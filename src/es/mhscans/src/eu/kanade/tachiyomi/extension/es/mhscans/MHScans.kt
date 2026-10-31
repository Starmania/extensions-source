package eu.kanade.tachiyomi.extension.es.mhscans

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MHScans : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2, 1.seconds)

    override suspend fun getPopularManga(page: Int) = fetchLibrary(page, "views")

    override suspend fun getLatestUpdates(page: Int) = fetchLibrary(page, "updated")

    private suspend fun fetchLibrary(page: Int, sort: String): MangasPage = parseLibrary(client.get("$baseUrl/library?sort=$sort&page=$page", Fetch.DOCUMENT).asJsoup())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/library".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) addQueryParameter("q", query)
            filters.filterIsInstance<QueryFilter>().forEach { filter ->
                if (filter.value.isNotEmpty()) addQueryParameter(filter.param, filter.value)
            }
        }.build()
        return parseLibrary(client.get(url, Fetch.DOCUMENT).asJsoup())
    }

    private fun parseLibrary(document: Document): MangasPage {
        val mangas = document.select(".lib-grid a.comic-card")
            // Novels are in the same catalogue, but their chapters are text
            .filterNot { it.selectFirst("span.top-2.left-2")?.text() == "Novel" }
            .map { card ->
                SManga.create().apply {
                    setUrlWithoutDomain(card.absUrl("href"))
                    title = card.selectFirst("p.leading-snug")!!.text()
                    thumbnail_url = card.selectFirst("img")?.absUrl("src")
                }
            }
        return MangasPage(mangas, document.selectFirst("a.page-btn[aria-label=Siguiente]") != null)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "comics") return null
        val path = "/comics/${url.pathSegments.getOrNull(1) ?: return null}"
        return parseDetails(client.get(baseUrl + path, Fetch.DOCUMENT).asJsoup()).apply { this.url = path }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val details = async { if (fetchDetails) parseDetails(client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()) else manga }
        val chapterList = async { if (fetchChapters) fetchChapterList(manga) else chapters }
        SMangaUpdate(details.await(), chapterList.await())
    }

    private fun parseDetails(document: Document) = SManga.create().apply {
        title = document.selectFirst("h1")!!.text()
        thumbnail_url = document.selectFirst("img.comic-cover__img")?.absUrl("src")
        description = document.selectFirst("#comic-description")?.text()
        genre = document.select(".comic-chip").joinToString { it.text() }
        author = document.selectFirst("a[href^=/groups/]")?.text()
        status = when (document.selectFirst(".comic-meta__status")?.text()) {
            "En emisión" -> SManga.ONGOING
            "Completado" -> SManga.COMPLETED
            "En hiato" -> SManga.ON_HIATUS
            "Cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private suspend fun fetchChapterList(manga: SManga): List<SChapter> = buildList {
        var page = 1
        do {
            val response = client.get("${getMangaUrl(manga)}/chapters?page=$page", Fetch.DOCUMENT)
            val lastPage = response.header("x-pages")!!.toInt()
            // Locked chapters only link to the login page and carry no data-chapter-id, so there is nothing to open
            response.asJsoup().select("a[data-chapter-id]").mapTo(this) { row ->
                SChapter.create().apply {
                    setUrlWithoutDomain(row.absUrl("href"))
                    name = row.attr("data-chapter-label")
                    chapter_number = row.attr("data-chapter-num").toFloat()
                    date_upload = parseRelativeDate(row.selectFirst("span.tracking-wide")?.text().orEmpty())
                }
            }
        } while (page++ < lastPage)
    }

    private fun parseRelativeDate(date: String): Long {
        val match = RELATIVE_DATE.find(date) ?: return 0L
        val amount = match.groupValues[1].toLong()
        val now = ZonedDateTime.now()
        val uploaded = when (match.groupValues[2].lowercase()) {
            "min" -> now.minusMinutes(amount)
            "h" -> now.minusHours(amount)
            "d" -> now.minusDays(amount)
            "sem" -> now.minusWeeks(amount)
            "mes" -> now.minusMonths(amount)
            else -> now.minusYears(amount)
        }
        return uploaded.toInstant().toEpochMilli()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        .select("img.page-img")
        .mapIndexed { index, img -> Page(index, imageUrl = img.absUrl("data-src").ifEmpty { img.absUrl("src") }) }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    private companion object {
        val RELATIVE_DATE = Regex("""hace (\d+) (min|h|d|sem|mes|a)""", RegexOption.IGNORE_CASE)
    }
}
