package eu.kanade.tachiyomi.extension.all.v2ph

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
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class V2ph : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 2, period = 1.seconds)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")

    // ============================== Popular ==============================
    override suspend fun getPopularManga(page: Int): MangasPage = albumList("$baseUrl/category/best-quality?page=$page")

    private suspend fun albumList(url: String): MangasPage {
        val document = client.get(url).asJsoup()
        val mangas = document.select(".albums-list .card").mapNotNull(::mangaFromElement)
        val hasNextPage = document.selectFirst("ul.pagination li.page-item a:contains(Next)") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/?page=$page").asJsoup()
        val mangas = document.select("#latest-albums-title ~ .albums-list .card").mapNotNull(::mangaFromElement)
        val hasNextPage = document.selectFirst("ul.pagination li.page-item a:contains(Next)") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Search ===============================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search/".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
            return albumList(url.toString())
        }

        val category = filters.firstInstanceOrNull<CategoryFilter>()?.toUriPart().orEmpty()
        val country = filters.firstInstanceOrNull<CountryFilter>()?.toUriPart().orEmpty()

        val url = when {
            category.isNotEmpty() -> "$baseUrl/category/$category?page=$page"
            country.isNotEmpty() -> "$baseUrl/country/$country?page=$page"
            else -> "$baseUrl/category/best-quality?page=$page"
        }

        return albumList(url)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "album") return null
        val response = client.get(url)
        response.checkPaywall()
        return mangaDetailsParse(response.asJsoup()).apply {
            this.url = response.request.url.encodedPath
        }
    }

    // ======================== Details and chapters ========================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get(baseUrl + manga.url)
        response.checkPaywall()
        val document = response.asJsoup()
        val dateStr = document.selectFirst("dl dt:contains(Date) + dd")?.text()

        val chapter = SChapter.create().apply {
            name = "Gallery"
            url = response.request.url.encodedPath
            date_upload = dateFormat.tryParseDate(dateStr, ZoneOffset.UTC)
        }
        return SMangaUpdate(mangaDetailsParse(document).apply { url = manga.url }, listOf(chapter))
    }

    private fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1")!!.text()
        author = document.selectFirst("dl dt:contains(Vendor) + dd a")?.text()
        artist = document.selectFirst("dl dt:contains(Model) + dd a")?.text()
        genre = document.select("dl dt:contains(Tags) + dd a").joinToString { it.text() }

        val photosCount = document.selectFirst("dl dt:contains(Photos) + dd")?.text()
        val intro = document.selectFirst(".album-intro")?.text()

        description = buildString {
            if (photosCount != null) {
                append("Photos: $photosCount\n\n")
            }
            intro?.let(::append)
        }.trim()

        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    // =============================== Pages ===============================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(baseUrl + chapter.url)
        response.checkPaywall()
        val document = response.asJsoup()

        val photosCount = document.selectFirst("dl dt:contains(Photos) + dd")?.text()?.toIntOrNull() ?: 0
        val isGuest = document.selectFirst("a[href*='/login'], a[href*='/register']") != null

        if (isGuest && photosCount > 20) {
            throw Exception("V2PH Session expired. Please log in via WebView to view more than 20 images.")
        }

        val maxPage = (photosCount + 9) / 10

        val pages = document.select(".photos-list img").mapIndexed { index, img ->
            Page(index, imageUrl = img.attr("abs:src"))
        }.toMutableList()

        for (i in 2..maxPage) {
            val pageUrl = "$baseUrl${chapter.url}${if (chapter.url.contains("?")) "&" else "?"}page=$i"
            client.get(pageUrl, ensureSuccess = false).use { pageResponse ->
                if (!pageResponse.isSuccessful) return@use
                val offset = pages.size
                pageResponse.asJsoup().select(".photos-list img").mapIndexedTo(pages) { index, img ->
                    Page(offset + index, imageUrl = img.attr("abs:src"))
                }
            }
        }

        return pages
    }

    // ============================== Filters ==============================
    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Note: Text Search ignores the filters below."),
        Filter.Header("If both Category and Country are set, Category takes precedence."),
        Filter.Separator(),
        CategoryFilter(),
        CountryFilter(),
    )

    // ============================= Utilities =============================
    private fun Response.checkPaywall() {
        if (request.url.encodedPath.startsWith("/user/")) {
            throw Exception("This album requires a V2PH premium account. Open in WebView to upgrade.")
        }
    }

    private fun mangaFromElement(element: Element): SManga? {
        val link = element.selectFirst("a.media-cover") ?: return null
        val titleEl = element.selectFirst(".card-body h6 a") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            title = titleEl.text()
            thumbnail_url = element.selectFirst(".card-cover img")?.attr("abs:src")
        }
    }

    companion object {
        private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    }
}
