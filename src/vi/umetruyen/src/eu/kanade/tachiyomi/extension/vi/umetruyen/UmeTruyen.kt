package eu.kanade.tachiyomi.extension.vi.umetruyen

import eu.kanade.tachiyomi.source.model.Filter
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
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Source
abstract class UmeTruyen : KeiSource() {

    override suspend fun getPopularManga(page: Int) = fetchListing(page, "", "trending", "")

    override suspend fun getLatestUpdates(page: Int) = fetchListing(page, "", "latest", "")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.value ?: "latest"
        val status = filters.firstInstanceOrNull<StatusFilter>()?.value ?: ""
        return fetchListing(page, query, sort, status)
    }

    private suspend fun fetchListing(page: Int, query: String, sort: String, status: String): MangasPage {
        val url = "$baseUrl/tim-kiem-nang-cao".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("s", query)
            if (status.isNotEmpty()) addQueryParameter("status", status)
            addQueryParameter("orderby", sort)
            addQueryParameter("page", page.toString())
        }.build()
        val document = client.get(url).asJsoup()
        val mangas = document.select(".story-card > a").map(::mangaFromElement)
        return MangasPage(mangas, document.selectFirst("a.listing-page-btn--next") != null)
    }

    private fun mangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst(".story-title")!!.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.size != 1) return null
        val manga = SManga.create().apply { setUrlWithoutDomain(url.toString()) }
        return client.get(getMangaUrl(manga)).asJsoup().let(::parseMangaDetails).apply { this.url = manga.url }
    }

    // Details and chapters come from the same page.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(
            manga = parseMangaDetails(document),
            chapters = parseChapterList(document),
        )
    }

    private fun parseMangaDetails(document: Document) = SManga.create().apply {
        title = document.selectFirst(".detail-cover img")!!.attr("alt")
        thumbnail_url = document.selectFirst(".detail-cover img")?.absUrl("src")
        author = document.metaValue("Tác giả")?.takeUnless { it == "Đang cập nhật" }
        genre = document.select(".detail-genre-tag").joinToString { it.text() }
        description = document.selectFirst(".detail-description")?.text()
        status = when (document.metaValue("Trạng thái")?.lowercase()) {
            "đang ra", "đang tiến hành" -> SManga.ONGOING
            "hoàn thành" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    private fun Document.metaValue(label: String): String? = selectFirst(".detail-meta-row:has(> .label:containsOwn($label)) > .value")?.text()

    private fun parseChapterList(document: Document): List<SChapter> = document.select("#manga-chapters-holder li.wp-manga-chapter").map { element ->
        SChapter.create().apply {
            element.selectFirst("a")!!.let {
                setUrlWithoutDomain(it.absUrl("href"))
                name = it.text()
            }
            date_upload = parseRelativeDate(element.selectFirst(".chapter-date")?.text())
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        // Every chapter is locked for anonymous visitors; the site sets the session cookie after a
        // login in WebView, which the app's cookie jar shares.
        if (document.selectFirst(".read-lock-card") != null) {
            throw IOException("Đăng nhập qua WebView để đọc chương này")
        }
        return document.select(".reading-content .page-break img").mapIndexed { i, img ->
            Page(i, imageUrl = img.absUrl(if (img.hasAttr("data-src")) "data-src" else "src"))
        }
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        StatusFilter(),
    )

    private open class SelectFilter(name: String, private val options: Array<Pair<String, String>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val value get() = options[state].second
    }

    private class SortFilter :
        SelectFilter(
            "Sắp xếp",
            arrayOf("Mới cập nhật" to "latest", "Thịnh hành" to "trending", "Truyện mới" to "new-manga"),
        )

    private class StatusFilter :
        SelectFilter(
            "Trạng thái",
            arrayOf("Tất cả" to "", "Đang ra" to "ongoing", "Hoàn thành" to "completed"),
        )

    private fun parseRelativeDate(date: String?): Long {
        val match = date?.let(relativeDateRegex::find) ?: return 0L
        val unit = when (match.groupValues[2]) {
            "giây" -> ChronoUnit.SECONDS
            "phút" -> ChronoUnit.MINUTES
            "giờ" -> ChronoUnit.HOURS
            "ngày" -> ChronoUnit.DAYS
            "tuần" -> ChronoUnit.WEEKS
            "tháng" -> ChronoUnit.MONTHS
            "năm" -> ChronoUnit.YEARS
            else -> return 0L
        }
        return ZonedDateTime.now(zoneId).minus(match.groupValues[1].toLong(), unit).toInstant().toEpochMilli()
    }

    companion object {
        private val relativeDateRegex = Regex("""(\d+)\s+(giây|phút|giờ|ngày|tuần|tháng|năm)""")
        private val zoneId = ZoneId.of("Asia/Ho_Chi_Minh")
    }
}
