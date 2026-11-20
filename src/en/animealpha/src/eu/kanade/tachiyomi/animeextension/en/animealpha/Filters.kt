package eu.kanade.tachiyomi.animeextension.en.animealpha

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

/** What the filter sheet asks for, in the words of the site's URLs. */
internal class FilterParams(
    val list: String = "",
    val sort: String = "latest",
    val genres: List<String> = emptyList(),
    val yearFrom: String = "",
    val yearTo: String = "",
    val letter: String = "",
)

private val LIST_OPTIONS =
    listOf(
        "A–Z catalog (all filters apply)" to "",
        "Airing now · Sub" to "airing/sub",
        "Airing now · Dual audio" to "airing/dual",
    )

private val SORT_OPTIONS =
    listOf(
        "Latest added" to "latest",
        "A–Z" to "a-z",
        "Z–A" to "z-a",
        "Episode count" to "episodes",
    )

/** The site filters by the lower-case genre name (`slice of life`, `sci-fi`, …). */
private val GENRE_NAMES =
    listOf(
        "Action",
        "Adventure",
        "Comedy",
        "Drama",
        "Ecchi",
        "Fantasy",
        "Horror",
        "Mahou Shoujo",
        "Mecha",
        "Music",
        "Mystery",
        "Psychological",
        "Romance",
        "Sci-Fi",
        "Slice of Life",
        "Sports",
        "Supernatural",
        "Thriller",
    )

private val YEAR_OPTIONS = listOf("Any") + (2026 downTo 1991).map { it.toString() }

private val LETTER_OPTIONS = listOf("All") + ('A'..'Z').map { it.toString() } + "0-9"

internal class ListFilter :
    AnimeFilter.Select<String>(
        "Browse",
        LIST_OPTIONS.map { it.first }.toTypedArray(),
    )

internal class SortFilter :
    AnimeFilter.Select<String>(
        "Sort by",
        SORT_OPTIONS.map { it.first }.toTypedArray(),
    )

internal class GenreCheckBox(
    name: String,
    val value: String,
) : AnimeFilter.CheckBox(name)

internal class GenreFilter :
    AnimeFilter.Group<GenreCheckBox>(
        "Genres (any of)",
        GENRE_NAMES.map { GenreCheckBox(it, it.lowercase()) },
    )

internal class YearFromFilter :
    AnimeFilter.Select<String>(
        "Released from",
        YEAR_OPTIONS.toTypedArray(),
    )

internal class YearToFilter :
    AnimeFilter.Select<String>(
        "Released until",
        YEAR_OPTIONS.toTypedArray(),
    )

internal class LetterFilter :
    AnimeFilter.Select<String>(
        "Starts with",
        LETTER_OPTIONS.toTypedArray(),
    )

internal fun buildFilterList(): AnimeFilterList = AnimeFilterList(
    AnimeFilter.Header("Text search ignores the filters below"),
    ListFilter(),
    AnimeFilter.Header("Catalog only"),
    SortFilter(),
    GenreFilter(),
    YearFromFilter(),
    YearToFilter(),
    LetterFilter(),
)

internal fun parseFilters(filters: AnimeFilterList): FilterParams {
    var list = ""
    var sort = "latest"
    var genres = emptyList<String>()
    var yearFrom = ""
    var yearTo = ""
    var letter = ""
    filters.forEach { filter ->
        when (filter) {
            is ListFilter -> {
                list = LIST_OPTIONS.getOrNull(filter.state)?.second.orEmpty()
            }

            is SortFilter -> {
                sort = SORT_OPTIONS.getOrNull(filter.state)?.second ?: "latest"
            }

            is GenreFilter -> {
                genres = filter.state.filter { it.state }.map { it.value }
            }

            is YearFromFilter -> {
                yearFrom = YEAR_OPTIONS.getOrNull(filter.state)?.takeIf { it != "Any" }.orEmpty()
            }

            is YearToFilter -> {
                yearTo = YEAR_OPTIONS.getOrNull(filter.state)?.takeIf { it != "Any" }.orEmpty()
            }

            is LetterFilter -> {
                letter = LETTER_OPTIONS.getOrNull(filter.state)?.takeIf { it != "All" }.orEmpty()
            }

            else -> {}
        }
    }
    return FilterParams(list, sort, genres, yearFrom, yearTo, letter)
}
