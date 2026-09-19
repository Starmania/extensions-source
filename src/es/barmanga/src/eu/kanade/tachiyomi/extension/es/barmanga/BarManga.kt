package eu.kanade.tachiyomi.extension.es.barmanga

import eu.kanade.tachiyomi.multisrc.madara.Madara
import keiyoushi.annotation.Source
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class BarManga : Madara() {
    override val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)

    // The /manga/ archive renders placeholder cards (empty title, every link pointing at the home
    // page); only the ajax endpoint returns the real listing.
    override val useLoadMoreRequest = LoadMoreStrategy.Always

    override val mangaDetailsSelectorTitle = ".breadcrumb > li:last-child > a"
}
