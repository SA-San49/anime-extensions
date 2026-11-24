package eu.kanade.tachiyomi.animeextension.en.twodhive

import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * Resolves the three servers the 2Dhive episode page offers.
 *
 * - **MegaPlay** is a plain HLS host: its player page names a file, the file's sources are handed
 *   over encrypted and the resulting CDN URL has to carry a signature.
 * - **BabaStream** is an embed that speaks its own encrypted protocol and gates it behind a
 *   proof of work.
 * - **Wavy** serves one HLS master playlist for the episode that was opened last.
 */
class TwoDHiveExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val playlistUtils: PlaylistUtils,
    scope: CoroutineScope,
) {

    private val babaStream = TwoDHiveBabaStream(client, headers, scope)

    // ============================== MegaPlay =============================

    /**
     * Resolves the MegaPlay player of one episode.
     *
     * @param malId the MyAnimeList id of the anime, which is what the player's URL carries.
     * @param epNum the episode number.
     * @param audio `sub` or `dub`.
     * @param referer the 2Dhive episode page the player is embedded in.
     */
    suspend fun extractMegaPlay(malId: Int, epNum: Int, audio: String, referer: String): List<Video> {
        val playerUrl = "$MEGAPLAY_BASE/stream/mal/$malId/$epNum/$audio"

        val html = runCatching {
            client.get(playerUrl, playerHeaders(referer), CacheControl.FORCE_NETWORK).bodyString()
        }.getOrNull() ?: return emptyList()

        val fileId = FILE_ID_REGEX.find(html)?.groupValues?.get(1)
            ?: FILE_REAL_ID_REGEX.find(html)?.groupValues?.get(1)
            ?: FILE_TITLE_REGEX.find(html)?.groupValues?.get(1)
            ?: return emptyList()

        val sources = fetchMegaPlaySources(fileId, audio, playerUrl) ?: return emptyList()

        val file = sources.fileUrl()
            ?: sources.enc
                ?.let { enc ->
                    TwoDHiveCrypto.decryptMegaPlay(enc)
                        ?: refreshMegaPlaySeeds()?.let { TwoDHiveCrypto.decryptMegaPlay(enc) }
                }
                ?.let { TwoDHiveCrypto.megaPlayFile(it) }
            ?: return emptyList()

        val origin = file.origin()
        val playlistUrl = TwoDHiveCrypto.signMegaPlayUrl(migrate(file, origin))

        val subtitles = sources.tracks
            .orEmpty()
            .filter { it.kind.isNullOrBlank() || it.kind.equals("captions", ignoreCase = true) }
            .mapNotNull { track ->
                val url = track.file?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                Track(migrate(url, origin), track.label?.takeIf(String::isNotBlank) ?: "Subtitle")
            }

        val videoHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Referer", "$MEGAPLAY_BASE/")
            .set("Origin", MEGAPLAY_BASE)
            .build()

        val label = "MegaPlay (${audioTag(audio)})"

        val videos = runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = playlistUrl,
                referer = "$MEGAPLAY_BASE/",
                masterHeaders = videoHeaders,
                videoHeaders = videoHeaders,
                subtitleList = subtitles,
                videoNameGen = { quality -> "$label - $quality" },
            )
        }.getOrElse {
            listOf(
                Video(
                    videoUrl = playlistUrl,
                    videoTitle = "$label - Auto",
                    headers = videoHeaders,
                    subtitleTracks = subtitles,
                    initialized = true,
                ),
            )
        }

        // Every variant of the master playlist is a CDN URL of its own and carries its own
        // signature, so each one is signed on its own.
        val signed = videos.map { video -> video.copy(videoUrl = TwoDHiveCrypto.signMegaPlayUrl(video.videoUrl)) }

        val timestamps = buildList {
            sources.intro?.takeIf { (it.start ?: 0) != 0 || (it.end ?: 0) != 0 }?.let {
                add(TimeStamp((it.start ?: 0).toDouble(), (it.end ?: 0).toDouble(), name = "Intro", type = ChapterType.Opening))
            }
            sources.outro?.takeIf { (it.start ?: 0) != 0 || (it.end ?: 0) != 0 }?.let {
                add(TimeStamp((it.start ?: 0).toDouble(), (it.end ?: 0).toDouble(), name = "Outro", type = ChapterType.Ending))
            }
        }

        return if (timestamps.isEmpty()) signed else signed.map { it.copy(timestamps = timestamps) }
    }

    /**
     * Reads the sources of a MegaPlay file.
     *
     * The endpoint was renamed once, so both spellings are tried and the first that answers wins.
     */
    private suspend fun fetchMegaPlaySources(
        fileId: String,
        audio: String,
        playerUrl: String,
    ): MegaPlaySourcesDto? {
        val apiHeaders = headers.newBuilder()
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .set("Referer", playerUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        val urls = listOf(
            "$MEGAPLAY_BASE/stream/getSourcesNew?id=$fileId&type=$audio",
            "$MEGAPLAY_BASE/stream/getSources?id=$fileId&type=$audio",
        )

        for (url in urls) {
            val dto = runCatching {
                client.get(url, apiHeaders, CacheControl.FORCE_NETWORK)
                    .parseAs<MegaPlaySourcesDto>(twoDHiveJson)
            }.getOrNull() ?: continue
            if (dto.sources != null || !dto.enc.isNullOrBlank()) return dto
        }
        return null
    }

    /**
     * Reads the playable URL out of the `sources` field.
     *
     * It comes in three shapes depending on how old the file is: a bare string, an object with a
     * `file` field, or a list of such objects.
     */
    private fun MegaPlaySourcesDto.fileUrl(): String? {
        fun fromObject(value: JsonObject): String? = value["file"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: value["url"]?.let { (it as? JsonPrimitive)?.contentOrNull }

        return when (val sources = sources) {
            is JsonPrimitive -> sources.contentOrNull?.takeIf { it.startsWith("http") }
            is JsonObject -> fromObject(sources)
            is JsonArray -> sources.firstNotNullOfOrNull { element ->
                (element as? JsonObject)?.let { fromObject(it) }
            }
            else -> null
        }
    }

    /**
     * Reads the seeds MegaPlay's client script publishes.
     *
     * They change when the site is redeployed, and a file encrypted with the old ones cannot be
     * read, so they are refreshed whenever a payload refuses to decrypt.
     */
    private suspend fun refreshMegaPlaySeeds(): Boolean {
        val script = runCatching {
            client.get("$MEGAPLAY_BASE/lib/newclient.min.js", headers, CacheControl.FORCE_NETWORK)
                .bodyString()
        }.getOrNull() ?: return false

        val seeds = TwoDHiveCrypto.parseMegaPlaySeeds(script) ?: return false
        TwoDHiveCrypto.updateMegaPlaySeeds(seeds.first, seeds.second)
        return true
    }

    /**
     * Moves a URL off the CDN the site used to serve from.
     *
     * The old host is gone and its files live on the host the media itself is served from, so a
     * subtitle track that still points at the old one is moved over.
     */
    private fun migrate(url: String, origin: String?): String {
        if (!url.contains(LEGACY_CDN)) return url
        return url.replace(LEGACY_CDN, origin ?: LEGACY_CDN_FALLBACK)
    }

    private fun String.origin(): String? = ORIGIN_REGEX.find(this)?.groupValues?.get(1)

    private fun playerHeaders(referer: String): Headers = headers.newBuilder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Referer", referer)
        .build()

    // ============================= BabaStream =============================

    /**
     * Resolves the BabaStream embed of one episode.
     *
     * The embed does not publish a URL: it publishes a session, and the session's API answers with
     * the media once the client has spoken its protocol.
     */
    suspend fun extractBabaStream(malId: Int, epNum: Int, audio: String, referer: String): List<Video> {
        val embedUrl = "$BABASTREAM_BASE/embed/$malId/$epNum/$audio"

        val html = runCatching {
            client.get(
                embedUrl,
                headers.newBuilder()
                    .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .set("Referer", referer)
                    .build(),
                CacheControl.FORCE_NETWORK,
            ).bodyString()
        }.getOrNull() ?: return emptyList()

        val payload = babaStream.resolve(html, embedUrl) ?: return emptyList()

        val streamUrl = payload.url?.takeIf(String::isNotBlank) ?: return emptyList()
        val label = "BabaStream (${audioTag(audio)})"

        val videoHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Referer", embedUrl)
            .set("Origin", BABASTREAM_BASE)
            .build()

        val subtitles = payload.tracks.map { track ->
            Track(track.url, track.label)
        }

        // The type of the media is decided by the URL and not by the label the embed answers
        // with: a playlist served as "direct" is still a playlist, and handing a manifest to a
        // player as a file would fail to decode.
        return if (HLS_URL_REGEX.containsMatchIn(streamUrl)) {
            runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = streamUrl,
                    referer = embedUrl,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    subtitleList = subtitles,
                    videoNameGen = { quality -> "$label - $quality" },
                )
            }.getOrElse {
                listOf(
                    Video(
                        videoUrl = streamUrl,
                        videoTitle = "$label - Auto",
                        headers = videoHeaders,
                        subtitleTracks = subtitles,
                        initialized = true,
                    ),
                )
            }
        } else {
            listOf(
                Video(
                    videoUrl = streamUrl,
                    videoTitle = "$label - Direct",
                    headers = videoHeaders,
                    subtitleTracks = subtitles,
                    initialized = true,
                ),
            )
        }
    }

    /**
     * Starts earning the proof of work BabaStream asks for.
     *
     * The work is seconds of a phone's whole CPU, so it is paid for while the episode list is
     * still on screen rather than when somebody presses play.
     */
    suspend fun warmUpBabaStream(malId: Int, epNum: Int, audio: String, referer: String) {
        val embedUrl = "$BABASTREAM_BASE/embed/$malId/$epNum/$audio"
        val html = runCatching {
            client.get(
                embedUrl,
                headers.newBuilder()
                    .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .set("Referer", referer)
                    .build(),
                CacheControl.FORCE_NETWORK,
            ).bodyString()
        }.getOrNull() ?: return

        babaStream.warmUp(babaStream.parseSession(html)?.capBase)
    }

    // =============================== Wavy ================================

    /**
     * Resolves the Wavy player of one episode.
     *
     * The player page itself carries no stream: the server hands out a master playlist for the
     * episode that was opened last, so the page is visited first and the playlist is asked for
     * afterwards. The playlist's variants are handed out as paths of the same host, and they are
     * only served with the episode page as their referer.
     */
    suspend fun extractWavy(malId: Int, epNum: Int, audio: String, referer: String): List<Video> {
        val playerUrl = "$WAVY_BASE/$malId/$epNum/$audio/"

        val pageHeaders = headers.newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .set("Referer", referer)
            .build()

        // Visiting the player is what tells the server which episode the playlist should be for.
        runCatching {
            client.get(playerUrl, pageHeaders, CacheControl.FORCE_NETWORK).bodyString()
        }

        val masterUrl = "$WAVY_BASE/stream.m3u8"

        val videoHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Referer", playerUrl)
            .set("Origin", WAVY_BASE)
            .build()

        val label = "Wavy (${audioTag(audio)})"

        return runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                referer = playerUrl,
                masterHeaders = videoHeaders,
                videoHeaders = videoHeaders,
                videoNameGen = { quality -> "$label - $quality" },
            )
        }.getOrElse {
            Log.w(TAG, "Wavy playlist could not be read: ${it.message}")
            emptyList()
        }
    }

    private fun audioTag(audio: String): String = if (audio.equals("dub", ignoreCase = true)) "Dub" else "Sub"

    companion object {
        private const val TAG = "TwoDHiveExtractor"

        const val MEGAPLAY_BASE = "https://megaplay.buzz"
        const val BABASTREAM_BASE = "https://babastream.top"
        const val WAVY_BASE = "https://wavy.babastream.top"

        /** The CDN the site used to serve from and that its files have moved off. */
        private const val LEGACY_CDN = "https://cdn.imgnex.top/anime"
        private const val LEGACY_CDN_FALLBACK = "https://megap.norami.top"

        private val FILE_ID_REGEX = Regex("""data-id=["'](\d+)["']""", RegexOption.IGNORE_CASE)
        private val FILE_REAL_ID_REGEX = Regex("""data-realid=["'](\d+)["']""", RegexOption.IGNORE_CASE)
        private val FILE_TITLE_REGEX = Regex("""<title>\s*File\s+(\d+)""", RegexOption.IGNORE_CASE)
        private val ORIGIN_REGEX = Regex("""https?://[^/]+""")
        private val HLS_URL_REGEX = Regex("""\.m3u8(?:$|[?&])""", RegexOption.IGNORE_CASE)
    }
}
