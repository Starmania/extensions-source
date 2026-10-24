package eu.kanade.tachiyomi.extension.pt.onereader

import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.serialization.Serializable

@Serializable
internal class FilterData(
    val tags: List<String>,
)

internal open class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    fun selected(): String = options[state].second
}

internal class OrderFilter :
    SelectFilter(
        "Ordenar por",
        listOf(
            "A-Z" to "AZ",
            "Mais recentes" to "DATE",
            "Mais lidas" to "VIEWS",
            "Melhor avaliadas" to "RATING",
        ),
    )

internal class StatusFilter :
    SelectFilter(
        "Status",
        listOf(
            "Todos" to "",
            "Em lançamento" to "LANÇANDO",
            "Completo" to "COMPLETO",
            "Hiato" to "HIATO",
        ),
    )

internal class TypeFilter :
    SelectFilter(
        "Tipo",
        listOf(
            "Todos" to "",
            "Webtoon" to "WEBTOON",
            "Mangá" to "MANGÁ",
            "Manhua" to "MANHUA",
            "Manhwa" to "MANHWA",
            "Shoujo" to "SHOUJO",
        ),
    )

internal class TagFilter(tags: List<String>) :
    SelectFilter(
        "Gênero",
        listOf("Todos" to "") + tags.map { it to it },
    )
