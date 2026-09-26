package eu.kanade.tachiyomi.extension.all.cosplaytele

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
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class CosplayTele : KeiSource() {

    private val popularPageLimit = 20

    // ========================= Popular =========================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/wp-json/wordpress-popular-posts/v1/popular-posts".toHttpUrl().newBuilder()
            .addQueryParameter("offset", (page * popularPageLimit).toString())
            .addQueryParameter("limit", popularPageLimit.toString())
            .addQueryParameter("range", "last7days")
            .addQueryParameter("embed", "true")
            .addQueryParameter("_embed", "wp:featuredmedia")
            .addQueryParameter("_fields", "title,link,_embedded,_links.wp:featuredmedia")
            .build()
        val result = client.get(url).parseAs<List<PopularPostDto>>()
        val mangas = result.map { item ->
            SManga.create().apply {
                title = item.title.rendered
                setUrlWithoutDomain(item.link)
                thumbnail_url = item.embedded?.featuredMedia?.getOrNull(0)?.sourceUrl
            }
        }
        return MangasPage(mangas, mangas.size >= popularPageLimit)
    }

    // ========================= Latest =========================

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangasPage(client.get("$baseUrl/page/$page/"))

    // ========================= Search =========================

    override suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage {
        val pathSegments = url.pathSegments.filter { it.isNotEmpty() }
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host || pathSegments.isEmpty()) {
            return MangasPage(emptyList(), false)
        }

        if (pathSegments[0] == "category" || pathSegments[0] == "tag") {
            val paginatedUrl = url.newBuilder().apply {
                val pageIndex = url.pathSegments.indexOf("page")
                if (pageIndex != -1) {
                    setPathSegment(pageIndex + 1, page.toString())
                } else {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                }
            }.build()
            return parseMangasPage(client.get(paginatedUrl))
        }

        val manga = parseMangaDetails(client.get(url).asJsoup()).apply {
            setUrlWithoutDomain(url.toString())
        }
        return MangasPage(listOf(manga), false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val categoryFilter = filters.firstInstanceOrNull<UriPartFilter>()

        val url = when {
            categoryFilter != null && categoryFilter.state != 0 -> baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegments(categoryFilter.toUriPart())
                addPathSegment("page")
                addPathSegment(page.toString())
                if (query.isNotEmpty()) {
                    addQueryParameter("s", query)
                }
            }.build()
            query.isNotEmpty() -> "$baseUrl/page/$page/".toHttpUrl().newBuilder()
                .addQueryParameter("s", query)
                .build()
            else -> return getLatestUpdates(page)
        }
        return parseMangasPage(client.get(url))
    }

    private fun parseMangasPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("main div.box").map { element ->
            SManga.create().apply {
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
                val linkEl = element.selectFirst("h5 a") ?: throw Exception("Title is mandatory")
                title = linkEl.text()
                setUrlWithoutDomain(linkEl.attr("abs:href"))
            }
        }
        val hasNextPage = document.selectFirst(".next.page-number") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ========================= Details =========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get(getMangaUrl(manga))
        val document = response.asJsoup()
        val chapter = SChapter.create().apply {
            name = "Gallery"
            url = response.request.url.encodedPath
            date_upload = document.selectFirst("time.updated")?.attr("datetime")?.let { getDate(it) } ?: 0L
        }
        return SMangaUpdate(parseMangaDetails(document), listOf(chapter))
    }

    private fun parseMangaDetails(document: Element): SManga = SManga.create().apply {
        title = document.selectFirst(".entry-title")?.text() ?: throw Exception("Title is mandatory")
        description = title
        genre = getTags(document).joinToString(", ") { it.first }
        status = SManga.COMPLETED
    }

    private fun getTags(document: Element): List<Pair<String, String>> = document.select("#main a").filter { a ->
        TAG_PATTERN.matches(a.attr("abs:href"))
    }.map { a ->
        a.text() to a.attr("abs:href").substringAfter(baseUrl).removePrefix("/")
    }

    private fun getDate(dateStr: String): Long = DATE_FORMAT.tryParse(dateStr.substringBefore("T"))

    // ========================= Pages =========================

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter)).asJsoup()
        .select(".gallery-item img").mapIndexed { i, element ->
            Page(i, imageUrl = element.attr("abs:src"))
        }

    // ========================= Filters =========================

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/explore-categories/").asJsoup()
        return getTags(document).filter { it.first.isNotEmpty() }.toMap().toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val categories = CATEGORIES.toMap() + (data?.parseAs<Map<String, String>>() ?: emptyMap())
        return FilterList(
            Filter.Header("NOTE: Only one filter will be applied!"),
            Filter.Separator(),
            UriPartFilter("Category", categories.toList().toTypedArray()),
        )
    }

    companion object {
        private val DATE_FORMAT by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        }

        private val TAG_PATTERN = """.*/(tag|category)/.*""".toRegex()
    }
}
