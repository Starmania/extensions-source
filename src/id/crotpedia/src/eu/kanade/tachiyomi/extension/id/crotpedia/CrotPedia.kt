package eu.kanade.tachiyomi.extension.id.crotpedia

import eu.kanade.tachiyomi.multisrc.zmanga.ZManga
import keiyoushi.annotation.Source
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class CrotPedia : ZManga() {
    override val dateFormatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale("id"))

    override val hasProjectPage = false

    override val typeFilterValues = arrayOf(
        Pair("All", ""),
        Pair("Manga", "Manga"),
        Pair("Image-set", "Image-set"),
        Pair("Manhwa", "Manhwa"),
        Pair("One-shot", "One-shot"),
        Pair("Doujinshi", "Doujinshi"),
    )
}
