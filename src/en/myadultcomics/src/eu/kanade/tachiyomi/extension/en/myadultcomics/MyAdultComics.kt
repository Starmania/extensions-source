package eu.kanade.tachiyomi.extension.en.myadultcomics

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
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

@Source
abstract class MyAdultComics : KeiSource() {

    override val supportsLatest = false

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage = client.get("$baseUrl/index.php?page=$page").asJsoup().mangasPage()

    private fun Document.mangasPage(): MangasPage {
        val mangas = select("td.list_container").mapNotNull { element ->
            val link = element.selectFirst("p.text_container > a") ?: return@mapNotNull null
            val href = link.absUrl("href")

            if (href.isEmpty() || link.text().isEmpty()) {
                return@mapNotNull null
            }

            val image = element.selectFirst("img.fon_pic_img")

            SManga.create().apply {
                setUrlWithoutDomain(href)
                title = link.text()
                thumbnail_url = image?.absUrl("src")
            }
        }
        val hasNextPage = selectFirst("td.tbl_page a:contains(>>)") != null

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    // ============================== Search ===============================

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Use the text search field along with the type below."),
        Filters(),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchType = filters.firstInstanceOrNull<Filters>()?.selectedValue() ?: "title"

        val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("index.php")

        if (searchType == "title") {
            if (query.isNotBlank()) {
                url.addQueryParameter("name", query)
            }
            url.addQueryParameter("search", "yes")
        } else {
            url.addQueryParameter("search", query)
            url.addQueryParameter("sort", searchType)
        }
        url.addQueryParameter("page", page.toString())

        return client.get(url.build()).asJsoup().mangasPage()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.encodedPath != "/read.php") return null
        val id = url.queryParameter("i") ?: return null

        val path = "/read.php?i=$id"
        return client.get(baseUrl + path).asJsoup().mangaDetails().apply { this.url = path }
    }

    // ============================== Details ==============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val chapter = SChapter.create().apply {
            url = manga.url
            name = "Gallery"
            date_upload = 0L
        }
        // The single chapter is the gallery itself, so only details need a request.
        val details = if (fetchDetails) client.get(baseUrl + manga.url).asJsoup().mangaDetails() else manga

        return SMangaUpdate(details, listOf(chapter))
    }

    private fun Document.mangaDetails() = SManga.create().apply {
        title = selectFirst("h1#TOP")!!.text()
        genre = select("p.text_info_book:contains(Tags:) a").joinToString { it.text() }
        artist = select("p.text_info_book:contains(Artists:) a").joinToString { it.text() }
        author = artist
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    // =============================== Pages ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url).asJsoup()
        val script = document.selectFirst("script:containsData(let template)")?.data() ?: return emptyList()

        val regex = Regex("""src=["'](books/[^"']+)["']""")
        return regex.findAll(script).mapIndexed { index, matchResult ->
            Page(index, imageUrl = "$baseUrl/${matchResult.groupValues[1]}")
        }.toList()
    }
}
