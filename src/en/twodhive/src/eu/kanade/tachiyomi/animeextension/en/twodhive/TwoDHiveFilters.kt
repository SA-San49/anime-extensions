package eu.kanade.tachiyomi.animeextension.en.twodhive

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

/**
 * The filter sheet the site offers above its catalog.
 *
 * Every filter maps to one query parameter of the catalog URL, so applying them is a matter of
 * putting the right values on the request. A filter left at its default contributes nothing, which
 * keeps the URL the way the site would produce it itself.
 */
object TwoDHiveFilters {

    /**
     * The formats the catalog can be narrowed down to.
     *
     * The site hides the formats that would return nothing for the chosen list, so a list that
     * has no OVAs does not offer them.
     */
    class FormatFilter : AnimeFilter.Select<String>("Format", FORMAT_ENTRIES) {
        /** The format to ask for, or `null` for the default. */
        fun getValue(): String? = FORMAT_VALUES[state]

        companion object {
            private val FORMAT_ENTRIES = arrayOf("Any", "Movie", "OVA", "ONA", "Special", "TV")
            private val FORMAT_VALUES = arrayOf<String?>(null, "Movie", "OVA", "ONA", "Special", "TV")
        }
    }

    /** The lowest score a title may have to still be listed. */
    class ScoreFilter : AnimeFilter.Select<String>("Minimum score", SCORE_ENTRIES) {
        /** The lowest score to ask for, or `null` for the default. */
        fun getValue(): String? = SCORE_VALUES[state]

        companion object {
            private val SCORE_ENTRIES = arrayOf("Any", "9+", "8+", "7+", "6+")
            private val SCORE_VALUES = arrayOf<String?>(null, "9", "8", "7", "6")
        }
    }

    /** The order the results are listed in. */
    class SortFilter : AnimeFilter.Select<String>("Sort results", SORT_ENTRIES) {
        /** The order to ask for, or `null` for the default. */
        fun getValue(): String? = SORT_VALUES[state]

        companion object {
            private val SORT_ENTRIES = arrayOf("Default", "Highest score", "Title A-Z", "Newest year")
            private val SORT_VALUES = arrayOf<String?>(null, "score", "title", "year")
        }
    }

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        FormatFilter(),
        ScoreFilter(),
        SortFilter(),
    )
}
