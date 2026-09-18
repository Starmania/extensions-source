package eu.kanade.tachiyomi.extension.en.comichubfree

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
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class ComicHubFree : KeiSource() {
    private val dateFormat = DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.getDefault())

    override suspend fun getPopularManga(page: Int): MangasPage = fetchMangaList("$baseUrl/popular-comic".toHttpUrl().newBuilder(), page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchMangaList("$baseUrl/new-comic".toHttpUrl().newBuilder(), page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = fetchMangaList(
        "$baseUrl/search-comic".toHttpUrl().newBuilder().addQueryParameter("key", query),
        page,
    )

    private suspend fun fetchMangaList(url: HttpUrl.Builder, page: Int): MangasPage {
        val document = client.get(url.addQueryParameter("page", page.toString()).build()).asJsoup()

        val mangas = document.select(".movie-list-index > .cartoon-box:has(.detail)").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
                title = element.selectFirst("h3")!!.text()
                thumbnail_url = element.selectFirst("img")?.imageAttr()
            }
        }

        return MangasPage(mangas, document.hasNextPage())
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        var document = client.get(getMangaUrl(manga)).asJsoup()
        val details = mangaDetailsParse(document).apply {
            url = manga.url
            title = manga.title
        }

        val chapterList = mutableListOf<SChapter>()
        while (true) {
            chapterListParse(document, chapterList)

            val nextUrl = document.selectFirst("ul.pagination a[rel=next]:not(hidden)")?.absUrl("href")
            if (nextUrl.isNullOrEmpty()) {
                break
            }
            document = client.get(nextUrl).asJsoup()
        }

        return SMangaUpdate(details, chapterList)
    }

    private fun chapterListParse(document: Document, chapters: MutableList<SChapter>) {
        document.select("div.episode-list > div > table > tbody > tr").mapTo(chapters) { element ->
            val urlElement = element.selectFirst("a")!!
            val dateElement = element.select("td:last-of-type")

            SChapter.create().apply {
                setUrlWithoutDomain(urlElement.attr("abs:href"))
                name = urlElement.text()
                date_upload = dateFormat.tryParseDate(dateElement.text())
            }
        }
    }

    private fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.selectFirst("div.movie-info") ?: return SManga.create()
        val seriesInfoElement = infoElement.selectFirst("div.series-info")
        val seriesDescriptionElement = infoElement.selectFirst("div#film-content")

        val authorElement = seriesInfoElement?.select("dt:contains(Author:) + dd")
        val statusElement = seriesInfoElement?.select("dt:contains(Status:) + dd")

        val image = seriesInfoElement?.selectFirst("img")

        return SManga.create().apply {
            description = seriesDescriptionElement?.text()
            thumbnail_url = image?.imageAttr()
            author = authorElement?.text()
            status = parseStatus(statusElement?.text().orEmpty())
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}/all").asJsoup()
        return document.select("img.chapter_img").mapIndexed { index, element ->
            Page(index, imageUrl = element.imageAttr())
        }.distinctBy { it.imageUrl }
    }

    private fun Document.hasNextPage() = selectFirst("ul.pagination a[rel=next]:not(hidden)") != null

    private fun parseStatus(status: String): Int = when (status) {
        "Ongoing" -> SManga.ONGOING
        "Completed" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private fun Element.imageAttr(): String = when {
        hasAttr("data-src") -> absUrl("data-src")
        else -> absUrl("src")
    }
}
