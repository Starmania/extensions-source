package eu.kanade.tachiyomi.extension.all.everiaclubcom

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

@Source
abstract class EveriaClubCom : KeiSource() {

    private val Element.imgSrc: String?
        get() = when {
            hasAttr("data-original") -> attr("data-original")
            hasAttr("data-lazy-src") -> attr("data-lazy-src")
            hasAttr("data-src") -> attr("data-src")
            hasAttr("src") -> attr("src")
            else -> null
        }

    private fun mangaFromElement(it: Element) = SManga.create().apply {
        setUrlWithoutDomain(it.attr("abs:href").removePrefix(baseUrl))
        with(it.selectFirst("img")!!) {
            thumbnail_url = imgSrc
            title = attr("title")
        }
    }

    private fun mangaListParse(document: Document): MangasPage {
        val mangas = document.select(".mainleft .leftp > a").map {
            mangaFromElement(it)
        }
        val isLastPage = document.selectFirst("li:has(span.current) + li > a")
        return MangasPage(mangas, isLastPage != null)
    }

    // Latest
    override suspend fun getLatestUpdates(page: Int): MangasPage = mangaListParse(client.get("$baseUrl/?page=$page").asJsoup())

    // Popular
    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get(baseUrl).asJsoup()
        val mangas = document.select(".mainright li a").map {
            mangaFromElement(it)
        }
        return MangasPage(mangas, false)
    }

    // Search
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val tag = filters.firstInstanceOrNull<TagFilter>()?.state.orEmpty()
        val categoryFilter = filters.firstInstanceOrNull<CategoryFilter>()
        val url = when {
            tag.isNotBlank() -> baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("tags")
                .addPathSegment(tag)
                .addPathSegment(page.toString())

            categoryFilter != null && categoryFilter.state != 0 -> "$baseUrl/${categoryFilter.toUriPart()}?page=$page".toHttpUrl().newBuilder()

            query.isNotBlank() -> baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("search")
                .addPathSegment("")
                .addQueryParameter("keyword", query)
                .addQueryParameter("page", page.toString())

            else -> "$baseUrl/?page=$page".toHttpUrl().newBuilder()
        }
        return mangaListParse(client.get(url.build()).asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || !url.encodedPath.startsWith("/kk")) return null
        val document = client.get(url).asJsoup()
        return SManga.create().apply {
            setUrlWithoutDomain(url.toString())
            title = document.selectFirst("h1")!!.text()
            thumbnail_url = document.selectFirst(".mainleft img")?.imgSrc
            detailsParse(document)
        }
    }

    // Details
    private fun SManga.detailsParse(document: Document) {
        genre = document.select("div.end span:contains(Tags:) ~ a > p.tags").joinToString {
            it.ownText()
        }
        status = SManga.COMPLETED
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = if (fetchDetails) {
            val document = client.get(getMangaUrl(manga)).asJsoup()
            SManga.create().apply {
                url = manga.url
                detailsParse(document)
            }
        } else {
            manga
        }
        val chapter = SChapter.create().apply {
            url = manga.url
            name = "Gallery"
            chapter_number = 1f
            date_upload = 0L
        }
        return SMangaUpdate(details, listOf(chapter))
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val images = document.select(".mainleft img")
        return images.mapIndexed { index, image ->
            Page(index, imageUrl = image.imgSrc)
        }
    }

    // Filters
    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("NOTE: Only one filter will be applied!"),
        Filter.Separator(),
        TagFilter(),
        CategoryFilter(),
    )

    open class UriPartFilter(
        displayName: String,
        private val valuePair: Array<Pair<String, String>>,
    ) : Filter.Select<String>(displayName, valuePair.map { it.first }.toTypedArray()) {
        fun toUriPart() = valuePair[state].second
    }

    class CategoryFilter :
        UriPartFilter(
            "Category",
            arrayOf(
                Pair("Any", ""),
                Pair("Gravure", "Gravure.html"),
                Pair("Japan", "Japan.html"),
                Pair("Korea", "Korea.html"),
                Pair("Thailand", "Thailand.html"),
                Pair("Chinese", "Chinese.html"),
                Pair("Cosplay", "Cosplay.html"),
            ),
        )

    class TagFilter : Filter.Text("Tag")
}
