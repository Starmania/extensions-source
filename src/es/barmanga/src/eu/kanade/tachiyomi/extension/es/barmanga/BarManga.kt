package eu.kanade.tachiyomi.extension.es.barmanga

import eu.kanade.tachiyomi.multisrc.madara.Madara
import keiyoushi.annotation.Source
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class BarManga : Madara() {
    override val chapterDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

    override val mangaDetailsSelectorTitle = ".breadcrumb > li:last-child > a"
}
