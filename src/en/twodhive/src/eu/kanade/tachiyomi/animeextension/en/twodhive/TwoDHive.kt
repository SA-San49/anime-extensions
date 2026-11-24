package eu.kanade.tachiyomi.animeextension.en.twodhive

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

/**
 * 2Dhive (2dhive.com).
 *
 * The site is an Astro application that keeps every title under its MyAnimeList id, so a title is
 * addressed as `/anime?anime=<mal id>` everywhere: in the catalog, on the details page and on the
 * episode pages. That makes the id the one thing that has to survive between the screens, and
 * everything else — the metadata, the episode list, the stream servers — is derived from it.
 *
 * Three servers carry the video:
 *
 * - **MegaPlay** hands out an HLS stream whose CDN URL has to carry a signature.
 * - **BabaStream** answers in an encrypted protocol of its own and gates it behind a proof of work.
 * - **Wavy** serves a plain HLS master playlist for the episode that was opened.
 *
 * All three are addressed the same way, `<server>/<mal id>/<episode>/<sub|dub>`, which is why the
 * hoster list can be built without asking the site anything.
 */
class TwoDHive : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "2Dhive"

    override val lang = "en"

    override val supportsLatest = true

    override val baseUrl = "https://2dhive.com"

    /**
     * The site sits behind Cloudflare and its player pages are sometimes served a challenge
     * instead of the page, so the WebView solver is wired in. It only starts when a challenge is
     * actually served, and the cookies it earns are cached for the session.
     */
    override val client: OkHttpClient = network.client
        .newBuilder()
        .addInterceptor(ChallengeInterceptor(CloudflareInterceptor(network.client)))
        .rateLimit(RATE_LIMIT_PERMITS, 1.seconds) { it.host in SITE_HOSTS }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Accept-Language", "en-US,en;q=0.9")

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val prefServer by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
    private val prefAudio by preferences.delegate(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT)
    private val prefQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    /**
     * The scope the background work runs in.
     *
     * BabaStream asks for a proof of work that costs seconds of a phone's whole CPU, so it is
     * earned ahead of time rather than when somebody presses play.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val extractor by lazy {
        TwoDHiveExtractor(client, headers, playlistUtils, scope)
    }

    // =============================== Browse ===============================

    private fun pageHeaders(referer: String = "$baseUrl/") = headers.newBuilder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Referer", referer)
        .build()

    private fun apiHeaders() = headers.newBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Referer", "$baseUrl/")
        .build()

    override fun popularAnimeRequest(page: Int): Request = GET(
        catalogUrl(page = page, list = "top"),
        pageHeaders(),
    )

    override fun latestUpdatesRequest(page: Int): Request = GET(
        catalogUrl(page = page, list = "latest"),
        pageHeaders(),
    )

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())

        filters.forEach { filter ->
            when (filter) {
                is TwoDHiveFilters.FormatFilter -> filter.getValue()
                    ?.takeIf(String::isNotBlank)
                    ?.let { url.addQueryParameter("format", it) }
                is TwoDHiveFilters.ScoreFilter -> filter.getValue()
                    ?.takeIf(String::isNotBlank)
                    ?.let { url.addQueryParameter("score", it) }
                is TwoDHiveFilters.SortFilter -> filter.getValue()
                    ?.takeIf(String::isNotBlank)
                    ?.let { url.addQueryParameter("sort", it) }
                else -> {}
            }
        }

        return GET(url.build(), pageHeaders())
    }

    private fun catalogUrl(page: Int, list: String) = "$baseUrl/".toHttpUrl().newBuilder()
        .addQueryParameter("list", list)
        .apply { if (page > 1) addQueryParameter("page", page.toString()) }
        .build()

    override fun popularAnimeParse(response: Response): AnimesPage = parseCatalog(response)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseCatalog(response)

    override fun searchAnimeParse(response: Response): AnimesPage = parseCatalog(response)

    /**
     * Reads the catalog out of a rendered page.
     *
     * The site's own search box shows every match, while the JSON endpoint behind it stops at
     * nine, so the rendered list is the one worth reading.
     */
    private fun parseCatalog(response: Response): AnimesPage {
        val catalog = TwoDHiveParser.parseCatalog(response.useAsJsoup())
        return AnimesPage(catalog.animes, catalog.hasNextPage)
    }

    // =============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = TwoDHiveFilters.getFilterList()

    // ============================ Anime Details ===========================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(
        "$baseUrl/api/anime/summary?malId=${anime.malId()}",
        apiHeaders(),
    )

    override fun animeDetailsParse(response: Response): SAnime {
        val summary = response.parseAs<SummaryResponseDto>(twoDHiveJson).anime ?: throw Exception("No details found")
        return SAnime.create().apply {
            url = "/anime?anime=${summary.id}"
            title = summary.englishTitle?.takeIf(String::isNotBlank) ?: summary.title.orEmpty()
            thumbnail_url = summary.coverImageUrl?.takeIf(String::isNotBlank) ?: summary.imageUrl
            genre = summary.genres?.takeIf { it.isNotEmpty() }?.joinToString(", ")
            author = summary.studios?.takeIf { it.isNotEmpty() }?.joinToString(", ")
            status = summary.status.toAnimeStatus()
            description = buildString {
                summary.synopsis?.takeIf(String::isNotBlank)?.let { append(it).append("\n\n") }
                val facts = buildList {
                    summary.type?.takeIf(String::isNotBlank)?.let { add("Type: $it") }
                    summary.year?.takeIf { it > 0 }?.let { add("Year: $it") }
                    summary.episodes?.takeIf { it > 0 }?.let { add("Episodes: $it") }
                    summary.score?.let { add("Score: $it") }
                }
                if (facts.isNotEmpty()) {
                    append(facts.joinToString("\n"))
                    append("\n")
                }
                summary.trailerUrl?.takeIf(String::isNotBlank)?.let { append("Trailer: $it") }
            }.trim()
            initialized = true
        }
    }

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/anime?anime=${anime.malId()}"

    override val disableRelatedAnimesBySearch = true

    override suspend fun fetchRelatedAnimeList(anime: SAnime): List<SAnime> = emptyList()

    override fun relatedAnimeListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // =============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val malId = anime.malId()

        val document = runCatching {
            client.get("$baseUrl/anime?anime=$malId", pageHeaders("$baseUrl/")).useAsJsoup()
        }.getOrNull()

        val parsed = document?.let { TwoDHiveParser.parseEpisodes(it) }
            ?: TwoDHiveParser.EpisodePage(total = 0, episodes = emptyList())

        // The page only renders the first page of episodes, so the site's own count decides how
        // many there are in total.
        val count = maxOf(parsed.total, episodeCount(malId))
        if (count == 0) return emptyList()

        val byNumber = parsed.episodes.associateBy { it.number }

        return (1..count).map { number ->
            val episode = byNumber[number]
            SEpisode.create().apply {
                setUrlWithoutDomain("$baseUrl/episode?anime=$malId&ep_num=$number")
                episode_number = number.toFloat()
                name = episode?.name?.takeIf(String::isNotBlank) ?: "Episode $number"
                preview_url = episode?.thumbnail
            }
        }.reversed()
    }

    /**
     * The number of episodes the site's own API reports.
     *
     * The page's episode list is the primary source, but it is paginated for long-running series
     * and this covers the rest.
     */
    private suspend fun episodeCount(malId: Int): Int = runCatching {
        client.get("$baseUrl/api/anime/episodes?id=$malId", apiHeaders())
            .parseAs<EpisodesResponseDto>(twoDHiveJson)
            .episodes
    }.getOrNull() ?: 0

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + episode.url

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response) = throw UnsupportedOperationException()

    // ================================ Hosters ==============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val malId = episode.malId()
        val number = episode.episodeNumber()

        val hosters = buildList {
            TwoDHiveServers.entries.forEach { server ->
                TwoDHiveAudio.entries.forEach { audio ->
                    add(
                        Hoster(
                            hosterName = "${server.label} (${audio.label})",
                            internalData = "twodhive::$malId::$number::${server.id}::${audio.id}",
                        ),
                    )
                }
            }
        }

        if (hosters.isEmpty()) throw Exception("No servers found for this episode")

        // The proof of work BabaStream asks for is earned while the episode list is still on
        // screen, so that it is not paid for when somebody presses play.
        scope.launch {
            runCatching { extractor.warmUpBabaStream(malId, number, "sub", "$baseUrl/episode?anime=$malId&ep_num=$number") }
        }

        return hosters
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val server = prefServer
        val audio = prefAudio
        return sortedWith(
            compareByDescending<Hoster> { it.server() == server }
                .thenByDescending { it.audio() == audio }
                .thenBy { it.hosterName },
        )
    }

    // ================================ Videos ===============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.internalData.removePrefix("twodhive::").split("::")
        if (parts.size < 4) return emptyList()

        val malId = parts[0].toIntOrNull() ?: return emptyList()
        val number = parts[1].toIntOrNull() ?: return emptyList()
        val server = TwoDHiveServers.fromId(parts[2]) ?: return emptyList()
        val audio = TwoDHiveAudio.fromId(parts[3]) ?: TwoDHiveAudio.SUB

        val referer = "$baseUrl/episode?anime=$malId&ep_num=$number"

        val videos = when (server) {
            TwoDHiveServers.MEGAPLAY -> extractor.extractMegaPlay(malId, number, audio.id, referer)
            TwoDHiveServers.BABASTREAM -> extractor.extractBabaStream(malId, number, audio.id, referer)
            TwoDHiveServers.WAVY -> extractor.extractWavy(malId, number, audio.id, referer)
        }

        return videos.sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = prefQuality.toIntOrNull() ?: 0
        return sortedWith(
            compareByDescending<Video> { it.resolution == quality }
                .thenByDescending { it.resolution ?: 0 }
                .thenBy { it.videoTitle },
        )
    }

    // =============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred server",
            summary = "%s",
            entries = TwoDHiveServers.entries.map { it.label },
            entryValues = TwoDHiveServers.entries.map { it.id },
        )
        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            default = PREF_AUDIO_DEFAULT,
            title = "Preferred audio",
            summary = "%s",
            entries = TwoDHiveAudio.entries.map { it.label },
            entryValues = TwoDHiveAudio.entries.map { it.id },
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
    }

    // ================================ Utils ================================

    private fun SAnime.malId(): Int = MAL_ID_REGEX.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun SEpisode.malId(): Int = MAL_ID_REGEX.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun SEpisode.episodeNumber(): Int = EPISODE_NUMBER_REGEX.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun Hoster.server(): String = internalData.split("::").getOrNull(3).orEmpty()

    private fun Hoster.audio(): String = internalData.split("::").getOrNull(4).orEmpty()

    private fun String?.toAnimeStatus(): Int = when {
        this == null -> SAnime.UNKNOWN
        contains("finished", ignoreCase = true) -> SAnime.COMPLETED
        contains("currently airing", ignoreCase = true) -> SAnime.ONGOING
        contains("airing", ignoreCase = true) -> SAnime.ONGOING
        contains("hiatus", ignoreCase = true) -> SAnime.ON_HIATUS
        contains("cancelled", ignoreCase = true) -> SAnime.CANCELLED
        else -> SAnime.UNKNOWN
    }

    companion object {
        private const val RATE_LIMIT_PERMITS = 4

        private val SITE_HOSTS = setOf(
            "2dhive.com",
            "megaplay.buzz",
            "babastream.top",
            "wavy.babastream.top",
        )

        private val MAL_ID_REGEX = Regex("""anime=(\d+)""")
        private val EPISODE_NUMBER_REGEX = Regex("""ep_num=(\d+)""")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "megaplay"
        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val PREF_AUDIO_DEFAULT = "sub"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}

/** The servers the episode page offers, in the order it lists them. */
enum class TwoDHiveServers(val id: String, val label: String) {
    MEGAPLAY("megaplay", "MegaPlay"),
    BABASTREAM("babastream", "BabaStream"),
    WAVY("wavy", "Wavy"),
    ;

    companion object {
        fun fromId(id: String): TwoDHiveServers? = entries.firstOrNull { it.id == id }
    }
}

/** The two audio tracks the player offers. */
enum class TwoDHiveAudio(val id: String, val label: String) {
    SUB("sub", "Sub"),
    DUB("dub", "Dub"),
    ;

    companion object {
        fun fromId(id: String): TwoDHiveAudio? = entries.firstOrNull { it.id == id }
    }
}
