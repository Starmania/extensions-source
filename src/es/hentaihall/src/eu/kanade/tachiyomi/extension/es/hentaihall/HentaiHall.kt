package eu.kanade.tachiyomi.extension.es.hentaihall

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Source
abstract class HentaiHall : KeiSource() {

    private val apiUrl = "https://hentaihallbackend-production.up.railway.app"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = dispatcher(
        Dispatcher().apply {
            maxRequests = 20
            maxRequestsPerHost = 10
        },
    )

    private val apiHeaders by lazy {
        headersBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .build()
    }

    // ============================== Popular ===============================
    override suspend fun getPopularManga(page: Int): MangasPage = getLibrary("$apiUrl/manhwa/library?buscar=&quebusca=nombre&order_item=seguir&order_dir=desc&page=${page - 1}&generes=".toHttpUrl())

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): MangasPage = getLibrary("$apiUrl/manhwa/library?buscar=&quebusca=nombre&order_item=creacion&order_dir=desc&page=${page - 1}&generes=".toHttpUrl())

    // =============================== Search ===============================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/manhwa/library".toHttpUrl().newBuilder().apply {
            addQueryParameter("buscar", query)
            addQueryParameter("page", (page - 1).toString())

            val searchBy = filters.firstInstanceOrNull<SearchByFilter>()?.selectedValue() ?: "nombre"
            val sortFilter = filters.firstInstanceOrNull<SortFilter>()
            val sortBy = sortFilter?.selectedValue() ?: "seguir"
            val sortDir = if (sortFilter?.state?.ascending == true) "asc" else "desc"
            val genres = filters.firstInstanceOrNull<GenreFilterGroup>()?.state
                ?.filter { it.state }
                ?.joinToString("_") { it.name }
                ?: ""

            addQueryParameter("quebusca", searchBy)
            addQueryParameter("order_item", sortBy)
            addQueryParameter("order_dir", sortDir)
            addQueryParameter("generes", genres)
        }.build()

        return getLibrary(url)
    }

    private suspend fun getLibrary(url: HttpUrl): MangasPage {
        val result = client.get(url, apiHeaders).parseAs<PageDto>()
        return MangasPage(result.data.map { it.toSManga() }, result.next)
    }

    override fun getFilterList(data: JsonElement?) = getFilters()

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() !in listOf("content", "reader")) return null
        val id = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null

        return getDetails(id).toSManga().apply { initialized = true }
    }

    // ====================== Manga Details & Chapters ======================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Because HentaiHall functions essentially as a one-shot source without unique chapter entries,
        // we generate the only existing chapter object strictly out of the same Details JSON data.
        val details = getDetails(manga.url)
        return SMangaUpdate(details.toSManga(), listOf(details.toSChapter()))
    }

    private suspend fun getDetails(id: String): DetailsDto = client.get("$apiUrl/manhwa/see/$id", apiHeaders).parseAs()

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/content/${manga.url}"

    // =============================== Pages ================================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val data = client.get("$apiUrl/manhwa/chapter/${chapter.url}", apiHeaders).parseAs<ChapterDto>()
        return data.chapter.filter { it.isNotBlank() }.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/reader/${chapter.url}"

    override fun imageRequest(page: Page): Request {
        // Requesting '*/*' disables the CDN's WebP compression and serves the original highest quality JPEGs
        val imageHeaders = headersBuilder()
            .set("Accept", "*/*")
            .build()

        return Request.Builder()
            .url(page.imageUrl!!)
            .headers(imageHeaders)
            .build()
    }
}
