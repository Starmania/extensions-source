package eu.kanade.tachiyomi.extension.all.mitaku

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstance
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Mitaku : KeiSource() {

    override val supportsLatest get() = false

    private val baseHttpUrl = baseUrl.toHttpUrl()

    // ========================= Popular =========================
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = baseHttpUrl.newBuilder()
            .addPathSegment("category")
            .addPathSegment("ero-cosplay")
            .addPathSegment("page")
            .addPathSegment(page.toString())
            .build()
        return parseMangasPage(client.get(url).asJsoup())
    }

    // ========================= Latest =========================
    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    // ========================= Search =========================
    override suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage {
        if (url.host != baseHttpUrl.host) return MangasPage(emptyList(), false)

        if (isMangaOrChapterPath(url.pathSegments.filter { it.isNotBlank() })) {
            val document = client.get(url).asJsoup()
            val manga = mangaDetailsParse(document).apply {
                this.url = document.location().toHttpUrl().encodedPath
            }
            return MangasPage(listOf(manga), false)
        }

        url.queryParameter("s")?.let { return searchPage(page, it) }

        return parseMangasPage(client.get(url).asJsoup())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) return searchPage(page, query.trim())

        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val categoryFilter = filterList.firstInstance<CategoryFilter>()
        val tagFilter = filterList.firstInstance<TagFilter>()

        categoryFilter.selected?.let { category ->
            val url = baseHttpUrl.newBuilder()
                .addPathSegment("category")
                .addPathSegment(category)
                .addPathSegment("page")
                .addPathSegment(page.toString())
                .build()
            return parseMangasPage(client.get(url).asJsoup())
        }

        val tag = tagFilter.toUriPart()
        if (tag.isNotEmpty()) {
            val url = baseHttpUrl.newBuilder()
                .addPathSegment("tag")
                .addPathSegment(tag)
                .addPathSegment("page")
                .addPathSegment(page.toString())
                .build()
            return parseMangasPage(client.get(url).asJsoup())
        }

        return getPopularManga(page)
    }

    private suspend fun searchPage(page: Int, query: String): MangasPage {
        val url = baseHttpUrl.newBuilder()
            .addPathSegment("page")
            .addPathSegment(page.toString())
            .addQueryParameter("s", query)
            .build()
        return parseMangasPage(client.get(url).asJsoup())
    }

    // ========================= Filters =========================
    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("NOTE: Only one tag search"),
        Filter.Separator(),
        CategoryFilter(),
        TagFilter(),
    )

    // ========================= Details =========================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val path = document.location().toHttpUrl().encodedPath

        val details = mangaDetailsParse(document).apply { url = path }

        val title = document.selectFirst("article h1")?.text() ?: ""
        val chapter = SChapter.create().apply {
            url = path
            chapter_number = 1F
            name = if (title.endsWith("(Video)")) {
                "This post is video-only, watch it in WebView"
            } else {
                "Gallery"
            }
        }

        return SMangaUpdate(details, listOf(chapter))
    }

    private fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val article = document.selectFirst("article") ?: throw Exception("Post details not found")

        title = article.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: throw Exception("Title is mandatory")

        val categoryGenres = article.select("span.cat-links a").joinToString { it.text() }
        val tagGenres = article.select("span.tag-links a").joinToString { it.text() }
        genre = listOf(categoryGenres, tagGenres)
            .filter { it.isNotEmpty() }
            .joinToString()

        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        initialized = true
    }

    // ========================= Pages =========================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pages = client.get(getChapterUrl(chapter)).asJsoup().select(PAGE_SELECTOR)
            .mapIndexedNotNull { index, element ->
                val imageUrl = element.absUrl("data-mfp-src").ifBlank { element.absUrl("href") }
                if (imageUrl.isBlank()) {
                    null
                } else {
                    Page(index, imageUrl = imageUrl)
                }
            }

        if (pages.isEmpty()) {
            throw Exception("Page list not found")
        }

        return pages
    }

    private fun parseMangasPage(document: Document): MangasPage {
        val mangas = document.select(POST_SELECTOR).map(::mangaFromElement)
        val hasNextPage = document.selectFirst(NEXT_PAGE_SELECTOR) != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("a")?.absUrl("href")
            ?: throw Exception("Post URL not found")

        val parsedUrl = link.toHttpUrlOrNull() ?: throw Exception("Invalid post URL: $link")
        url = parsedUrl.encodedPath

        title = element.selectFirst("a")?.attr("title")
            ?.takeIf { it.isNotBlank() }
            ?: element.selectFirst("h1, h2, h3")?.text()?.takeIf { it.isNotBlank() }
            ?: throw Exception("Title is mandatory")

        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    private fun isMangaOrChapterPath(pathSegments: List<String>): Boolean {
        if (pathSegments.isEmpty()) return false
        if (pathSegments.first() in NON_POST_PATH_PREFIXES) return false

        return pathSegments.size >= 2
    }

    companion object {
        private const val POST_SELECTOR = "div.article-container article"
        private const val NEXT_PAGE_SELECTOR = "div.wp-pagenavi a.page.larger"
        private const val PAGE_SELECTOR = "a.msacwl-img-link"
        private val NON_POST_PATH_PREFIXES = setOf(
            "category",
            "tag",
            "search",
            "page",
        )
    }
}
