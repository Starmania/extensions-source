package eu.kanade.tachiyomi.extension.tr.nemesisscans

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}

class GenreFilter :
    UriPartFilter(
        "Tür",
        arrayOf(
            "Tüm Türler" to "",
            "Action" to "Action",
            "Adventure" to "Adventure",
            "Comedy" to "Comedy",
            "Drama" to "Drama",
            "Fantasy" to "Fantasy",
            "Horror" to "Horror",
            "Mystery" to "Mystery",
            "Romance" to "Romance",
            "Sci-Fi" to "Sci-Fi",
            "Slice of Life" to "Slice of Life",
            "Sports" to "Sports",
            "Supernatural" to "Supernatural",
            "Thriller" to "Thriller",
        ),
    )

class StatusFilter :
    UriPartFilter(
        "Durum",
        arrayOf(
            "Tüm Durumlar" to "",
            "Devam Ediyor" to "false",
            "Tamamlandı" to "true",
        ),
    )

class SortFilter :
    UriPartFilter(
        "Sırala",
        arrayOf(
            "En Yeni" to "newest",
            "En Eski" to "oldest",
            "A-Z" to "title",
        ),
    )
