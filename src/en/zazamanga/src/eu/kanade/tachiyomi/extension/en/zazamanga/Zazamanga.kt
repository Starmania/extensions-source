package eu.kanade.tachiyomi.extension.en.zazamanga

import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import keiyoushi.annotation.Source
import okhttp3.Request
import org.jsoup.nodes.Element

@Source
abstract class Zazamanga : MadaraNoAjax() {
    override fun chapterListSelector() = "div.wp-manga-chapter"

    override fun nextPageSelector() = ".pagination li:last-child:not(.disabled)"

    // The site only paginates text search through the "page" query parameter, /page/N/ is ignored there
    override fun archiveUrlBuilder(page: Int, order: String, path: String, query: String) = super.archiveUrlBuilder(1, order, path, query).apply {
        if (query.isNotBlank()) addQueryParameter("post_type", "wp-manga")
        if (page > 1) addQueryParameter("page", page.toString())
    }

    // Search results carry post ids like the archives, and genre pages ignore the query
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = if (query.isBlank()) super.getSearchMangaList(page, query, filters) else archivePage(page, "", "/", query)

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    override fun imageFromElement(element: Element): String? = when {
        element.hasAttr("data-src") -> element.attr("data-src")
        element.hasAttr("data-lazy-src") -> element.attr("data-lazy-src")
        element.hasAttr("srcset") -> element.attr("srcset").getSrcSetImage()
        element.hasAttr("data-cfsrc") -> element.attr("data-cfsrc")
        else -> element.attr("src")
    }
}
