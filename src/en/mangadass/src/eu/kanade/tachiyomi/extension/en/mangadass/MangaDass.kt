package eu.kanade.tachiyomi.extension.en.mangadass

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class MangaDass : Madara() {
    override val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)
    override val client = super.client.newBuilder()
        .rateLimit(3)
        .build()

    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val filterNonMangaItems = false

    // The site paginates as /manga/2, not /manga/page/2/ (404)
    override fun searchPage(page: Int) = if (page == 1) "" else page.toString()

    override fun popularMangaNextPageSelector() = "li.next:not(.disabled)"

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/$mangaSubString/${searchPage(page)}?m_orderby=trending", headers)

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        title = element.selectFirst("h3")!!.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
        setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/$mangaSubString/${searchPage(page)}?m_orderby=latest", headers)

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    // The theme's ?s=&post_type=wp-manga query is ignored by the site and returns the default listing
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = GET(
        "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build(),
        headers,
    )

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun chapterListSelector() = ".row-content-chapter li"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        with(element.selectFirst("a")!!) {
            name = text()
            setUrlWithoutDomain(absUrl("href"))
        }
        date_upload = parseChapterDate(element.selectFirst(".chapter-time")?.text())
    }

    override fun pageListParse(document: Document): List<Page> = document.select(".read-content img").mapIndexed { index, element ->
        Page(index, imageUrl = element.absUrl("src"))
    }
}
