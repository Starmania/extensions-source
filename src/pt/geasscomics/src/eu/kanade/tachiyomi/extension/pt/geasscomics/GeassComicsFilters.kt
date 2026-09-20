package eu.kanade.tachiyomi.extension.pt.geasscomics

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

fun getFilters(
    genres: List<Pair<String, String>>,
    tags: List<Pair<String, String>>,
): FilterList = FilterList(
    buildList {
        add(SortFilter())
        add(StatusFilter())
        if (genres.isNotEmpty() || tags.isNotEmpty()) {
            add(Filter.Separator())
        }
        if (genres.isNotEmpty()) add(GenreFilter(genres))
        if (tags.isNotEmpty()) add(TagFilter(tags))
    },
)

class SortFilter :
    Filter.Select<String>(
        "Ordenar por",
        SORT_OPTIONS.map { it.first }.toTypedArray(),
    ) {
    val params: Map<String, String> get() = SORT_OPTIONS[state].second

    companion object {
        // The API silently ignores any other sortBy value and falls back to most recently updated.
        private val SORT_OPTIONS = listOf<Pair<String, Map<String, String>>>(
            "Mais Recentes" to emptyMap(),
            "Melhor Avaliados" to mapOf("sortBy" to "rating"),
            "Título (A-Z)" to mapOf("sortBy" to "title", "sortDir" to "asc"),
        )
    }
}

class StatusFilter :
    Filter.Select<String>(
        "Status",
        STATUS_OPTIONS.map { it.first }.toTypedArray(),
    ) {
    val selected: String? get() = STATUS_OPTIONS[state].second

    companion object {
        private val STATUS_OPTIONS = listOf(
            "Todos" to null,
            "Em Andamento" to "ongoing",
            "Completo" to "completed",
            "Hiato" to "hiatus",
            "Cancelado" to "cancelled",
        )
    }
}

class GenreFilter(genres: List<Pair<String, String>>) :
    Filter.Group<GenreCheckBox>(
        "Gêneros",
        genres.map { GenreCheckBox(it.first, it.second) },
    )

class GenreCheckBox(name: String, val id: String) : Filter.CheckBox(name, false)

class TagFilter(tags: List<Pair<String, String>>) :
    Filter.Group<TagCheckBox>(
        "Tags",
        tags.map { TagCheckBox(it.first, it.second) },
    )

class TagCheckBox(name: String, val id: String) : Filter.CheckBox(name, false)
