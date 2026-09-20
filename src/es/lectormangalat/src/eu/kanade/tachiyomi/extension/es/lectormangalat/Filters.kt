package eu.kanade.tachiyomi.extension.es.lectormangalat

import eu.kanade.tachiyomi.source.model.Filter

open class SelectFilter(name: String, private val options: List<Pair<String, String>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val value get() = options[state].second
}

class TypeFilter :
    SelectFilter(
        "Tipo",
        listOf("Todos" to "", "Manga" to "Manga", "Cómic" to "Comics"),
    )

class StatusFilter :
    SelectFilter(
        "Estado",
        listOf("Todos" to "", "En emisión" to "En emisión", "Finalizado" to "Finalizado"),
    )

class GenreFilter :
    SelectFilter(
        "Género",
        listOf(
            "Todos" to "",
            "+18" to "+18",
            "Acción" to "Acción",
            "Adulto" to "Adulto",
            "Aventura" to "Aventura",
            "Boys Love" to "Boys Love",
            "Comedia" to "Comedia",
            "Drama" to "Drama",
            "Ecchi" to "Ecchi",
            "Fantasía" to "Fantasía",
            "Girls Love" to "Girls Love",
            "Harem" to "Harem",
            "Horror" to "Horror",
            "Manhwa +19" to "Manhwa +19",
            "Psicológico" to "Psicológico",
            "Reencarnación" to "Reencarnación",
            "Romance" to "Romance",
            "Sobrenatural" to "Sobrenatural",
            "Vida Escolar" to "Vida Escolar",
        ),
    )
