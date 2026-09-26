package eu.kanade.tachiyomi.extension.all.foamgirl

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class FoamGirl : KeiSource() {
    override val supportsLatest = false

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    // ============================== Popular ======================================

    override suspend fun getPopularManga(page: Int): MangasPage = mangaListParse(client.get("$baseUrl/page/$page").asJsoup())

    private fun mangaListParse(document: Document): MangasPage {
        val mangas = document.select(".update_area .i_list").map { element ->
            SManga.create().apply {
                thumbnail_url = element.select("img").attr("data-original")
                title = element.select("a.meta-title").text()
                setUrlWithoutDomain(element.select("a").attr("href"))
                initialized = true
            }
        }
        val hasNextPage = document.selectFirst("a.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ======================================

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    // ============================== Search ======================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("page")
            addPathSegment("$page")
            addQueryParameter("post_type", "post")
            addQueryParameter("s", query)
        }.build()
        return mangaListParse(client.get(url).asJsoup())
    }

    // ============================== Details & Chapters ======================================

    // Details are fully populated from the listing, so only the gallery page for its chapter is fetched.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchChapters) return SMangaUpdate(manga, chapters)

        val document = client.get(getMangaUrl(manga)).asJsoup()
        val chapter = SChapter.create().apply {
            setUrlWithoutDomain(document.select("link[rel=canonical]").attr("abs:href"))
            chapter_number = 0F
            name = "GALLERY"
            date_upload = getDate(document.select("span.image-info-time").text().substring(1))
        }
        return SMangaUpdate(manga, listOf(chapter))
    }

    // ============================== Pages ======================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val allPages = mutableListOf<Page>()
        var document = client.get(getChapterUrl(chapter)).asJsoup()
        var pageIndex = 0

        while (true) {
            document.select(".imageclick-imgbox").forEach { element ->
                allPages.add(Page(pageIndex++, imageUrl = element.absUrl("href")))
            }

            val nextPageUrl = document.selectFirst(".page-numbers[title=Next page]")
                ?.absUrl("href")
                ?.takeIf { HAS_NEXT_PAGE_REGEX in it }
                ?: break

            document = client.get(nextPageUrl).asJsoup()
        }

        return allPages
    }

    // ============================== Helpers ======================================

    private fun getDate(str: String): Long = try {
        DATE_FORMAT.parse(str)?.time ?: 0L
    } catch (_: ParseException) {
        0L
    }

    companion object {
        private val HAS_NEXT_PAGE_REGEX = """(\d+_\d+)""".toRegex()
        private val DATE_FORMAT by lazy {
            SimpleDateFormat("yyyy.M.d", Locale.ENGLISH)
        }
    }
}
