package eu.kanade.tachiyomi.extension.ko.wolfdotcom

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
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.time.format.DateTimeFormatter

@Source
abstract class Wolf : KeiSource() {

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

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", POPULAR)

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", LATEST)

    // ============================== Search ===============================

    private val specialChars = Regex("""[^\p{InHangul_Syllables}0-9a-z ]""", RegexOption.IGNORE_CASE)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val requestUrl = if (query.isNotBlank()) {
            if (query.length < 2) {
                throw Exception("두 글자 이상 입력 해주세요.")
            }
            // The search page is EUC-KR and, unlike browsing, is not paginated
            "$baseUrl/sh".toHttpUrl().newBuilder()
                .addEncodedQueryParameter("q", URLEncoder.encode(query.replace(specialChars, ""), "EUC-KR"))
                .build()
        } else {
            val path = filters.filterIsInstance<StatusFilter>().firstOrNull()?.path ?: browsePath
            "$baseUrl/$path".toHttpUrl().newBuilder().apply {
                filters.filterIsInstance<UrlPartFilter>().forEach { it.addToUrl(this) }
                addQueryParameter("pg", page.toString())
            }.build()
        }

        val document = client.get(requestUrl).asJsoup()

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

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.first() != entryPath) return null
        val toon = url.queryParameter("toon") ?: return null

        val manga = SManga.create().apply { this.url = toon }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl.toHttpUrl().newBuilder()
        .addPathSegment(entryPath)
        .addQueryParameter("toon", manga.url)
        .toString()

    // ============================= Chapters ==============================

    @Serializable
    class ChapterUrl(
        val toon: String,
        val num: String,
    )

    // Details and the first page of chapters come from the same page; the rest is paginated by 100
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        var document = client.get(getMangaUrl(manga)).asJsoup()

        val details = SManga.create().apply {
            title = document.selectFirst(".w-title")!!.text()
            thumbnail_url = document.selectFirst(".thumb-wrap img")?.absUrl("src")
            description = document.selectFirst("#summary")?.text()
            genre = document.select(".genre-tags .gtag").eachText().joinToString { it.removePrefix("#") }
        }

        if (!fetchChapters) return SMangaUpdate(details, chapters)

        val allChapters = mutableListOf<SChapter>()
        while (true) {
            allChapters += document.select("a.ep-item").map(::chapterFromElement)
            val next = document.nextPageUrl() ?: break
            document = client.get(next).asJsoup()
        }

        return SMangaUpdate(details, allChapters)
    }

    private fun chapterFromElement(el: Element) = SChapter.create().apply {
        val chapUrl = el.absUrl("href").toHttpUrl()
        url = ChapterUrl(
            chapUrl.queryParameter("toon")!!,
            chapUrl.queryParameter("num")!!,
        ).toJsonString()
        name = el.selectFirst(".ep-title")!!.text()
        date_upload = dateFormat.tryParseDate(el.selectFirst(".ep-date")?.text())
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

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        return document.select(".vimg-area img").mapIndexed { idx, img ->
            Page(idx, imageUrl = img.absUrl("data-src"))
        }
    }

    // ============================== Filters ==============================

    override fun getFilterList(data: JsonElement?): FilterList {
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

    companion object {
        private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
