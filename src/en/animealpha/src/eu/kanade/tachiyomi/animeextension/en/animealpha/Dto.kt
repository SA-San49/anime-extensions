package eu.kanade.tachiyomi.animeextension.en.animealpha

import kotlinx.serialization.Serializable
import java.io.IOException

/** Answer of `/search_suggestions?q=`: the site's own live-search API (at most eight titles). */
@Serializable
internal class SuggestionsDto(
    val suggestions: List<SuggestionDto> = emptyList(),
)

@Serializable
internal class SuggestionDto(
    val name: String = "",
    val url: String = "",
    val image: String? = null,
    val genres: List<String> = emptyList(),
)

/** Everything a hoster needs to find its episode again when it is picked (no network call is made to build it). */
@Serializable
internal data class HosterData(
    val id: String,
    val season: String,
    val number: Float,
    val resolution: Int,
    val audio: String? = null,
)

/** A download page that cannot be used; [tryNext] says whether another quality is worth a try. */
internal class DownloadPageException(
    message: String,
    val tryNext: Boolean = false,
) : IOException(message)

/** One file of a season listing: `/download/<id>/<season>/<resolution>/<uuid>?token=…`. */
internal class EpisodeEntry(
    val season: String,
    val number: Float,
    val audio: String?,
    val href: String,
    val ddl: String?,
)

/** The playable links the download page / `/get_ddl/` API hand out for one file. */
internal class DdlLinks(
    val stream: String?,
    val download: String?,
    val watch: String?,
    val dash: String?,
    val torrent: String?,
    val fileName: String?,
    val fileSize: String?,
    val audioLanguages: List<String>,
    val subtitles: List<Pair<String, String>>,
)

/** A title taken from one of the lightweight feeds (RSS / home grid). */
internal class FeedItem(
    val path: String,
    val title: String,
    val time: Long = 0L,
)
