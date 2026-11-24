package eu.kanade.tachiyomi.animeextension.en.twodhive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The JSON reader the module uses.
 *
 * The site's answers carry fields that come and go with its deployments, so unknown keys are
 * ignored rather than failing the whole response.
 */
val twoDHiveJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * Response of `GET /api/search?q=`.
 *
 * The endpoint answers with at most nine matches, which is all the site's own search box asks
 * for; `englishTitle` is empty for titles that have no English name.
 */
@Serializable
class SearchResponseDto(
    val results: List<SearchAnimeDto>? = null,
)

@Serializable
class SearchAnimeDto(
    val id: Int? = null,
    val title: String? = null,
    val englishTitle: String? = null,
    val imageUrl: String? = null,
    val coverImageUrl: String? = null,
    val smallImageUrl: String? = null,
    val score: Double? = null,
    val type: String? = null,
    val episodes: Int? = null,
    val status: String? = null,
    val year: Int? = null,
)

/**
 * Response of `GET /api/anime/summary?malId=`.
 *
 * Everything the details screen needs comes from here, so the anime page itself never has to be
 * downloaded for it.
 */
@Serializable
class SummaryResponseDto(
    val anime: AnimeSummaryDto? = null,
)

@Serializable
class AnimeSummaryDto(
    val id: Int? = null,
    val title: String? = null,
    val englishTitle: String? = null,
    val imageUrl: String? = null,
    val coverImageUrl: String? = null,
    val episodes: Int? = null,
    val type: String? = null,
    val score: Double? = null,
    val status: String? = null,
    val synopsis: String? = null,
    val genres: List<String>? = null,
    val year: Int? = null,
    val studios: List<String>? = null,
    val trailerUrl: String? = null,
)

/**
 * Response of `GET /api/anime/episodes?id=`.
 */
@Serializable
class EpisodesResponseDto(
    val episodes: Int? = null,
)

/**
 * Response of `GET /stream/getSources?id=` on MegaPlay.
 *
 * The playable URL is usually handed over encrypted in [enc]; [sources] is the plain form and
 * comes either as a bare string, as an object with a `file` field or as a list of such objects,
 * depending on how old the file is.
 */
@Serializable
class MegaPlaySourcesDto(
    val sources: JsonElement? = null,
    val tracks: List<MegaPlayTrackDto>? = null,
    val enc: String? = null,
    val intro: SkipRangeDto? = null,
    val outro: SkipRangeDto? = null,
    val t: Int? = null,
    val server: Int? = null,
    val image: String? = null,
)

@Serializable
class MegaPlayTrackDto(
    val file: String? = null,
    val label: String? = null,
    val kind: String? = null,
    val default: Boolean? = null,
)

@Serializable
class SkipRangeDto(
    val start: Int? = null,
    val end: Int? = null,
)

/**
 * Envelope every BabaStream API answer arrives in: `{"s": …, "d": "<encrypted>"}`.
 */
@Serializable
class BabaEnvelopeDto(
    val s: String? = null,
    val d: String? = null,
)

@Serializable
class BabaRequestDto(
    val s: String,
    val d: String,
)

/**
 * The body a Cap proof of work is redeemed with.
 *
 * The counted challenge form answers with a list of numbers, the listed form with a list of
 * objects, so the solutions are kept as plain JSON elements.
 */
@Serializable
class CapRedeemRequestDto(
    val token: String,
    val solutions: List<JsonElement>,
)
