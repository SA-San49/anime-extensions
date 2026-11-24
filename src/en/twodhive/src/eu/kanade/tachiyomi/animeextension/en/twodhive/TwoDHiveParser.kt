package eu.kanade.tachiyomi.animeextension.en.twodhive

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Reads the pages the site renders.
 *
 * The catalog, the search results and the episodes are all rendered by the same Astro components,
 * so the markup is the contract here rather than an API. The parsers are written to survive a
 * redesign of that markup: no class name is used, and every field has more than one way of being
 * found.
 */
object TwoDHiveParser {

    /** One title of the catalog. */
    class Card(
        val id: Int,
        val title: String,
        val thumbnail: String?,
    )

    /** The titles of a page, and whether another page follows it. */
    class Catalog(
        val animes: List<SAnime>,
        val hasNextPage: Boolean,
    )

    /** One episode of an anime. */
    class Episode(
        val number: Int,
        val name: String?,
        val thumbnail: String?,
    )

    /**
     * The episodes of an anime.
     *
     * The page shows the first twenty-four episodes and paginates the rest on its own, so the
     * number of episodes is read from the data the page carries rather than from the links it
     * happens to render.
     */
    class EpisodePage(
        val total: Int,
        val episodes: List<Episode>,
    )

    /**
     * The titles of a catalog or search page.
     *
     * The cards are `<article>` elements, but a page that stops using them still lists every
     * title as a link, so the links are the fallback rather than the other way round.
     */
    fun parseCatalog(document: Document): Catalog {
        val articles = document.select("article")
        val blocks = articles.filter { it.selectFirst(ANIME_LINK_SELECTOR) != null }
            .takeIf { it.isNotEmpty() }
            ?: document.select(ANIME_LINK_SELECTOR)

        val animes = mutableListOf<SAnime>()
        val seen = mutableSetOf<Int>()

        for (block in blocks) {
            val card = parseCard(block) ?: continue
            if (!seen.add(card.id)) continue
            animes += card.toSAnime()
        }

        return Catalog(animes = animes, hasNextPage = document.hasNextPage())
    }

    /**
     * Whether the page leads to another one.
     *
     * The catalog paginates with a link to the next page, while the search results are all on
     * one page. Asking for a second page of the search would only produce the same results
     * again, so the link of the page itself is what decides.
     */
    private fun Document.hasNextPage(): Boolean =
        select("a").any { link ->
            link.text().trim().equals("next", ignoreCase = true) && link.attr("href").contains("page=")
        }

    private fun parseCard(block: Element): Card? {
        val link = when {
            block.tagName() == "a" -> block
            else -> block.selectFirst(ANIME_LINK_SELECTOR)
        } ?: return null

        val id = MAL_ID_REGEX.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return null

        val title = title(block, link) ?: return null

        return Card(
            id = id,
            title = title,
            thumbnail = thumbnail(block, link),
        )
    }

    /**
     * The title of a card.
     *
     * The image carries the title as its alternative text, which is the most reliable place for
     * it; a heading or a bold text is the next best, and the card's own text is the last resort —
     * that text also holds the format, the episode count and the score, which are not part of
     * the title and are taken out of it.
     */
    private fun title(block: Element, link: Element): String? {
        val candidates = listOfNotNull(
            block.selectFirst("h2, h3, h4, h5")?.text(),
            block.selectFirst("strong, b")?.text(),
            link.selectFirst("img")?.attr("alt"),
            block.selectFirst("img")?.attr("alt"),
            link.attr("title"),
        )
        val picked = candidates
            .map { it.trim() }
            .firstOrNull { it.length >= 2 && !it.startsWith("http") }
        if (picked != null) return picked

        return cleanTitle(link.text()).takeIf { it.length >= 2 }
    }

    /**
     * The title out of a card's whole text.
     *
     * The text of a card reads like `TV EP 26 Title ★ 9.1`, so the parts that are not the title
     * are taken out.
     */
    private fun cleanTitle(text: String): String {
        var cleaned = text
        cleaned = SCORE_REGEX.replace(cleaned, " ")
        cleaned = EPISODE_COUNT_REGEX.replace(cleaned, " ")
        cleaned = FORMAT_REGEX.replace(cleaned, " ")
        return cleaned.replace(WHITESPACE_REGEX, " ").trim()
    }

    /** The cover of a card, which the site loads lazily under a couple of attribute names. */
    private fun thumbnail(block: Element, link: Element): String? {
        val image = link.selectFirst("img") ?: block.selectFirst("img") ?: return null
        return IMAGE_ATTRIBUTES
            .firstNotNullOfOrNull { attribute -> image.attr(attribute).takeIf(String::isNotBlank) }
            ?.takeIf { it.startsWith("http") }
    }

    /**
     * The episodes of an anime.
     *
     * The episode browser of the page is handed the number of episodes and the details of each
     * of them, so both are read from there. The links the page renders are the fallback for a
     * page that does not carry that data.
     */
    fun parseEpisodes(document: Document): EpisodePage {
        val island = document.selectFirst(EPISODE_ISLAND_SELECTOR)
        val props = island?.let { readIslandProps(it) }

        val total = props?.get("totalEpisodes")?.asInt() ?: 0
        val meta = props?.get("episodeMeta")?.asList().orEmpty()
            .mapNotNull { it.asEpisode() }
            .associateBy { it.number }

        if (total > 0 || meta.isNotEmpty()) {
            return EpisodePage(total = maxOf(total, meta.size), episodes = meta.values.sortedBy { it.number })
        }

        val links = document.select(EPISODE_LINK_SELECTOR)
        val episodes = links.mapNotNull { link ->
            val number = EPISODE_NUMBER_REGEX.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            Episode(
                number = number,
                name = episodeName(link, number),
                thumbnail = episodeThumbnail(link),
            )
        }.distinctBy { it.number }.sortedBy { it.number }

        return EpisodePage(total = episodes.size, episodes = episodes)
    }

    /**
     * The name of an episode.
     *
     * An episode with a title shows it next to its number, while a plain episode shows only the
     * number — in which case there is no name to read.
     */
    private fun episodeName(link: Element, number: Int): String? {
        val text = link.text().trim()
        if (text.isEmpty()) return null

        val withoutBadge = EPISODE_BADGE_REGEX.replace(text, " ")
        val name = withoutBadge.replace(WHITESPACE_REGEX, " ").trim()

        return name.takeIf { it.isNotBlank() && it != number.toString() }
    }

    /** The thumbnail of an episode, which the site shows next to its title. */
    private fun episodeThumbnail(link: Element): String? {
        val image = link.selectFirst("img") ?: return null
        return IMAGE_ATTRIBUTES
            .firstNotNullOfOrNull { attribute -> image.attr(attribute).takeIf(String::isNotBlank) }
            ?.takeIf { it.startsWith("http") }
    }

    /** A card as the catalogue entry it becomes. */
    fun Card.toSAnime(): SAnime = SAnime.create().apply {
        url = "/anime?anime=$id"
        title = this@toSAnime.title
        thumbnail_url = thumbnail
    }

    /**
     * The data the episode browser of the page was handed.
     *
     * The page renders its islands as HTML attributes, and the data of an island is stored as
     * JSON in which every value is wrapped in a pair that names its type. The wrapper is peeled
     * off wherever it is found, so the shape of the data does not have to be known exactly.
     */
    private fun readIslandProps(island: Element): Map<String, JsonElement>? {
        val raw = island.attr("props").takeIf(String::isNotBlank) ?: return null
        val root = runCatching { twoDHiveJson.parseToJsonElement(raw) }.getOrNull() ?: return null
        val obj = root as? JsonObject ?: return null
        return obj.mapValues { (_, value) -> unwrap(value) }
    }

    /** Takes the type wrapper off a value, if it carries one. */
    private fun unwrap(element: JsonElement): JsonElement {
        val array = element as? JsonArray ?: return element
        if (array.size != 2) return element
        val type = (array[0] as? JsonPrimitive)?.intOrNull ?: return element
        if (type !in 0..2) return element
        return array[1]
    }

    /** The number a value holds, if it holds one. */
    private fun JsonElement?.asInt(): Int {
        val element = this ?: return 0
        return when (val unwrapped = unwrap(element)) {
            is JsonPrimitive -> unwrapped.intOrNull ?: 0
            else -> 0
        }
    }

    /** The text a value holds, if it holds one. */
    private fun JsonElement?.asText(): String? {
        val element = this ?: return null
        return when (val unwrapped = unwrap(element)) {
            is JsonPrimitive -> unwrapped.contentOrNull
            else -> null
        }
    }

    /** The list a value holds, if it holds one. */
    private fun JsonElement?.asList(): List<JsonElement> {
        val element = this ?: return emptyList()
        return when (val unwrapped = unwrap(element)) {
            is JsonArray -> unwrapped.map { unwrap(it) }
            else -> emptyList()
        }
    }

    /** An episode of the data the episode browser was handed. */
    private fun JsonElement.asEpisode(): Episode? {
        val obj = this as? JsonObject ?: return null
        val number = obj["number"].asInt()
        if (number <= 0) return null
        return Episode(
            number = number,
            name = obj["title"].asText()?.takeIf(String::isNotBlank),
            thumbnail = obj["thumbnail"].asText()?.takeIf(String::isNotBlank),
        )
    }

    /**
     * The link every title sits behind.
     *
     * The value is quoted because it carries both a question mark and an equal sign, which an
     * unquoted attribute value cannot hold.
     */
    private const val ANIME_LINK_SELECTOR = "a[href*=\"/anime?anime=\"]"

    private const val EPISODE_LINK_SELECTOR = "a[href*=\"ep_num=\"]"

    private const val EPISODE_ISLAND_SELECTOR = "astro-island[component-url*=EpisodeBrowser]"

    private val MAL_ID_REGEX = Regex("""anime=(\d+)""")

    private val EPISODE_NUMBER_REGEX = Regex("""ep_num=(\d+)""")

    /** The score of a card. */
    private val SCORE_REGEX = Regex("""[★☆]\s*[\d.]+""")

    /** The episode count of a card. */
    private val EPISODE_COUNT_REGEX = Regex("""EP\s*\d+""", RegexOption.IGNORE_CASE)

    /** The format of a card. */
    private val FORMAT_REGEX = Regex(
        """(?<![A-Za-z0-9])(TV_SHORT|TV|MOVIE|OVA|ONA|SPECIAL|MUSIC|MANGA)(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )

    /** The number an episode is badged with. */
    private val EPISODE_BADGE_REGEX = Regex("""(?<![A-Za-z0-9])EP\s*\d+""", RegexOption.IGNORE_CASE)

    private val WHITESPACE_REGEX = Regex("""\s+""")

    private val IMAGE_ATTRIBUTES = listOf("src", "data-src", "data-lazy-src", "data-original")
}
