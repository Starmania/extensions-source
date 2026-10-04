package eu.kanade.tachiyomi.extension.vi.truyenmm

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@Source
abstract class TruyenMM : KeiSource() {
    override fun OkHttpClient.Builder.configureClient() = rateLimit(3)

    // The site answers 522/523 to any request carrying an Origin header.
    override fun Headers.Builder.configureHeaders(): Headers.Builder = removeAll("Origin")

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaListPage(client.get("$baseUrl/danh-sach-truyen/$page"))

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaListPage(client.get("$baseUrl/truyen-moi-cap-nhat/$page"))

    // ============================== Search ================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = "$baseUrl/tim-kiem".toHttpUrl().newBuilder()
                .addQueryParameter("key", query)
                .addQueryParameter("page", page.toString())
                .build()
            return parseMangaListPage(client.get(url))
        }

        val genreSlug = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart()
            ?: return getPopularManga(page)

        return parseMangaListPage(client.get("$baseUrl/the-loai/$genreSlug/$page"))
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "truyen" || segments[1].isEmpty()) return null
        return fetchMangaUpdate(
            SManga.create().apply { this.url = "/truyen/${segments[1]}" },
            emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        ).manga
    }

    private fun parseMangaListPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangaList = document.select("article:has(a[href^=/truyen/])").map { element ->
            SManga.create().apply {
                val mangaLink = element.selectFirst("a[href^=/truyen/]")!!
                title = element.selectFirst("h2, h3")!!.text()
                setUrlWithoutDomain(mangaLink.absUrl("href"))
                thumbnail_url = element.selectFirst("img")?.extractImageUrl()
            }
        }

        return MangasPage(mangaList, hasNextPage(document, response.request.url.toString()))
    }

    private fun hasNextPage(document: Document, requestUrl: String): Boolean {
        if (document.selectFirst("link[rel=next]") != null) return true

        val currentUrl = requestUrl.toHttpUrlOrNull() ?: return false
        val currentPage = currentUrl.queryParameter("page")?.toIntOrNull()
            ?: currentUrl.pathSegments.lastOrNull()?.toIntOrNull()
            ?: 1
        val nextPage = currentPage + 1

        return document.select("a[href]").any { anchor ->
            val pageUrl = anchor.absUrl("href").toHttpUrlOrNull() ?: return@any false
            val pageValue = pageUrl.queryParameter("page")?.toIntOrNull()
                ?: pageUrl.pathSegments.lastOrNull()?.toIntOrNull()
            pageValue == nextPage
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = getFilters()

    // ============================== Details ===============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        return SMangaUpdate(
            parseMangaDetails(document).apply { url = manga.url },
            if (fetchChapters) parseChapterList(document) else emptyList(),
        )
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1")!!.text()
        thumbnail_url = document.selectFirst("img[alt*=Bìa], img[alt*=bìa]")?.extractImageUrl()

        author = findInfoValue(document, "Tác giả")
        status = parseStatus(findInfoValue(document, "Loại Truyện"))
        genre = document.select("dd a[href*='/the-loai/']")
            .map(Element::text)
            .distinct()
            .joinToString()
            .ifEmpty { null }
    }

    private fun findInfoValue(document: Document, label: String): String? = document.select("dl > div").firstOrNull {
        it.selectFirst("dt")?.text()?.startsWith(label, ignoreCase = true) == true
    }?.selectFirst("dd")?.text()?.ifEmpty { null }

    private fun parseStatus(statusText: String?): Int = when {
        statusText == null -> SManga.UNKNOWN
        statusText.contains("hoàn thành", ignoreCase = true) -> SManga.COMPLETED
        statusText.contains("đang tiến hành", ignoreCase = true) -> SManga.ONGOING
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    private suspend fun parseChapterList(document: Document): List<SChapter> {
        val topicId = document.selectFirst("script#script-chapter")?.attr("data-id")
        val topic = topicId?.let { fetchTopic(it) }
        if (topic != null) {
            return topic.chapters.orEmpty().mapNotNull { chapter ->
                val chapterId = chapter.id ?: return@mapNotNull null
                val chapterName = chapter.name ?: return@mapNotNull null
                val chapterUrl = buildChapterUrl(chapterId)

                SChapter.create().apply {
                    setUrlWithoutDomain(chapterUrl)
                    name = chapterName
                    date_upload = chapter.update_time ?: 0L
                }
            }
        }

        return document.select("#chapter-list a[href*='/chapter-']").map { chapterElement ->
            SChapter.create().apply {
                setUrlWithoutDomain(chapterElement.absUrl("href"))
                name = chapterElement.selectFirst("span")?.text() ?: chapterElement.text()
                date_upload = parseChapterDate(chapterElement.selectFirst("time")?.text())
            }
        }
    }

    private fun parseChapterDate(dateText: String?): Long {
        if (dateText == null) return 0L
        val normalized = dateText.replace("🗓", "")
        return parseRelativeDate(normalized).takeIf { it != 0L }
            ?: chapterDateFormat.tryParse(normalized)
    }

    private fun parseRelativeDate(dateText: String): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"))

        if (dateText.contains("vừa xong", ignoreCase = true)) {
            return calendar.timeInMillis
        }

        val number = DATE_NUMBER_REGEX.find(dateText)?.value?.toIntOrNull() ?: return 0L

        when {
            dateText.contains("giây", ignoreCase = true) -> calendar.add(Calendar.SECOND, -number)
            dateText.contains("phút", ignoreCase = true) -> calendar.add(Calendar.MINUTE, -number)
            dateText.contains("giờ", ignoreCase = true) -> calendar.add(Calendar.HOUR_OF_DAY, -number)
            dateText.contains("ngày", ignoreCase = true) -> calendar.add(Calendar.DAY_OF_MONTH, -number)
            dateText.contains("tuần", ignoreCase = true) -> calendar.add(Calendar.WEEK_OF_YEAR, -number)
            dateText.contains("tháng", ignoreCase = true) -> calendar.add(Calendar.MONTH, -number)
            dateText.contains("năm", ignoreCase = true) -> calendar.add(Calendar.YEAR, -number)
            else -> return 0L
        }

        return calendar.timeInMillis
    }

    private suspend fun fetchTopic(topicId: String): TruyenMMTopic? {
        val url = "$baseUrl/api/get-topic".toHttpUrl().newBuilder()
            .addQueryParameter("id", topicId)
            .build()

        // The chapter list in the page's HTML is the fallback when the API is unavailable.
        return try {
            val response = client.get(url, ensureSuccess = false)
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            response.parseAs<TruyenMMGetTopicResponse>().topic
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        }
    }

    private fun buildChapterUrl(rawChapterId: String): String {
        val normalizedChapterId = rawChapterId.replace("-chapter-", "/chapter-")
        val splitIndex = normalizedChapterId.indexOf("/chapter-")
        if (splitIndex == -1) {
            return "$baseUrl/truyen/$normalizedChapterId"
        }

        val mangaId = normalizedChapterId.substring(0, splitIndex)
        val chapterId = normalizedChapterId.substring(splitIndex + 1)
        return "$baseUrl/truyen/$mangaId/$chapterId"
    }

    // ============================== Pages =================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        val imageUrls = document.select("div.w-full.flex.flex-col.items-center img").ifEmpty {
            document.select("img[data-src], img[src]")
        }.mapNotNull { imageElement ->
            imageElement.extractImageUrl()
                ?.takeIf { it.isNotEmpty() }
                ?.takeIf { url ->
                    !url.contains("/chapter-") &&
                        !url.endsWith("/loading.webp") &&
                        !url.endsWith("/page_logo.png")
                }
        }.distinct()

        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    private fun Element.extractImageUrl(): String? {
        val dataSrc = attr("data-src")
        if (dataSrc.isNotBlank()) {
            return absUrl("data-src")
        }

        val src = attr("src")
        if (src.isNotBlank()) {
            return absUrl("src")
        }

        return null
    }

    companion object {
        private val DATE_NUMBER_REGEX = Regex("""\d+""")

        private val chapterDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        }
    }
}
