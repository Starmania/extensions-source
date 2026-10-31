package eu.kanade.tachiyomi.extension.th.manhwabreakup

import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import eu.kanade.tachiyomi.source.model.Page
import keiyoushi.annotation.Source
import keiyoushi.lib.tilescramble.TileScrambleInterceptor
import keiyoushi.lib.tilescramble.tileScrambledImageUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class ManhwaBreakup : MadaraNoAjax() {
    override val chapterDateFormat = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.forLanguageTag("th"))

    override fun OkHttpClient.Builder.configureClient() = addInterceptor(TileScrambleInterceptor())

    override val pageListParseSelector = ".reading-content img, .reading-content div.displayImage + script:containsData(p,a,c,k,e,d)"

    override fun parsePages(document: Document) = document.select(pageListParseSelector).mapIndexed { idx, element ->
        val imageUrl = if (element.tagName() == "img") imageFromElement(element) else tileScrambledImageUrl(element.data())
        Page(idx, imageUrl = imageUrl)
    }
}
