package eu.kanade.tachiyomi.extension.es.mhscans

import eu.kanade.tachiyomi.source.model.Filter

open class QueryFilter(name: String, private val options: Array<Pair<String, String>>, val param: String) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val value get() = options[state].second
}

class SortFilter :
    QueryFilter(
        "Ordenar por",
        arrayOf(
            "Más visto" to "views",
            "Más reciente" to "latest",
            "Actualizado" to "updated",
            "Mejor valorado" to "rating",
            "A-Z" to "title",
        ),
        "sort",
    )

class TypeFilter :
    QueryFilter(
        "Tipo",
        arrayOf("Todos" to "", "Manga" to "Manga", "Manhwa" to "Manhwa", "Manhua" to "Manhua", "Doujinshi" to "Doujinshi", "Otro" to "Other"),
        "type",
    )

class StatusFilter :
    QueryFilter(
        "Estado",
        arrayOf("Todos" to "", "En emisión" to "Ongoing", "Completado" to "Completed", "En hiato" to "Hiatus", "Cancelado" to "Cancelled"),
        "status",
    )

class GenreFilter :
    QueryFilter(
        "Género",
        arrayOf(
            "Todos",
            "Acción",
            "Aliado",
            "Artes Marciales",
            "Aventura",
            "Ciencia ficción",
            "Comedia",
            "Drama",
            "Fantasía",
            "Manhua",
            "Manhwa",
            "Meian",
            "Nuevo",
            "Recomendado",
            "Reencarnación",
            "Shounen",
            "Sistema",
        ).map { it to if (it == "Todos") "" else it }.toTypedArray(),
        "genre",
    )
