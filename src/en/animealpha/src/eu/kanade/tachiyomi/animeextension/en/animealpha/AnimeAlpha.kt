package eu.kanade.tachiyomi.animeextension.en.animealpha

import androidx.preference.PreferenceScreen
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.utils.Source
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.formatBytes
import keiyoushi.utils.parallelMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * AnimeAlpha (animealpha.cc): low-size AV1 anime that is free to stream or download.
 *
 * The extension talks to the site the way the site's own pages do: the live-search JSON API
 * (`/search_suggestions`), the RSS feeds for the latest episodes, the per-season `/episodes/` listings
 * and the download page / `/get_ddl/` API for the video links. The site's Anubis bot check and its
 * IP gateway are handled inside the HTTP client (see Interceptors.kt), so nothing has to be solved
 * by hand in WebView.
 */
class AnimeAlpha : Source() {
    override val name = "AnimeAlpha"

    override val lang = "en"

    override val supportsLatest = true

    override val baseUrl: String
        get() = customDomain() ?: DEFAULT_DOMAIN

    private val prefQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    private val prefServer by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

    private val prefLatest by preferences.delegate(PREF_LATEST_KEY, PREF_LATEST_DEFAULT)

    private val showTorrent by preferences.delegate(PREF_TORRENT_KEY, PREF_TORRENT_DEFAULT)

    private val loadCovers by preferences.delegate(PREF_COVERS_KEY, PREF_COVERS_DEFAULT)

    private fun customDomain(): String? = preferences
        .getString(PREF_DOMAIN_KEY, "")
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.startsWith("http") && it.toHttpUrlOrNull() != null }

    private fun siteHost(): String = baseUrl.toHttpUrlOrNull()?.host ?: DEFAULT_HOST

    private fun isSiteUrl(url: HttpUrl): Boolean = url.host == siteHost()

    override val client: OkHttpClient =
        network.client
            .newBuilder()
            .addInterceptor(GatewayInterceptor(::isSiteUrl))
            .addInterceptor(AnubisInterceptor(::isSiteUrl))
            .addInterceptor(ChallengeInterceptor(CloudflareInterceptor(network.client)))
            .rateLimit(RATE_LIMIT_PERMITS, 1.seconds) { isSiteUrl(it) }
            .build()

    override fun headersBuilder(): Headers.Builder = super
        .headersBuilder()
        .set("Accept-Language", "en-US,en;q=0.9")

    private fun pageHeaders(referer: String? = null): Headers = headersBuilder()
        .set("Accept", ACCEPT_HTML)
        .set("Referer", referer ?: "$baseUrl/")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "same-origin")
        .set("Upgrade-Insecure-Requests", "1")
        .build()

    private fun jsonHeaders(referer: String): Headers = headersBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Referer", referer)
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-origin")
        .build()

    private fun apiHeaders(
        referer: String,
        token: String?,
    ): Headers = headersBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Referer", referer)
        .set("Origin", baseUrl)
        .set("X-Requested-With", "XMLHttpRequest")
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-origin")
        .apply { if (!token.isNullOrBlank()) set("X-DDL-Token", token) }
        .build()

    /**
     * Headers handed to the player. The site's cookies (identity + bot-check) and Origin only go to
     * the site's own host; file hosts just get the Referer.
     */
    private fun videoHeaders(url: String): Headers {
        val builder =
            headersBuilder()
                .set("Accept", "*/*")
                .set("Referer", "$baseUrl/")
        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl != null && isSiteUrl(httpUrl)) {
            builder.set("Origin", baseUrl)
            val cookies = client.cookieJar.loadForRequest(httpUrl)
            if (cookies.isNotEmpty()) builder.set("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
        }
        return builder.build()
    }

    private suspend fun fetchDocument(
        url: String,
        referer: String? = null,
        cache: CacheControl = PAGE_CACHE,
    ): Document = client.get(url, pageHeaders(referer), cache).useAsJsoup()

    // ============================== Popular ===============================

    /**
     * Page 1 is the home page (spotlight, newest episodes and dub releases, all with posters);
     * everything after it continues with the full catalog.
     */
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return fetchCatalog(FilterParams(), page - 1)

        val home = fetchDocument("$baseUrl/", cache = FRESH_CACHE)
        val animes = parseCards(home.selectFirst("main") ?: home)
        if (animes.isEmpty()) return fetchCatalog(FilterParams(), 1)
        return AnimesPage(animes.fillCovers(), hasNextPage = true)
    }

    // =============================== Latest ===============================

    @Volatile private var latestCache: Triple<String, Long, List<FeedItem>>? = null

    /** Newest episodes first, taken from the site's RSS feed and paged locally. */
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val feed = latestFeed()
        val from = (page - 1) * LATEST_PAGE_SIZE
        if (from >= feed.size) return AnimesPage(emptyList(), false)
        val slice = feed.drop(from).take(LATEST_PAGE_SIZE)
        return AnimesPage(slice.map { it.toAnime() }.fillCovers(), hasNextPage = from + slice.size < feed.size)
    }

    private suspend fun latestFeed(): List<FeedItem> {
        val key = "$baseUrl|$prefLatest"
        val now = System.currentTimeMillis()
        latestCache?.let { (cachedKey, time, items) -> if (cachedKey == key && now - time < LATEST_TTL_MS) return items }

        val items = runCatching { fetchRssFeed() }.getOrNull().orEmpty().ifEmpty { fetchHomeFeed() }
        if (items.isNotEmpty()) latestCache = Triple(key, now, items)
        return items
    }

    private suspend fun fetchRssFeed(): List<FeedItem> {
        val feedPath =
            when (prefLatest) {
                LATEST_SUB -> "/airing/rss/sub"
                LATEST_DUAL -> "/airing/rss/dual"
                else -> "/airing/rss"
            }
        val headers =
            headersBuilder()
                .set("Accept", "application/rss+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5")
                .set("Referer", "$baseUrl/")
                .build()
        val body = client.get("$baseUrl$feedPath", headers, FRESH_CACHE).bodyString()
        val doc = Jsoup.parse(body, baseUrl, Parser.xmlParser())
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
        val items =
            doc.select("item").mapNotNull { item ->
                val itemPath = animePath(item.selectFirst("link")?.text().orEmpty()) ?: return@mapNotNull null
                val itemTitle =
                    item
                        .selectFirst("title")
                        ?.text()
                        .orEmpty()
                        .ifEmpty { return@mapNotNull null }
                val time = runCatching { format.parse(item.selectFirst("pubDate")?.text().orEmpty())?.time }.getOrNull() ?: 0L
                FeedItem(itemPath, itemTitle, time)
            }
        // A combined feed is two lists glued together: keep the newest entry of every title first.
        return items.sortedByDescending { it.time }.distinctBy { it.path }
    }

    private suspend fun fetchHomeFeed(): List<FeedItem> {
        val home = fetchDocument("$baseUrl/", cache = FRESH_CACHE)
        val root = home.selectFirst("#episodeGrid, .episode-grid") ?: home.selectFirst("main") ?: home
        return parseCards(root).map { FeedItem(it.url, it.title) }
    }

    private fun FeedItem.toAnime(): SAnime = SAnime.create().apply {
        url = path
        title = this@toAnime.title
        thumbnail_url = coverCache[path]
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val text = query.trim()

        // A pasted site link (or bare id) opens that title directly.
        pathFromQuery(text)?.let { path ->
            val anime = getAnimeDetails(SAnime.create().apply { url = path })
            anime.url = path
            return AnimesPage(listOf(anime), false)
        }

        if (text.isNotEmpty()) return searchText(text, page)

        val params = parseFilters(filters)
        if (params.list.isNotEmpty()) return fetchAiring(params.list, page)
        return fetchCatalog(params, page)
    }

    /**
     * The first page comes from the site's live-search API (a few hundred bytes, posters and genres
     * included); the second page is the site's full result page minus what is already shown.
     */
    private suspend fun searchText(
        text: String,
        page: Int,
    ): AnimesPage {
        val suggestions =
            if (text.length >= 2) {
                runCatching { fetchSuggestions(text) }.getOrNull().orEmpty()
            } else {
                emptyList()
            }
        val shown = suggestions.mapNotNull { animePath(it.url) }.toSet()

        if (page == 1 && suggestions.isNotEmpty()) {
            val animes =
                suggestions.mapNotNull { suggestion ->
                    val path = animePath(suggestion.url) ?: return@mapNotNull null
                    SAnime.create().apply {
                        url = path
                        title = suggestion.name
                        thumbnail_url = coverCache[path]
                        genre = suggestion.genres.joinToString().ifEmpty { null }
                    }
                }
            return AnimesPage(animes, hasNextPage = suggestions.size >= SUGGESTION_LIMIT)
        }
        if (page > 2 || (page == 2 && suggestions.isEmpty())) return AnimesPage(emptyList(), false)

        val url = "$baseUrl/search".toHttpUrl().newBuilder().addQueryParameter("q", text).build()
        val doc = fetchDocument(url.toString())
        val animes = parseCards(doc.selectFirst("main") ?: doc).filter { it.url !in shown }
        return AnimesPage(animes.fillCovers(), hasNextPage = false)
    }

    private fun pathFromQuery(text: String): String? = when {
        text.startsWith("http", ignoreCase = true) -> animePath(text)
        ID_REGEX.matches(text) -> "/anime/$text"
        else -> null
    }

    private val suggestionCache = ConcurrentHashMap<String, Pair<Long, List<SuggestionDto>>>()

    /** The site's live-search API; every answer also teaches the cover cache. */
    private suspend fun fetchSuggestions(query: String): List<SuggestionDto> {
        val key = query.lowercase()
        val now = System.currentTimeMillis()
        suggestionCache[key]?.let { (time, list) -> if (now - time < SUGGESTION_TTL_MS) return list }

        val url = "$baseUrl/search_suggestions".toHttpUrl().newBuilder().addQueryParameter("q", query).build()
        val suggestions = client.get(url, jsonHeaders("$baseUrl/"), FRESH_CACHE).parseAs<SuggestionsDto>().suggestions
        suggestions.forEach { suggestion ->
            val path = animePath(suggestion.url) ?: return@forEach
            rememberCover(path, normalizeCover(suggestion.image, LIST_COVER_SIZE))
        }
        if (suggestionCache.size > SUGGESTION_CACHE_SIZE) suggestionCache.clear()
        suggestionCache[key] = now to suggestions
        return suggestions
    }

    // ============================== Catalog ===============================

    override fun getFilterList(): AnimeFilterList = buildFilterList()

    private suspend fun fetchCatalog(
        params: FilterParams,
        page: Int,
    ): AnimesPage {
        val url =
            "$baseUrl/anime"
                .toHttpUrl()
                .newBuilder()
                .apply {
                    if (params.sort.isNotEmpty() && params.sort != "latest") addQueryParameter("sort", params.sort)
                    if (params.genres.isNotEmpty()) addQueryParameter("genres", params.genres.joinToString(","))
                    if (params.yearFrom.isNotEmpty()) addQueryParameter("year_from", params.yearFrom)
                    if (params.yearTo.isNotEmpty()) addQueryParameter("year_to", params.yearTo)
                    if (params.letter.isNotEmpty()) addQueryParameter("letter", params.letter)
                    if (page > 1) addQueryParameter("page", page.toString())
                }.build()

        val response = client.get(url, pageHeaders(), PAGE_CACHE, ensureSuccess = false)
        if (response.code == 404) {
            // The site answers a genre / year combination without matches with a 404.
            response.close()
            return AnimesPage(emptyList(), false)
        }
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code} while loading the catalog")
        }
        val doc = response.useAsJsoup()
        val animes = parseCards(doc.selectFirst("main") ?: doc)
        return AnimesPage(animes.fillCovers(), doc.hasNextPage(page))
    }

    /** `/airing/sub` and `/airing/dual` list every airing title on one page; page them locally. */
    private suspend fun fetchAiring(
        list: String,
        page: Int,
    ): AnimesPage {
        val doc = fetchDocument("$baseUrl/$list", cache = FRESH_CACHE)
        val all = parseCards(doc.selectFirst("main") ?: doc)
        val from = (page - 1) * LATEST_PAGE_SIZE
        if (from >= all.size) return AnimesPage(emptyList(), false)
        val slice = all.drop(from).take(LATEST_PAGE_SIZE)
        return AnimesPage(slice.fillCovers(), hasNextPage = from + slice.size < all.size)
    }

    private fun Document.hasNextPage(current: Int): Boolean {
        val links = select("a[href*='page=']")
        if (links.any { it.text().equals("Next", ignoreCase = true) || it.attr("rel") == "next" }) return true
        return links
            .mapNotNull {
                it
                    .absUrl("href")
                    .toHttpUrlOrNull()
                    ?.queryParameter("page")
                    ?.toIntOrNull()
            }.any { it > current }
    }

    // ============================ List parsing ============================

    private val coverCache = ConcurrentHashMap<String, String>()

    /**
     * Collects every `/anime/<id>` link inside [root] as one entry and reads its title and poster from
     * the surrounding card. The same logic serves the spotlight, the episode grids, search results,
     * the A–Z index and the "more like this" row, whose markup differs.
     */
    private fun parseCards(root: Element): List<SAnime> {
        val result = LinkedHashMap<String, SAnime>()
        root.select("a[href]").forEach { link ->
            val path = animePath(link.absUrl("href").ifEmpty { link.attr("href") }) ?: return@forEach
            val card = link.closest(CARD_SELECTOR) ?: link
            val scope = if (card !== link && card.linkedAnimeCount() > 1) link else card
            val img = scope.selectFirst("img")
            val title =
                scope
                    .selectFirst(TITLE_SELECTOR)
                    ?.text()
                    .orEmpty()
                    .ifEmpty { img?.attr("alt").orEmpty() }
                    .ifEmpty { link.attr("title") }
                    .ifEmpty { link.text() }
                    .trim()
            if (title.isEmpty() || title.lowercase() in IGNORED_TITLES) return@forEach

            val thumbnail = normalizeCover(img?.imageUrl(), LIST_COVER_SIZE)
            val existing = result[path]
            if (existing == null) {
                result[path] =
                    SAnime.create().apply {
                        url = path
                        this.title = title
                        thumbnail_url = thumbnail
                    }
            } else if (thumbnail != null && (existing.thumbnail_url == null || isPoster(thumbnail))) {
                existing.thumbnail_url = thumbnail
            }
            rememberCover(path, thumbnail)
        }
        return result.values.toList().onEach { anime ->
            if (anime.thumbnail_url == null) anime.thumbnail_url = coverCache[anime.url]
        }
    }

    private fun Element.linkedAnimeCount(): Int = select("a[href*='/anime/']")
        .mapNotNull { animePath(it.absUrl("href").ifEmpty { it.attr("href") }) }
        .distinct()
        .size

    private fun Element.imageUrl(): String? = listOf("data-src", "data-lazy-src", "data-original", "src")
        .firstNotNullOfOrNull { attr -> absUrl(attr).takeIf { it.isNotBlank() && !it.startsWith("data:") } }

    /** Returns `/anime/<id>` for a link to an anime page of this site, otherwise `null`. */
    private fun animePath(href: String): String? {
        if (href.isBlank()) return null
        val url = if (href.startsWith("http")) href.toHttpUrlOrNull() else "$baseUrl/${href.trimStart('/')}".toHttpUrlOrNull()
        url ?: return null
        if (url.host != siteHost()) return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size != 2 || segments[0] != "anime") return null
        return "/anime/${segments[1]}"
    }

    /**
     * TMDB serves the same image at any size; the site stores the 2 MB originals, which are far too
     * heavy for a grid on a slow connection, so lists ask for a smaller rendition.
     */
    private fun normalizeCover(
        url: String?,
        size: String,
    ): String? {
        val value = url?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("data:") } ?: return null
        val http = value.toHttpUrlOrNull() ?: return value
        if (http.host == "image.tmdb.org" || http.host == "media.themoviedb.org") {
            val file = http.pathSegments.lastOrNull().orEmpty()
            if (file.isNotEmpty()) return "https://image.tmdb.org/t/p/$size/$file"
        }
        return value
    }

    private fun isPoster(url: String): Boolean = url.contains("livechart.me") || url.contains("animeschedule.net")

    /** Portrait posters (LiveChart, AnimeSchedule) beat the landscape backdrops of the other sources. */
    private fun rememberCover(
        path: String,
        url: String?,
    ) {
        val cover = url ?: return
        val existing = coverCache[path]
        if (existing == null || (isPoster(cover) && !isPoster(existing))) coverCache[path] = cover
    }

    @Volatile private var scheduleLoadedAt = 0L

    /** The schedule page carries LiveChart posters for every airing title: one cheap request. */
    private suspend fun ensureScheduleCovers() {
        val now = System.currentTimeMillis()
        if (now - scheduleLoadedAt < SCHEDULE_TTL_MS) return
        scheduleLoadedAt = now
        runCatching {
            val doc = fetchDocument("$baseUrl/schedule", cache = FRESH_CACHE)
            val payload = doc.selectFirst("script#initialPayload")?.data().orEmpty()
            val root = json.parseToJsonElement(payload) as? JsonObject ?: return@runCatching
            (root["days"] as? JsonArray)?.forEach { day ->
                ((day as? JsonObject)?.get("slots") as? JsonArray)?.forEach { slot ->
                    ((slot as? JsonObject)?.get("shows") as? JsonArray)?.forEach { show ->
                        val obj = show as? JsonObject ?: return@forEach
                        val path = animePath(obj.string("href").orEmpty()) ?: return@forEach
                        rememberCover(path, obj.string("poster_2x") ?: obj.string("poster"))
                    }
                }
            }
        }
    }

    /**
     * The A–Z index has no posters. Airing titles are covered by the schedule page; the rest is asked
     * from the live-search API (tiny answers, a few at a time, never longer than a few seconds).
     */
    private suspend fun List<SAnime>.fillCovers(): List<SAnime> {
        forEach { anime -> if (anime.thumbnail_url.isNullOrBlank()) coverCache[anime.url]?.let { anime.thumbnail_url = it } }
        if (!loadCovers) return this
        if (none { it.thumbnail_url.isNullOrBlank() }) return this

        ensureScheduleCovers()
        forEach { anime -> if (anime.thumbnail_url.isNullOrBlank()) coverCache[anime.url]?.let { anime.thumbnail_url = it } }

        val missing = filter { it.thumbnail_url.isNullOrBlank() }
        if (missing.isNotEmpty()) {
            val permits = Semaphore(COVER_CONCURRENCY)
            withTimeoutOrNull(COVER_TIMEOUT_MS) {
                missing.parallelMap { anime ->
                    permits.withPermit {
                        if (coverCache[anime.url] == null) runCatching { fetchSuggestions(anime.title) }
                        coverCache[anime.url]?.let { anime.thumbnail_url = it }
                    }
                }
            }
        }
        return this
    }

    // ============================== Details ===============================

    private val detailCache = ConcurrentHashMap<String, Pair<Long, Document>>()

    private suspend fun detailDocument(path: String): Document {
        val url = baseUrl + path
        val now = System.currentTimeMillis()
        detailCache[url]?.let { (time, doc) -> if (now - time < DETAIL_TTL_MS) return doc }
        val doc = fetchDocument(url, cache = FRESH_CACHE)
        if (detailCache.size > DETAIL_CACHE_SIZE) detailCache.clear()
        detailCache[url] = now to doc
        return doc
    }

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + "/anime/" + idOf(anime.url)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val path = "/anime/" + idOf(anime.url)
        val doc = detailDocument(path)

        val title = doc.selectFirst("h1")?.text()?.ifEmpty { null } ?: anime.title
        var genres = doc.detailGenres()
        var cover =
            coverCache[path]
                ?: normalizeCover(
                    doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifEmpty { null }
                        ?: doc.selectFirst("main img, img")?.imageUrl(),
                    DETAIL_COVER_SIZE,
                )

        if (genres.isEmpty() || cover == null) {
            // The live-search API knows the genres and the poster of every title.
            val match =
                runCatching { fetchSuggestions(title) }
                    .getOrNull()
                    ?.firstOrNull { animePath(it.url) == path }
            if (match != null) {
                if (genres.isEmpty()) genres = match.genres
                if (cover == null) cover = normalizeCover(match.image, DETAIL_COVER_SIZE)
            }
        }

        val episodes =
            EPISODES_REGEX
                .find(
                    doc
                        .selectFirst("h1")
                        ?.parent()
                        ?.text()
                        .orEmpty(),
                )?.groupValues
                ?.get(1)
                ?: EPISODES_REGEX.find(doc.body().text())?.groupValues?.get(1)
        val synopsis = doc.detailSynopsis()

        return SAnime.create().apply {
            url = path
            this.title = title
            thumbnail_url = cover ?: anime.thumbnail_url
            genre = genres.joinToString().ifEmpty { null }
            // The site shows "N/A" while a series is still airing and the real total once it is done.
            status =
                when {
                    episodes == null -> SAnime.UNKNOWN
                    episodes.equals("N/A", ignoreCase = true) -> SAnime.ONGOING
                    else -> SAnime.COMPLETED
                }
            description =
                buildString {
                    if (!synopsis.isNullOrBlank()) append(synopsis)
                    if (episodes != null && episodes.all { it.isDigit() }) {
                        if (isNotEmpty()) append("\n\n")
                        append("Episodes: ").append(episodes)
                    }
                }.ifEmpty { null }
            initialized = true
        }
    }

    private fun Document.detailGenres(): List<String> {
        val h1 = selectFirst("h1") ?: return emptyList()
        val containers = listOfNotNull(h1, h1.parent())
        for (container in containers) {
            val list =
                generateSequence(container.nextElementSibling()) { it.nextElementSibling() }
                    .takeWhile { it.tagName() != "h2" }
                    .firstNotNullOfOrNull { sibling -> if (sibling.tagName() == "ul") sibling else sibling.selectFirst("ul") }
            val genres =
                list
                    ?.select("li")
                    ?.map { it.text() }
                    ?.filter { it.isNotEmpty() && it.length <= 30 }
                    .orEmpty()
            if (genres.isNotEmpty()) return genres.distinct()
        }
        return select("[class*=genre] a, a[href*='genres=']").map { it.text() }.filter { it.isNotEmpty() && it.length <= 30 }.distinct()
    }

    private fun Document.detailSynopsis(): String? {
        selectFirst(".synopsis, .anime-synopsis, [class*=synopsis], #synopsis")
            ?.text()
            ?.removePrefix("Synopsis")
            ?.removeSuffix("Read more")
            ?.trim()
            ?.takeIf { it.length > 20 }
            ?.let { return it }
        val heading = select("h2, h3").firstOrNull { it.text().equals("Synopsis", ignoreCase = true) }
        heading
            ?.let { generateSequence(it.nextElementSibling()) { next -> next.nextElementSibling() } }
            ?.firstOrNull { it.tagName() != "button" && it.text().length > 20 }
            ?.text()
            ?.removeSuffix("Read more")
            ?.trim()
            ?.let { return it }
        return selectFirst("meta[name=description]")?.attr("content")?.ifEmpty { null }
    }

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> {
        val path = "/anime/" + idOf(anime.url)
        val doc = detailDocument(path)
        val root = doc.selectFirst(".more-like-this, .similar-anime-grid, .similar-anime") ?: return emptyList()
        return parseCards(root).filter { it.url != path }.fillCovers()
    }

    // ============================== Episodes ==============================

    private val listingCache = ConcurrentHashMap<String, Pair<Long, List<EpisodeEntry>>>()

    private class SeasonListing(
        val season: String,
        val entries: List<EpisodeEntry>,
    )

    override fun getEpisodeUrl(episode: SEpisode): String {
        val ref = EpisodeRef.parse(episode.url) ?: return baseUrl + episode.url.substringBefore('#')
        val resolution = prefQuality.toIntOrNull()?.let { STANDARD_RESOLUTIONS[it] } ?: STANDARD_RESOLUTIONS.values.first()
        return listingUrl(ref.id, ref.season, resolution).toString()
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = idOf(anime.url)
        val hinted = runCatching { detailDocument("/anime/$id").seasonHints(id) }.getOrNull().orEmpty()
        val seasons = discoverSeasons(id, hinted)
        if (seasons.isEmpty()) {
            throw Exception("No episodes found. Pull down to refresh; if the site works in WebView, try again in a moment.")
        }

        val ordered =
            seasons.sortedWith(
                compareBy<SeasonListing> { it.season.equals(MOVIE_SEASON, ignoreCase = true) }
                    .thenBy { it.season.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.season },
            )
        val multiSeason = ordered.size > 1

        val episodes =
            ordered.flatMapIndexed { seasonIndex, listing ->
                val isMovie = listing.season.equals(MOVIE_SEASON, ignoreCase = true)
                val entries =
                    listing.entries
                        .distinctBy { it.number to it.audio }
                        .sortedWith(compareBy<EpisodeEntry> { it.number }.thenBy { it.audio.orEmpty() })
                entries.map { entry ->
                    SEpisode.create().apply {
                        url = episodeUrl(id, listing.season, entry.number, entry.audio.takeIf { audio -> entries.count { it.number == entry.number } > 1 })
                        name =
                            when {
                                isMovie && entries.size == 1 -> "Movie"
                                multiSeason || isMovie -> "${seasonLabel(listing.season)} · Episode ${formatNumber(entry.number)}"
                                else -> "Episode ${formatNumber(entry.number)}"
                            }
                        // Seasons restart their numbering, so spread them out to keep every number unique.
                        episode_number = if (multiSeason) (seasonIndex + 1) * SEASON_NUMBER_STEP + entry.number else entry.number
                        scanlator = entry.audio
                    }
                }
            }
        // The app expects the newest entry first.
        return episodes.reversed()
    }

    /** Season keys the detail page itself links to (`/episodes/<id>/<season>/…`, `data-season`). */
    private fun Document.seasonHints(id: String): List<String> {
        val fromLinks =
            select("a[href*='/episodes/']").mapNotNull { link ->
                val segments = link.absUrl("href").toHttpUrlOrNull()?.pathSegments ?: return@mapNotNull null
                if (segments.size >= 3 && segments[0] == "episodes" && segments[1] == id) segments[2] else null
            }
        val fromData = select("[data-season]").map { it.attr("data-season").trim() }
        return (fromLinks + fromData).filter { it.isNotEmpty() }.distinct()
    }

    private fun listingOrder(): List<String> {
        val preferred = prefQuality.toIntOrNull()
        return STANDARD_RESOLUTIONS.entries
            .sortedWith(compareByDescending<Map.Entry<Int, String>> { it.key == preferred }.thenByDescending { it.key })
            .map { it.value }
    }

    /**
     * The detail page does not list its seasons, but the `/episodes/<id>/<season>/<resolution>` listings
     * answer 404 for seasons that do not exist, so the seasons are probed in small parallel batches
     * until one comes back empty (a movie lives under the season key "movie").
     */
    private suspend fun discoverSeasons(
        id: String,
        hinted: List<String>,
    ): List<SeasonListing> = coroutineScope {
        val order = listingOrder()

        suspend fun probe(
            season: String,
            allResolutions: Boolean,
        ): SeasonListing? {
            for (resolution in if (allResolutions) order else order.take(2)) {
                val list = fetchListing(id, season, resolution)
                if (list.isNotEmpty()) return SeasonListing(season, list)
            }
            return null
        }

        if (hinted.isNotEmpty()) {
            return@coroutineScope hinted.parallelMap { probe(it, allResolutions = true) }.filterNotNull()
        }

        val found = mutableListOf<SeasonListing>()
        val movie = async { probe(MOVIE_SEASON, allResolutions = false) }
        var next = 1
        while (next <= MAX_SEASONS) {
            val batch = (next until next + SEASON_BATCH).toList()
            val results = batch.parallelMap { probe(it.toString(), allResolutions = it == 1) }
            found += results.filterNotNull()
            // Stop once a batch ran past the last season.
            if (results.last() == null) break
            next += SEASON_BATCH
        }
        movie.await()?.let { found += it }
        found
    }

    /** Episode files of one season at one resolution; empty when that combination does not exist. */
    private suspend fun fetchListing(
        id: String,
        season: String,
        resolution: String,
        fresh: Boolean = false,
    ): List<EpisodeEntry> {
        val url = listingUrl(id, season, resolution).toString()
        val now = System.currentTimeMillis()
        if (!fresh) listingCache[url]?.let { (time, list) -> if (now - time < LISTING_TTL_MS) return list }

        val response = client.get(url, pageHeaders("$baseUrl/anime/$id"), CacheControl.FORCE_NETWORK, ensureSuccess = false)
        if (!response.isSuccessful) {
            // 404 = no such season / resolution. Anything else is treated the same so probing never aborts.
            response.close()
            return emptyList()
        }
        val entries = parseListing(response.bodyString(), season, url)
        if (listingCache.size > LISTING_CACHE_SIZE) listingCache.clear()
        listingCache[url] = now to entries
        return entries
    }

    private fun listingUrl(
        id: String,
        season: String,
        resolution: String,
    ): HttpUrl = baseUrl
        .toHttpUrl()
        .newBuilder()
        .addPathSegment("episodes")
        .addPathSegment(id)
        .addPathSegment(season)
        .addPathSegment(resolution)
        .build()

    /** The `/episodes/` endpoint answers either an HTML list of download links or a JSON array of `{num, label, audio, href, ddl}`. */
    private fun parseListing(
        body: String,
        season: String,
        pageUrl: String,
    ): List<EpisodeEntry> {
        val text = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (text.startsWith("[")) {
            val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull()
            if (array != null) {
                return array.mapIndexedNotNull { index, element ->
                    val obj = element as? JsonObject ?: return@mapIndexedNotNull null
                    buildEntry(
                        season = season,
                        href = obj.string("href") ?: return@mapIndexedNotNull null,
                        label = obj.string("label").orEmpty(),
                        number = obj.string("num")?.toFloatOrNull(),
                        audio = obj.string("audio"),
                        ddl = (obj["ddl"] as? JsonObject)?.toString(),
                        index = index,
                    )
                }
            }
        }

        val doc = Jsoup.parse(body, pageUrl)
        return doc.select("a[href*='/download/']").mapIndexedNotNull { index, link ->
            buildEntry(
                season = season,
                href = link.attr("href"),
                label = link.text(),
                number = null,
                audio = null,
                ddl = null,
                index = index,
            )
        }
    }

    private fun buildEntry(
        season: String,
        href: String,
        label: String,
        number: Float?,
        audio: String?,
        ddl: String?,
        index: Int,
    ): EpisodeEntry? {
        val path = sitePath(href.replace("&amp;", "&")) ?: return null
        if (!path.startsWith("/download/")) return null
        val episodeNumber =
            number?.takeIf { it > 0 }
                ?: EPISODE_LABEL_REGEX
                    .find(label)
                    ?.groupValues
                    ?.get(1)
                    ?.toFloatOrNull()
                ?: LEADING_NUMBER_REGEX
                    .find(label)
                    ?.groupValues
                    ?.get(1)
                    ?.toFloatOrNull()
                ?: (index + 1).toFloat()
        val audioTag = audioTag(audio) ?: audioTag(AUDIO_LABEL_REGEX.find(label)?.groupValues?.get(1))
        return EpisodeEntry(season, episodeNumber, audioTag, path, ddl)
    }

    // =============================== Hosters ==============================

    /** Per-episode signal: backup qualities start once the primary one is done. */
    private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** The site's gateway bans bursts, so backup qualities resolve two at a time. */
    private val backupPermits = Semaphore(BACKUP_CONCURRENCY)

    /**
     * One hoster per quality. None of them is lazy: the player only auto-loads non-lazy hosters, so
     * all-lazy hosters would end in "No available videos". The preferred quality is fetched in full;
     * the other listings only get a short grace period, so one slow page on a weak connection never
     * holds back the episode.
     */
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = coroutineScope {
        val ref =
            EpisodeRef.parse(episode.url)
                ?: throw Exception("Old episode link — refresh the episode list (pull down on the anime page)")
        val preferred = prefQuality.toIntOrNull()
        val ordered =
            STANDARD_RESOLUTIONS.entries.sortedWith(
                compareByDescending<Map.Entry<Int, String>> { it.key == preferred }.thenByDescending { it.key },
            )

        val jobs =
            ordered.map { (height, resolution) ->
                Triple(height, resolution, async { runCatching { fetchListing(ref.id, ref.season, resolution, fresh = true) }.getOrNull() })
            }
        var deadline = 0L
        val found =
            jobs.mapNotNull { (height, resolution, job) ->
                val entries =
                    if (deadline == 0L) {
                        job.await().also { deadline = System.currentTimeMillis() + OPTIONAL_LISTING_MS }
                    } else {
                        withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(1L)) { job.await() }
                    }
                entries?.findMatch(ref)?.let { Triple(height, resolution, it) }
            }
        jobs.forEach { it.third.cancel() }
        if (found.isEmpty()) {
            throw Exception("No servers found for this episode. Pull down on the anime page to refresh, then retry.")
        }

        val gateKey = episode.url + "#" + System.nanoTime()
        if (gates.size > GATE_CACHE_SIZE) gates.clear()
        gates[gateKey] = CompletableDeferred()

        found.mapIndexed { index, (height, resolution, match) ->
            val data =
                HosterData(
                    href = match.href,
                    referer = listingUrl(ref.id, ref.season, resolution).toString(),
                    resolution = height,
                    id = ref.id,
                    season = ref.season,
                    number = match.number,
                    audio = match.audio,
                    ddl = match.ddl,
                    primary = index == 0,
                    gate = gateKey,
                )
            Hoster(
                hosterUrl = baseUrl + match.href,
                hosterName = "${height}p" + (match.audio?.let { " · $it" } ?: ""),
                internalData = data.toJsonString(),
            )
        }
    }

    private fun List<EpisodeEntry>.findMatch(ref: EpisodeRef): EpisodeEntry? {
        val sameNumber = filter { it.number == ref.number }
        if (sameNumber.isEmpty()) return null
        return sameNumber.firstOrNull { it.audio.equals(ref.audio, ignoreCase = true) } ?: sameNumber.firstOrNull()
    }

    // =============================== Videos ===============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        hoster.videoList?.takeIf { it.isNotEmpty() }?.let { return it }
        val data = hoster.internalData.parseAs<HosterData>()
        val gate = gates[data.gate]

        if (data.primary) {
            try {
                return resolveHoster(data, hoster.hosterName)
            } finally {
                gate?.complete(Unit)
            }
        }
        // Give the preferred quality the connection first; it is the one that plays.
        gate?.let { withTimeoutOrNull(PRIMARY_HEAD_START_MS) { it.await() } }
        return backupPermits.withPermit { resolveHoster(data, hoster.hosterName) }
    }

    private suspend fun resolveHoster(
        data: HosterData,
        name: String,
    ): List<Video> {
        val videos = buildVideos(resolveLinks(data), data)
        if (videos.isEmpty()) throw Exception("The site returned no playable link for $name")
        // `preferred` lets the player start this video as soon as it is ready instead of waiting
        // for every other quality to finish loading.
        return videos.sortVideos().mapIndexed { index, video ->
            video.copy(preferred = data.primary && index == 0)
        }
    }

    private suspend fun resolveLinks(data: HosterData): DdlLinks {
        data.ddl
            ?.let { raw -> runCatching { parseDdl(json.parseToJsonElement(raw)) }.getOrNull() }
            ?.takeIf { it.hasPlayable() }
            ?.let { return it }

        // Fast path: a recent X-DDL-Token is reused, skipping the download page round-trip.
        cachedToken()?.let { token ->
            val links = fetchDdl(listOf(fileNameOf(data.href)), token, baseUrl + data.href)
            if (links != null) return links
            tokenCache = null
        }

        return try {
            resolveDownloadPage(data)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The ?token= on download links is time-limited: reload the listing once for a fresh one.
            val refreshed = refreshHref(data) ?: throw e
            resolveDownloadPage(data.copy(href = refreshed))
        }
    }

    private suspend fun refreshHref(data: HosterData): String? {
        val resolution = STANDARD_RESOLUTIONS[data.resolution] ?: return null
        val ref = EpisodeRef(data.id, data.season, data.number, data.audio)
        return fetchListing(data.id, data.season, resolution, fresh = true)
            .findMatch(ref)
            ?.href
            ?.takeIf { it != data.href }
    }

    @Volatile private var tokenCache: Triple<String, String, Long>? = null

    private fun cachedToken(): String? {
        val (host, token, time) = tokenCache ?: return null
        if (host != baseUrl || System.currentTimeMillis() - time > TOKEN_TTL_MS) return null
        return token
    }

    private fun fileNameOf(href: String): String = decode(href.substringBefore('?').substringAfterLast('/'))

    /**
     * Walks the download page the way the site's own script does: the page reports its state in
     * `#main-content[data-state]`: `adcheck` asks for a small hidden form to be posted back,
     * `preparing` means the site is still getting the file ready (this takes a few seconds, the
     * page is simply asked again), and a ready page carries the direct / stream / torrent links
     * and the token for the `/get_ddl/` API.
     */
    private suspend fun resolveDownloadPage(data: HosterData): DdlLinks {
        val pageUrl = baseUrl + data.href
        val referer = data.referer.toSiteUrl()
        var doc = loadPage(pageUrl, referer)
        var adChecks = 0
        var reloads = 0
        var state = ""
        while (true) {
            ensureNotBlocked(doc)
            state = doc.selectFirst("#main-content[data-state]")?.attr("data-state").orEmpty()
            when (state) {
                "adcheck" -> {
                    if (++adChecks > MAX_ADCHECKS) {
                        throw IOException("The site's download check failed. Open the episode in WebView once, then retry.")
                    }
                    val form =
                        doc.selectFirst("form#ddl-adcheck-form")
                            ?: throw IOException("The site's download check form is missing.")
                    val body =
                        FormBody
                            .Builder()
                            .apply {
                                form.select("input[name]:not([disabled])").forEach { add(it.attr("name"), it.attr("value")) }
                            }.build()
                    val action = form.absUrl("action").ifEmpty { pageUrl }
                    val headers = pageHeaders(pageUrl).newBuilder().set("Origin", baseUrl).build()
                    doc = client.post(action, headers, body, ensureSuccess = false).useAsJsoup()
                }

                "preparing" -> {
                    if (++reloads > MAX_PREPARE_RELOADS) throw IOException("The site is still preparing this video. Try again in a moment.")
                    delay(PREPARE_DELAY_MS)
                    doc = loadPage(pageUrl, referer)
                }

                "adblock", "error" -> {
                    throw IOException(doc.selectFirst("#error-message")?.text()?.ifEmpty { null } ?: "The site could not prepare this video.")
                }

                else -> {
                    break
                }
            }
        }

        val scripts = doc.select("script").joinToString("\n") { it.data() }
        val html = doc.outerHtml()
        val token = extractDdlToken(scripts) ?: extractDdlToken(html)
        val fileName =
            doc.selectFirst("#dl-file-name, .dl-file-name")?.text()?.ifEmpty { null }
                ?: doc.selectFirst("#file-details[data-file-name]")?.attr("data-file-name")?.ifEmpty { null }
                ?: PAGE_FILENAME_REGEX
                    .find(html)
                    ?.groupValues
                    ?.get(1)
                    ?.let { Parser.unescapeEntities(it, false) }
                ?: fileNameOf(data.href)
        val size = doc.selectFirst("#dl-stat-size")?.text()?.ifEmpty { null }

        val direct =
            firstAttr(
                doc,
                "#download-now-btn[data-href]" to "data-href",
                "a#download-link[href]" to "href",
                "a[href*='/dl/']" to "href",
            )
        val streamOrWatch =
            firstAttr(
                doc,
                "[data-stream]" to "data-stream",
                "iframe#anime-video-player[src], iframe.anime-video-player[src]" to "src",
                "dash-video[src], video source[src], video[src]" to "src",
            )
        val watch = extractWatchLink(html)
        val torrent = firstAttr(doc, "a#torrent-btn[href]" to "href", "a[href^='magnet:']" to "href")

        val ddl = token?.let { fetchDdl(listOf(fileName, fileNameOf(data.href)).distinct(), it, pageUrl) }
        if (token != null && ddl != null) tokenCache = Triple(baseUrl, token, System.currentTimeMillis())

        val streamIsWatch = streamOrWatch?.contains("/watch/") == true
        val links =
            DdlLinks(
                stream = ddl?.stream ?: streamOrWatch?.takeUnless { streamIsWatch }?.let(::absolute),
                download = ddl?.download ?: direct?.let(::absolute),
                watch = ddl?.watch ?: watch ?: streamOrWatch?.takeIf { streamIsWatch }?.let(::absolute),
                dash = ddl?.dash,
                torrent = ddl?.torrent ?: torrent?.let(::absolute),
                fileName = ddl?.fileName ?: fileName,
                fileSize = ddl?.fileSize ?: size,
                audioLanguages = ddl?.audioLanguages.orEmpty(),
                subtitles = ddl?.subtitles.orEmpty(),
            )
        if (!links.hasPlayable()) {
            throw IOException("The download page gave no playable link (state: ${state.ifEmpty { "ready" }}). Open the episode in WebView once, then retry.")
        }
        return links
    }

    private fun firstAttr(
        doc: Document,
        vararg selectors: Pair<String, String>,
    ): String? = selectors.firstNotNullOfOrNull { (selector, attr) ->
        doc
            .selectFirst(selector)
            ?.attr(attr)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private suspend fun loadPage(
        url: String,
        referer: String,
    ): Document = client.get(url, pageHeaders(referer), CacheControl.FORCE_NETWORK).useAsJsoup()

    private fun ensureNotBlocked(doc: Document) {
        if (doc.selectFirst("script#anubis_challenge") != null) {
            throw IOException("The site's bot check is still active. Open the site in WebView once, then retry.")
        }
        if (doc.location().contains("/home/blocked") || doc.title().contains("Access Blocked", ignoreCase = true)) {
            throw IOException("The site rejected this session. Refresh the episode list and retry.")
        }
    }

    private suspend fun fetchDdl(
        candidates: List<String>,
        token: String?,
        referer: String,
    ): DdlLinks? {
        for (candidate in candidates) {
            val url =
                baseUrl
                    .toHttpUrl()
                    .newBuilder()
                    .addPathSegment("get_ddl")
                    .apply { candidate.split('/').forEach { addEncodedPathSegment(encodeComponent(it)) } }
                    .build()
            val body =
                runCatching {
                    client.get(url, apiHeaders(referer, token), CacheControl.FORCE_NETWORK, ensureSuccess = false).bodyString()
                }.getOrNull() ?: continue
            val element = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: continue
            val links = parseDdl(element)
            if (links != null && links.hasPlayable()) return links
        }
        return null
    }

    private fun parseDdl(element: JsonElement): DdlLinks? {
        val root = element as? JsonObject ?: return null
        val obj = root["ddl"] as? JsonObject ?: root
        if (obj.string("success") == "false" && obj.string("download_link") == null) return null

        fun link(vararg keys: String) = keys
            .firstNotNullOfOrNull { key -> obj.string(key)?.takeIf { it.isNotBlank() } }
            ?.let(::absolute)

        val audio =
            ((obj["audio_details"] as? JsonObject)?.get("audio") as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.string("language")?.takeIf(String::isNotBlank) }
                ?.distinct()
                .orEmpty()

        val subtitles =
            (obj["subtitles"] as? JsonArray)
                ?.mapNotNull { item ->
                    val sub = item as? JsonObject ?: return@mapNotNull null
                    val url = listOf("url", "file", "src", "link").firstNotNullOfOrNull { sub.string(it) } ?: return@mapNotNull null
                    val label =
                        listOfNotNull(sub.string("language") ?: sub.string("label"), sub.string("format"))
                            .joinToString(" ")
                            .ifEmpty { "Subtitle" }
                    absolute(url) to label
                }.orEmpty()

        val size = obj.string("file_size")?.let { raw -> raw.toLongOrNull()?.formatBytes() ?: raw }

        return DdlLinks(
            stream = link("stream_link", "streamLink", "stream_url"),
            download = link("download_link", "downloadLink", "ddl_link", "direct_link"),
            watch = link("watch_link", "watchLink", "player_link"),
            dash = link("dash_link", "dashLink", "manifest_link", "mpd_link", "dash_url"),
            torrent = link("torrent_link", "torrentLink", "magnet_link", "magnet"),
            fileName = obj.string("file_name"),
            fileSize = size,
            audioLanguages = audio,
            subtitles = subtitles,
        )
    }

    private fun DdlLinks.hasPlayable() = stream != null || download != null || watch != null || dash != null || torrent != null

    private fun dashFromWatch(watchUrl: String?): String? {
        if (watchUrl == null) return null
        if (watchUrl.contains(".mpd")) return watchUrl
        if (!watchUrl.contains("/watch/")) return null
        return watchUrl
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
            .replace("/watch/", "/dash/") + "/manifest.mpd"
    }

    private suspend fun buildVideos(
        links: DdlLinks,
        data: HosterData,
    ): List<Video> = coroutineScope {
        val audio =
            data.audio ?: when {
                links.audioLanguages.size > 1 -> "Dual"
                links.audioLanguages.size == 1 -> links.audioLanguages.first()
                else -> null
            }
        val info = listOfNotNull(audio, links.fileSize).joinToString(" · ")

        fun title(server: String) = buildString {
            append(data.resolution).append("p · ").append(server)
            if (info.isNotEmpty()) append(" · ").append(info)
        }
        val subtitles = links.subtitles.map { Track(it.first, it.second) }

        // Resolve the site's redirect links in parallel, once, here.
        val stream = links.stream?.let { async { finalUrl(it, media = true) } }
        val direct = links.download?.let { async { finalUrl(it, media = true) } }
        val dash =
            links.dash
                ?: dashFromWatch(links.watch)
                ?: links.watch?.takeIf { prefServer == SERVER_DASH }?.let { dashFromWatch(finalUrl(it, media = false)) }

        val videos = mutableListOf<Video>()

        fun add(
            url: String?,
            server: String,
        ) {
            if (url.isNullOrBlank() || videos.any { it.videoUrl == url }) return
            videos +=
                Video(
                    videoUrl = url,
                    videoTitle = title(server),
                    resolution = data.resolution,
                    headers = videoHeaders(url),
                    subtitleTracks = subtitles,
                    mpvArgs = NETWORK_MPV_ARGS,
                    initialized = true,
                )
        }
        add(stream?.await(), SERVER_STREAM)
        add(direct?.await(), SERVER_DIRECT)
        add(dash, SERVER_DASH)
        links.torrent?.takeIf { showTorrent }?.let { videos += torrentVideo(it, title(SERVER_TORRENT), data) }
        videos
    }

    private val redirectCache = ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * The links the site hands out are short redirects (`/r/…`, `/dl/…`). Giving those to the
     * player would make it walk the redirect through the site's bot gateway again on every seek
     * and buffer refill, which trips the gateway's burst limit and stalls weak connections.
     * Following the redirect once here hands the player the final file URL directly.
     *
     * A tiny ranged GET is used because some file hosts refuse HEAD. Any failure falls back to the
     * original link, so this can only make playback faster.
     */
    private suspend fun finalUrl(
        url: String,
        media: Boolean,
    ): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return url
        if (!isSiteUrl(httpUrl)) return url
        val now = System.currentTimeMillis()
        redirectCache[url]?.let { (time, resolved) -> if (now - time < REDIRECT_TTL_MS) return resolved }

        val resolved =
            withTimeoutOrNull(REDIRECT_TIMEOUT_MS) {
                runCatching {
                    val headers = videoHeaders(url).newBuilder().set("Range", "bytes=0-0").build()
                    client.get(httpUrl, headers, CacheControl.FORCE_NETWORK, ensureSuccess = false).use { response ->
                        val type = response.header("Content-Type").orEmpty()
                        val ok = response.isSuccessful && (!media || !type.contains("html", ignoreCase = true))
                        response.request.url
                            .toString()
                            .takeIf { ok }
                    }
                }.getOrNull()
            } ?: return url

        if (redirectCache.size > REDIRECT_CACHE_SIZE) redirectCache.clear()
        redirectCache[url] = now to resolved
        return resolved
    }

    // ============================== Torrent ===============================

    /**
     * Torrent links are resolved only when that video is actually picked, so they never delay the
     * other servers. The player is only ever handed a proper magnet link with `index=` as its very
     * last parameter: the app's torrent engine reads everything after "index=" as a number.
     */
    private fun torrentVideo(
        url: String,
        title: String,
        data: HosterData,
    ): Video {
        if (url.startsWith("magnet:")) {
            return Video(
                videoUrl = TorrentMagnet.normalize(url, PUBLIC_TRACKERS),
                videoTitle = title,
                resolution = data.resolution,
                initialized = true,
            )
        }
        return Video(
            videoUrl = "$url#resolve",
            videoTitle = title,
            resolution = data.resolution,
            internalData = TORRENT_PREFIX + url,
            initialized = false,
        )
    }

    override suspend fun resolveVideo(video: Video): Video? {
        if (video.initialized || !video.internalData.startsWith(TORRENT_PREFIX)) return video
        val url = video.internalData.removePrefix(TORRENT_PREFIX)
        val magnet =
            withTimeoutOrNull(TORRENT_TIMEOUT_MS) { runCatching { torrentToMagnet(url) }.getOrNull() }
                ?: return null
        return video.copy(videoUrl = magnet, internalData = "", initialized = true)
    }

    private suspend fun torrentToMagnet(url: String): String {
        if (url.startsWith("magnet:")) return TorrentMagnet.normalize(url, PUBLIC_TRACKERS)
        var lastError: Throwable? = null
        repeat(TORRENT_ATTEMPTS) {
            try {
                val bytes =
                    client.get(url, videoHeaders(url), CacheControl.FORCE_NETWORK, ensureSuccess = false).use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code} while downloading the torrent")
                        response.body.bytes()
                    }
                return TorrentMagnet.fromTorrent(bytes, PUBLIC_TRACKERS)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
            }
        }
        throw IOException("Could not read the torrent file", lastError)
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val server = prefServer
        val quality = prefQuality.toIntOrNull()
        return sortedWith(
            compareByDescending<Video> { it.resolution == quality }
                .thenByDescending { it.videoTitle.contains(" · $server") }
                .thenBy { video ->
                    SERVER_ORDER.indexOfFirst { video.videoTitle.contains(" · $it") }.let { if (it < 0) SERVER_ORDER.size else it }
                },
        )
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "%s\nLower qualities start faster and use far less data on a slow connection",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred server",
            summary = "%s",
            entries = SERVER_ORDER,
            entryValues = SERVER_ORDER,
        )
        screen.addListPreference(
            key = PREF_LATEST_KEY,
            default = PREF_LATEST_DEFAULT,
            title = "Latest tab shows",
            summary = "%s",
            entries = listOf("All airing episodes", "English sub only", "Dual audio only"),
            entryValues = listOf(LATEST_ALL, LATEST_SUB, LATEST_DUAL),
        )
        screen.addSwitchPreference(
            key = PREF_TORRENT_KEY,
            default = PREF_TORRENT_DEFAULT,
            title = "Show torrent links",
            summary = "Adds the Torrent server (needs the app's torrent engine). The magnet link is prepared only when you pick it",
        )
        screen.addSwitchPreference(
            key = PREF_COVERS_KEY,
            default = PREF_COVERS_DEFAULT,
            title = "Load covers in lists",
            summary = "The A–Z catalog has no posters; fetch them in the background (small requests, never blocks the list)",
        )
        screen.addEditTextPreference(
            key = PREF_DOMAIN_KEY,
            default = "",
            title = "Custom domain",
            summary = "Leave empty for $DEFAULT_DOMAIN. Use this if the site moves (e.g. https://new-domain.example)",
            getSummary = { it.ifBlank { "Leave empty for $DEFAULT_DOMAIN. Use this if the site moves (e.g. https://new-domain.example)" } },
            validate = { it.isBlank() || it.trim().toHttpUrlOrNull() != null },
            validationMessage = { "Enter a full address starting with https://" },
            restartRequired = true,
        )
    }

    // =============================== Utils ================================

    private fun absolute(link: String): String = when {
        link.startsWith("magnet:") || link.startsWith("http://") || link.startsWith("https://") -> link
        link.startsWith("//") -> "https:$link"
        link.startsWith("/") -> baseUrl + link
        else -> "$baseUrl/$link"
    }

    /** `/download/…?token=…` for a site link given as an absolute URL or a path. */
    private fun sitePath(href: String): String? {
        if (href.isBlank()) return null
        if (!href.startsWith("http")) return "/" + href.trimStart('/')
        val url = href.toHttpUrlOrNull() ?: return null
        return url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
    }

    private fun String.toSiteUrl(): String {
        val url = toHttpUrlOrNull() ?: return absolute(this)
        if (!isSiteUrl(url)) return this
        return url
            .newBuilder()
            .host(siteHost())
            .build()
            .toString()
    }

    private fun extractDdlToken(text: String): String? = DDL_TOKEN_REGEXES.firstNotNullOfOrNull { regex ->
        regex
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.takeIf { it.length >= 10 }
    }

    private fun extractWatchLink(html: String): String? {
        val iframe =
            runCatching {
                Jsoup
                    .parse(html, baseUrl)
                    .selectFirst("iframe#anime-video-player, iframe[src*='/watch/'], iframe[src*='/r/']")
                    ?.attr("src")
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
        if (iframe != null) return absolute(iframe.replace("&amp;", "&"))
        return WATCH_REGEX
            .find(html)
            ?.value
            ?.replace("&amp;", "&")
            ?.replace("\\/", "/")
            ?.let(::absolute)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** "…/anime/ABC123" (or any variant, with query / fragment) → "ABC123". */
    private fun idOf(url: String): String = url
        .substringBefore('#')
        .substringBefore('?')
        .trimEnd('/')
        .substringAfterLast('/')

    private fun episodeUrl(
        id: String,
        season: String,
        number: Float,
        audio: String?,
    ): String = buildString {
        append("/anime/").append(id)
        append("#s=").append(encodeComponent(season))
        append("&e=").append(formatNumber(number))
        if (!audio.isNullOrBlank()) append("&a=").append(encodeComponent(audio))
    }

    /** A reference to one episode: `/anime/<id>#s=<season>&e=<number>[&a=<audio>]`. */
    private class EpisodeRef(
        val id: String,
        val season: String,
        val number: Float,
        val audio: String?,
    ) {
        companion object {
            fun parse(url: String): EpisodeRef? {
                val path = url.substringBefore('#')
                val fragment = url.substringAfter('#', "")
                val id = path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return null
                val params =
                    fragment
                        .split('&')
                        .filter { it.contains('=') }
                        .associate { it.substringBefore('=') to decode(it.substringAfter('=')) }
                val season = params["s"]?.takeIf { it.isNotEmpty() } ?: return null
                val number = params["e"]?.toFloatOrNull() ?: return null
                return EpisodeRef(id, season, number, params["a"])
            }
        }
    }

    companion object {
        private const val DEFAULT_DOMAIN = "https://animealpha.cc"
        private const val DEFAULT_HOST = "animealpha.cc"

        private const val PREF_DOMAIN_KEY = "pref_domain"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_LATEST_KEY = "pref_latest_feed"
        private const val PREF_LATEST_DEFAULT = "all"
        private const val PREF_TORRENT_KEY = "pref_show_torrent"
        private const val PREF_TORRENT_DEFAULT = false
        private const val PREF_COVERS_KEY = "pref_load_covers"
        private const val PREF_COVERS_DEFAULT = true

        private const val LATEST_ALL = "all"
        private const val LATEST_SUB = "sub"
        private const val LATEST_DUAL = "dual"

        private const val SERVER_STREAM = "Stream"
        private const val SERVER_DASH = "DASH"
        private const val SERVER_DIRECT = "Direct DL"
        private const val SERVER_TORRENT = "Torrent"
        private val SERVER_ORDER = listOf(SERVER_STREAM, SERVER_DIRECT, SERVER_DASH, SERVER_TORRENT)
        private const val PREF_SERVER_DEFAULT = SERVER_STREAM

        private const val MOVIE_SEASON = "movie"
        private const val MAX_SEASONS = 30
        private const val SEASON_BATCH = 3
        private const val SEASON_NUMBER_STEP = 1000f

        private const val RATE_LIMIT_PERMITS = 5
        private const val LATEST_PAGE_SIZE = 24
        private const val LATEST_TTL_MS = 2 * 60 * 1000L
        private const val SUGGESTION_LIMIT = 8
        private const val SUGGESTION_TTL_MS = 10 * 60 * 1000L
        private const val SUGGESTION_CACHE_SIZE = 96
        private const val SCHEDULE_TTL_MS = 30 * 60 * 1000L
        private const val COVER_CONCURRENCY = 3
        private const val COVER_TIMEOUT_MS = 7_000L
        private const val LIST_COVER_SIZE = "w500"
        private const val DETAIL_COVER_SIZE = "w780"
        private const val DETAIL_TTL_MS = 2 * 60 * 1000L
        private const val DETAIL_CACHE_SIZE = 32
        private const val LISTING_TTL_MS = 45 * 1000L
        private const val LISTING_CACHE_SIZE = 64

        private const val OPTIONAL_LISTING_MS = 6_000L
        private const val PRIMARY_HEAD_START_MS = 6_000L
        private const val BACKUP_CONCURRENCY = 2
        private const val GATE_CACHE_SIZE = 32
        private const val TOKEN_TTL_MS = 10 * 60 * 1000L
        private const val REDIRECT_TIMEOUT_MS = 8_000L
        private const val REDIRECT_TTL_MS = 5 * 60 * 1000L
        private const val REDIRECT_CACHE_SIZE = 64
        private const val MAX_ADCHECKS = 2
        private const val MAX_PREPARE_RELOADS = 8
        private const val PREPARE_DELAY_MS = 4_000L

        private const val TORRENT_PREFIX = "torrent:"
        private const val TORRENT_TIMEOUT_MS = 25_000L
        private const val TORRENT_ATTEMPTS = 2

        private const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

        private val PAGE_CACHE = CacheControl.Builder().maxAge(10, TimeUnit.MINUTES).build()
        private val FRESH_CACHE = CacheControl.Builder().maxAge(2, TimeUnit.MINUTES).build()

        private val STANDARD_RESOLUTIONS =
            linkedMapOf(
                1080 to "1920 x 1080",
                720 to "1280 x 720",
                480 to "854 x 480",
                360 to "640 x 360",
            )

        private val PUBLIC_TRACKERS =
            listOf(
                "udp://tracker.opentrackr.org:1337/announce",
                "udp://open.stealth.si:80/announce",
                "udp://tracker.torrent.eu.org:451/announce",
                "udp://exodus.desync.com:6969/announce",
                "udp://open.demonii.com:1337/announce",
            )

        /** Bigger read-ahead plus reconnects so playback survives a weak or flaky connection. */
        private val NETWORK_MPV_ARGS =
            listOf(
                "cache" to "yes",
                "demuxer-max-bytes" to "64MiB",
                "demuxer-readahead-secs" to "120",
                "network-timeout" to "30",
                "stream-lavf-o-append" to "reconnect=1",
            )

        private const val CARD_SELECTOR =
            "article, li, .anime-card, .spotlight-slide, .similar-anime-card, .mini-item, .anime-item, [class*=card]"
        private const val TITLE_SELECTOR = ".spotlight-title, .similar-anime-title, h1, h2, h3, h4, .title, .anime-title"
        private val IGNORED_TITLES = setOf("view more", "watch now", "watch", "details", "more info", "a-z list", "anime", "subscribe")

        private val ID_REGEX = Regex("^[A-Za-z0-9]{12}$")
        private val EPISODES_REGEX = Regex("""Episodes:?\s*(N/A|\d+)""", RegexOption.IGNORE_CASE)
        private val EPISODE_LABEL_REGEX = Regex("""EPISODE\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val LEADING_NUMBER_REGEX = Regex("""^\s*(\d+(?:\.\d+)?)""")
        private val AUDIO_LABEL_REGEX = Regex("""(dual audio|english sub|english dub|dual|sub|dub)\s*$""", RegexOption.IGNORE_CASE)
        private val PAGE_FILENAME_REGEX = Regex("""(?:encodedFilename|fileName|filename)\s*=\s*["']([^"']+\.(?:mkv|mp4))["']""")
        private val WATCH_REGEX = Regex("""(?:https?:\\?/\\?/[^\s"'<>]+)?\\?/watch\\?/[^\s"'<>\\]+""")
        private val DDL_TOKEN_REGEXES =
            listOf(
                Regex("""['"]X-DDL-Token['"]\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
                Regex("""ddl[_-]?token['"\s]*[=:]\s*['"]([A-Za-z0-9+/=_.\-]+)['"]""", RegexOption.IGNORE_CASE),
                Regex("""['"](A{4,}[A-Za-z0-9_\-]{10,})['"]"""),
            )

        private fun decode(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(value)

        private fun encodeComponent(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun seasonLabel(season: String): String = if (season.toIntOrNull() != null) "Season $season" else season.replaceFirstChar { it.uppercase() }

        private fun formatNumber(number: Float): String = if (number % 1f == 0f) number.toInt().toString() else number.toString()

        /** "DUAL AUDIO" / "English Sub" / "dub" → "Dual" / "Sub" / "Dub". */
        private fun audioTag(raw: String?): String? {
            val value = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                "dual" in value || "multi" in value -> "Dual"
                "dub" in value -> "Dub"
                "sub" in value -> "Sub"
                else -> value.replaceFirstChar { it.uppercase() }
            }
        }
    }
}
