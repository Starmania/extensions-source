package eu.kanade.tachiyomi.extension.ja.rawbaka

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
import keiyoushi.utils.tryParseDate
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class RawBaka : KeiSource() {

    // The site has no popularity ordering; the archive lists every title.
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("manga")
            if (page > 1) addPathSegments("page/$page")
            addPathSegment("")
        }.build()
        return parseMangaList(client.get(url).asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("latest_page", page.toString())
            .build()
        return parseMangaList(client.get(url).asJsoup())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegments("page/$page/")
            addQueryParameter("s", query)
            addQueryParameter("post_type", "manga")
        }.build()
        return parseMangaList(client.get(url).asJsoup())
    }

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("article.manga-card, article.search-result-card").map { element ->
            SManga.create().apply {
                val link = element.selectFirst("h3 a, h2 a")!!
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text()
                thumbnail_url = element.selectFirst("a.cover img")?.absUrl("src")
            }
        }
        return MangasPage(mangas, document.selectFirst("a.next.page-numbers") != null)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "manga" || url.pathSize < 2) return null
        val path = "/manga/${url.encodedPathSegments[1]}/"
        return parseDetails(client.get(baseUrl + path).asJsoup()).apply { this.url = path }
    }

    override suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        return SMangaUpdate(parseDetails(document).apply { url = manga.url }, parseChapters(document))
    }

    private fun parseDetails(document: Document) = SManga.create().apply {
        title = document.selectFirst("h1.manga-title")!!.text()
        thumbnail_url = document.selectFirst(".manga-hero .cover img")?.absUrl("src")
        description = document.select(".manga-summary p").joinToString("\n\n") { it.wholeText().trim() }
        status = when (document.selectFirst(".status-pill")?.text()?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapters(document: Document) = document.select(".chapter-list .chapter-row").map { element ->
        SChapter.create().apply {
            val link = element.selectFirst("a")!!
            setUrlWithoutDomain(link.absUrl("href"))
            name = link.text()
            date_upload = dateFormat.tryParseDate(element.selectFirst("time")?.text())
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(baseUrl + chapter.url).asJsoup()
        .select(".reader-images img")
        .mapIndexed { index, element -> Page(index, imageUrl = element.absUrl("src")) }

    private val dateFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
}
