package eu.kanade.tachiyomi.extension.es.submanhwa

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
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Submanhwa : KeiSource() {

    private val dateFormat = SimpleDateFormat("dd MMM. yyyy", Locale.ENGLISH)

    override fun Headers.Builder.configureHeaders() = add("Accept-Language", "es-PE,es;q=0.9,en-US;q=0.8,en;q=0.7")

    override suspend fun getPopularManga(page: Int): MangasPage = fetchListing(page)

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get(baseUrl).asJsoup()
        val mangas = document.select("div[class^=manga-item]").map { element ->
            SManga.create().apply {
                title = element.selectFirst("h3[class^=manga-title] a")!!.text()
                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
                thumbnail_url = element.selectFirst("img")!!.absUrl("src")
            }
        }

        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = fetchListing(page, query)

    private suspend fun fetchListing(page: Int, query: String? = null): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("filterList")
            addQueryParameter("page", page.toString())
            addQueryParameter("sortBy", "views")
            addQueryParameter("asc", "false")
            if (query != null) addQueryParameter("alpha", query)
        }.build()

        val document = client.get(url).asJsoup()
        val mangas = document.select(".series-card").map { element ->
            SManga.create().apply {
                title = element.selectFirst(".series-title")!!.text()
                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
                thumbnail_url = element.selectFirst("img")!!.absUrl("src")
            }
        }
        val hasNextPage = document.selectFirst("li a[rel=next]") != null

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "serie") return null

        return mangaDetailsParse(client.get(url).asJsoup()).apply {
            setUrlWithoutDomain(url.toString())
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        return SMangaUpdate(mangaDetailsParse(document), chapterListParse(document))
    }

    private fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst(".manga-title-centered")!!.text()
        thumbnail_url = document.selectFirst("img")?.absUrl("src")
        description = document.selectFirst("h5:contains(Resumen) + p")?.text()

        val box = document.selectFirst(".main-content > .boxed-modern")

        status = when (box?.selectFirst(".detail-label:contains(Estado) + .detail-value span")?.text()?.lowercase()) {
            "completa" -> SManga.COMPLETED
            "en curso" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        author = box?.selectFirst(".detail-label:contains(Autor) + .detail-value a")?.text()
        artist = box?.selectFirst(".detail-label:contains(Artist) + .detail-value a")?.text()
        genre = box?.select(".detail-label:contains(Categor) + .detail-value a")?.joinToString { it.text() }
    }

    private fun chapterListParse(document: Document): List<SChapter> = document.select(".chapters-grid [class^=chapter-card]").map { element ->
        SChapter.create().apply {
            val a = element.selectFirst("a.chapter-link")!!
            name = a.text()
            setUrlWithoutDomain(a.absUrl("href"))

            val date = element.selectFirst("span:has(i.glyphicon-time)")?.text()
                ?: element.selectFirst(".chapter-preview-meta > span")?.text()

            date_upload = dateFormat.tryParse(date)
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        return document.select("#all img").mapIndexed { idx, img ->
            Page(idx, imageUrl = img.imgAttr())
        }
    }

    private fun Element.imgAttr(): String = when {
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }
}
