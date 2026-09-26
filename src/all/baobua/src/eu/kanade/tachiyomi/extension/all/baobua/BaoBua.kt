package eu.kanade.tachiyomi.extension.all.baobua

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstance
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import kotlin.time.Instant

@Source
abstract class BaoBua : KeiSource() {

    override val supportsLatest = false

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    // ========================= Popular =========================
    override suspend fun getPopularManga(page: Int): MangasPage = parseMangasPage(client.get("$baseUrl/?page=$page").asJsoup())

    // ========================= Latest  =========================
    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    // ========================= Search  =========================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) throw Exception("Full-text search is not supported")

        val filter = filters.firstInstance<SourceCategorySelector>()
        return filter.selectedCategory?.let {
            parseMangasPage(client.get(it.buildUrl(baseUrl, page)).asJsoup())
        } ?: getPopularManga(page)
    }

    // Deeplinks cover both single galleries and category listings.
    override suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage {
        if (url.host != baseUrl.toHttpUrl().host) throw Exception("Full-text search is not supported")

        val document = client.get(url).asJsoup()

        if (document.selectFirst(IMAGE_SELECTOR) != null) {
            val manga = parseMangaDetails(document).apply {
                this.url = url.encodedPath
                title = document.selectFirst(".s-denomination .box-mt-output")?.text()
                    ?.removePrefix(TITLE_PREFIX)
                    ?: throw Exception("Title is mandatory")
                thumbnail_url = document.selectFirst(IMAGE_SELECTOR)?.absUrl("src")
                    ?.let { normalizeImageUrl(it) }
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            }
            return MangasPage(listOf(manga), false)
        }

        return parseMangasPage(document)
    }

    // ========================= Details =========================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get(getMangaUrl(manga))
        val requestUrl = response.request.url.toString()
        val document = response.asJsoup()
        return SMangaUpdate(parseMangaDetails(document), parseChapterList(document, requestUrl))
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        genre = document.select(".it-cat-content a").joinToString { it.text() }
        status = SManga.COMPLETED
    }

    // ========================= Chapters=========================
    private fun parseChapterList(document: Document, requestUrl: String): List<SChapter> = listOf(
        SChapter.create().apply {
            chapter_number = 0F
            val absUrl = document.selectFirst("link[rel=canonical]")?.absUrl("href")
                ?: requestUrl
            url = absUrl.toHttpUrlOrNull()?.encodedPath ?: absUrl
            date_upload = DATE_PUBLISHED_REGEX.find(document.select("script[type=application/ld+json]").html())
                ?.groupValues?.get(1)
                .let { Instant.tryParse(it) }
            name = "Gallery"
        },
    )

    // ========================= Pages   =========================
    override suspend fun getPageList(chapter: SChapter): List<Page> = recursivePageListParse(client.get(getChapterUrl(chapter)).asJsoup())

    private suspend fun recursivePageListParse(document: Document): List<Page> {
        val pages = document.select(IMAGE_SELECTOR)
            .mapIndexed { index, element ->
                Page(index, imageUrl = normalizeImageUrl(element.absUrl("src")))
            }

        val nextPageUrl = document.selectFirst("a.page-numbers:contains(Next)")
            ?.absUrl("href")
            ?: return pages

        val nextPages = recursivePageListParse(client.get(nextPageUrl).asJsoup())
        val offset = pages.size
        val redirectedNextPages = nextPages.map { Page(it.index + offset, it.url, it.imageUrl) }
        return pages + redirectedNextPages
    }

    // ========================= Filters =========================
    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SourceCategorySelector.create(),
    )

    // ========================= Helpers =========================
    private fun parseMangasPage(document: Document): MangasPage {
        val mangas = document.select(".videos .thumb-view").mapNotNull { element ->
            SManga.create().apply {
                val link = element.selectFirst("a.denomination") ?: return@mapNotNull null
                val absUrl = link.absUrl("href")
                url = absUrl.toHttpUrlOrNull()?.encodedPath ?: absUrl
                title = link.text()
                thumbnail_url = element.selectFirst("img.xld")?.absUrl("src")
                    ?.let { normalizeImageUrl(it) }
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            }
        }

        val hasNextPage = document.selectFirst(".pagination-site a.next") != null

        return MangasPage(mangas, hasNextPage)
    }

    private fun normalizeImageUrl(url: String): String = if (WP_COM_REGEX.containsMatchIn(url)) {
        url.replace(WP_COM_REPLACE_REGEX, "https://")
            .replace("?w=640", "")
    } else {
        url
    }

    companion object {
        private val WP_COM_REGEX = Regex("""^https://i\d+\.wp\.com/""")
        private val WP_COM_REPLACE_REGEX = Regex("""https://i\d+\.wp\.com/""")
        private const val IMAGE_SELECTOR = ".video_block .contentme img"
        private const val TITLE_PREFIX = "BaoBua.Net: "
        private val DATE_PUBLISHED_REGEX = Regex(""""datePublished":"([^"]+)"""")
    }
}
