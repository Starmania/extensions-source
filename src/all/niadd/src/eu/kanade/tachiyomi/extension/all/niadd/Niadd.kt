package eu.kanade.tachiyomi.extension.all.niadd

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Niadd : HttpSource() {

    override val supportsLatest = true

    companion object {
        private val CHAPTER_NUMBER_REGEX = Regex("""Capítulo\s+(\d+(\.\d+)?)""")
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Popular
    override fun popularMangaRequest(page: Int) = GET("$baseUrl/list/Hot-Manga.html", headers)

    private fun popularMangaSelector() = "div.manga-item"

    private fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst("div.manga-name")!!.text()
        val rawUrl = element.selectFirst("a")!!.absUrl("href")
        setUrlWithoutDomain(rawUrl)
        element.selectFirst("div.manga-img img")?.attr("abs:src")?.also { thumbnail_url = it }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }
        return MangasPage(mangas, false)
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("name", query)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }
        return MangasPage(mangas, false)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/list/New-Update.html", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }
        return MangasPage(mangas, false)
    }

    // Details
    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        val infoElement = document.select("div.bookside-general, div.detail-general")

        title = document.selectFirst("h1, .book-headline-name")!!.text()
        author = infoElement.select(".detail-general-cell:contains(Autor) span, [itemprop=author] span").text()
            .replace("Autor (es):", "", ignoreCase = true)
        artist = infoElement.select(".detail-general-cell:contains(Artista) span").text()
            .replace("Artista:", "", ignoreCase = true)
        genre = document.select("[itemprop=genre]").eachText().joinToString()

        val yearKeywords = listOf(
            "Released:",
            "Lanzado:",
            "Rilasciato:",
            "Выпущенный:",
            "Liberado:",
            "Freigegeben:",
        )

        val yearRaw = infoElement.select(".detail-general-cell").firstOrNull { cell ->
            yearKeywords.any { cell.text().contains(it, ignoreCase = true) }
        }?.selectFirst("span")?.text().orEmpty()

        val yearClean = yearRaw
            .let { text ->
                yearKeywords.fold(text) { acc, keyword -> acc.replace(keyword, "", ignoreCase = true) }
            }

        val synopsisKeywords = listOf(
            "Synopsis",
            "Sinopsis",
            "Sinossi",
            "конспект",
            "Sinopse",
            "Zusammenfassung",
        )

        val synopsisText = run {
            val titles = document.select(".detail-cate-title")
            for (title in titles) {
                val titleText = title.text()
                if (synopsisKeywords.any { keyword -> titleText.contains(keyword, ignoreCase = true) }) {
                    val nextSection = title.nextElementSibling()
                    if (nextSection != null && nextSection.hasClass("detail-section")) {
                        if (!nextSection.select("a[itemprop=genre]").any()) {
                            return@run nextSection.text()
                        }
                    }
                }
            }
            ""
        }

        description = buildString {
            if (yearClean.isNotEmpty()) append("Ano: $yearClean\n\n")
            if (synopsisText.isNotEmpty()) append(synopsisText)
        }

        document.selectFirst("div.detail-img img, div.bookside-img img")?.attr("abs:src").also { thumbnail_url = it }
        status = SManga.ONGOING
    }

    // Chapters
    override fun chapterListRequest(manga: SManga): Request {
        val chaptersUrl = baseUrl + manga.url.removeSuffix(".html") + "/chapters.html"
        return GET(chaptersUrl, headers)
    }

    private val chapterListSelector = "ul.chapter-list a.hover-underline"
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)

    private fun parseDate(dateString: String): Long {
        if (dateString.contains("atrás", ignoreCase = true) ||
            dateString.contains("ago", ignoreCase = true)
        ) {
            return 0L
        }

        return dateFormat.tryParse(dateString)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        document.selectFirst("ul.chapter-list")!!

        return document.select(chapterListSelector).map { chapterFromElement(it) }
    }

    private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val rawUrl = element.attr("abs:href")
        setUrlWithoutDomain(rawUrl)

        name = element.selectFirst("span.chapter-name, span.name")?.text()
            ?.takeIf(String::isNotEmpty)
            ?: element.text()

        element.selectFirst("span.chapter-time, span.time")?.text()
            ?.also { date_upload = parseDate(it) }

        chapter_number = CHAPTER_NUMBER_REGEX.find(name)
            ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }

    // Pages
    // Like ninemanga, the site serves a chapter either one image per page ("<id>-<n>.html") or
    // ten images per page ("<id>-10-<n>.html"). Only the latter is worth scraping: the single
    // image pages re-sign every image URL per request, so they cannot even be de-duplicated.
    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.removeSuffix("/").removeSuffix(".html")
        return GET("$baseUrl$chapterId-10-1.html", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val imageUrls = pageImageUrls(document).toMutableList()

        document.select("select.sl-page option").drop(1).forEach { option ->
            val groupUrl = option.absUrl("value")
            client.newCall(GET(groupUrl, headers)).execute().use { groupResponse ->
                imageUrls += pageImageUrls(groupResponse.asJsoup())
            }
        }

        return imageUrls.mapIndexed { i, imageUrl -> Page(i, imageUrl = imageUrl) }
    }

    private fun pageImageUrls(document: Document): List<String> = document.select("div.pic_box img.manga_pic").map { it.absUrl("src") }
}
