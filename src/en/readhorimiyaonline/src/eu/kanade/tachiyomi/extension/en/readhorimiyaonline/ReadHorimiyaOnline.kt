package eu.kanade.tachiyomi.extension.en.readhorimiyaonline

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import org.jsoup.nodes.Document

@Source
abstract class ReadHorimiyaOnline : KeiSource() {

    override val supportsLatest = false

    // Popular
    override suspend fun getPopularManga(page: Int): MangasPage {
        val doc = client.get(baseUrl).asJsoup()
        return MangasPage(listOf(parseManga(doc)), false)
    }

    // Latest
    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException("No latest updates")

    // Search
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = throw UnsupportedOperationException("Search not supported")

    // Manga Details & Chapter List
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get(baseUrl).asJsoup()
        return SMangaUpdate(parseManga(doc), parseChapters(doc))
    }

    private fun parseChapters(doc: Document): List<SChapter> = doc.select("#chapters-list-holder a.chapter-list-item").map { element ->
        SChapter.create().apply {
            name = element.selectFirst(".chapter-name")!!.text()
            setUrlWithoutDomain(element.absUrl("href"))
        }
    }

    // Page List
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = client.get(getChapterUrl(chapter)).asJsoup()
        return doc.select(".images-container img").mapIndexed { index, img ->
            val imageUrl = img.attr("data-lazy-src").takeIf { it.isNotEmpty() }
                ?: img.absUrl("src")
            Page(index, imageUrl = imageUrl)
        }
    }

    // Private helper to avoid duplication
    private fun parseManga(doc: Document): SManga = SManga.create().apply {
        title = "Horimiya"
        url = "/"
        thumbnail_url = doc.selectFirst("img.manga-thumb")
            ?.let { img ->
                img.attr("data-lazy-src").takeIf { it.isNotEmpty() }
                    ?: img.absUrl("src")
            }
        description = doc.selectFirst("span.desc")?.text()
        status = SManga.UNKNOWN
    }
}
