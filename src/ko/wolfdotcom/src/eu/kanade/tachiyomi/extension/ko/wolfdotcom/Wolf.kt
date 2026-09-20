package eu.kanade.tachiyomi.extension.ko.wolfdotcom

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Wolf : HttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addNetworkInterceptor(::refererInterceptor)
        .build()

    private val isComic get() = name.endsWith("만화책")
    private val isPhoto get() = name.endsWith("포토툰")

    private val browsePath get() = when {
        isComic -> "cm"
        isPhoto -> "pt"
        else -> "ing" // Webtoon
    }

    private val entryPath get() = when {
        isComic -> "cl"
        else -> "list"
    }

    private val readerPath get() = when {
        isComic -> "cv"
        else -> "view"
    }

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request = searchMangaRequest(page, "", POPULAR)

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = searchMangaRequest(page, "", LATEST)

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    // ============================== Search ===============================

    private val specialChars = Regex("""[^\p{InHangul_Syllables}0-9a-z ]""", RegexOption.IGNORE_CASE)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            if (query.length < 2) {
                throw Exception("두 글자 이상 입력 해주세요.")
            }
            // The search page is EUC-KR and, unlike browsing, is not paginated
            val url = "$baseUrl/sh".toHttpUrl().newBuilder()
                .addEncodedQueryParameter("q", URLEncoder.encode(query.replace(specialChars, ""), "EUC-KR"))
                .build()

            return GET(url, headers)
        }

        val path = filters.filterIsInstance<StatusFilter>().firstOrNull()?.path ?: browsePath
        val url = "$baseUrl/$path".toHttpUrl().newBuilder().apply {
            filters.filterIsInstance<UrlPartFilter>().forEach { it.addToUrl(this) }
            addQueryParameter("pg", page.toString())
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        // Search results mix webtoons and comics
        val entries = document.select("a.t-card[href^=/$entryPath?]").map { el ->
            SManga.create().apply {
                url = el.absUrl("href").toHttpUrl().queryParameter("toon")!!
                title = el.selectFirst(".t-title")!!.text()
                thumbnail_url = el.selectFirst(".t-img > img")?.absUrl("src")
            }
        }

        return MangasPage(entries, document.nextPageUrl() != null)
    }

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String = baseUrl.toHttpUrl().newBuilder()
        .addPathSegment(entryPath)
        .addQueryParameter("toon", manga.url)
        .toString()

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst(".w-title")!!.text()
            thumbnail_url = document.selectFirst(".thumb-wrap img")?.absUrl("src")
            description = document.selectFirst("#summary")?.text()
            genre = document.select(".genre-tags .gtag").eachText().joinToString { it.removePrefix("#") }
        }
    }

    // ============================= Chapters ==============================

    @Serializable
    class ChapterUrl(
        val toon: String,
        val num: String,
    )

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    // The list is paginated by 100 chapters
    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var document = response.asJsoup()

        while (true) {
            chapters += document.select("a.ep-item").map(::chapterFromElement)
            val next = document.nextPageUrl() ?: break
            document = client.newCall(GET(next, headers)).execute().asJsoup()
        }

        return chapters
    }

    private fun chapterFromElement(el: Element) = SChapter.create().apply {
        val chapUrl = el.absUrl("href").toHttpUrl()
        url = ChapterUrl(
            chapUrl.queryParameter("toon")!!,
            chapUrl.queryParameter("num")!!,
        ).toJsonString()
        name = el.selectFirst(".ep-title")!!.text()
        date_upload = dateFormat.tryParse(el.selectFirst(".ep-date")?.text())
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val chapUrl = chapter.url.parseAs<ChapterUrl>()

        return baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(readerPath)
            .addQueryParameter("toon", chapUrl.toon)
            .addQueryParameter("num", chapUrl.num)
            .toString()
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        return document.select(".vimg-area img").mapIndexed { idx, img ->
            Page(idx, imageUrl = img.absUrl("data-src"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Filters ==============================

    override fun getFilterList(): FilterList {
        if (isPhoto) return FilterList()

        val filters: List<Filter<*>> = if (isComic) {
            listOf(SortFilter(), ComicGenreFilter())
        } else {
            listOf(SortFilter(), StatusFilter(), CategoryFilter(), DayFilter(), WebtoonGenreFilter())
        }

        return FilterList(filters)
    }

    // ============================= Utilities =============================

    // The next arrow is a link, and a plain span on the last page
    private fun Document.nextPageUrl(): String? = selectFirst(".pagi > .pg-btn:last-child")
        ?.takeIf { it.tagName() == "a" }
        ?.absUrl("href")

    private fun refererInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Referer", "$baseUrl/")
            .build()

        return chain.proceed(request)
    }

    companion object {
        private val dateFormat by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        }
    }
}
