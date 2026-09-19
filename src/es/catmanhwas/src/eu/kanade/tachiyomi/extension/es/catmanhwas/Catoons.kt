package eu.kanade.tachiyomi.extension.es.catmanhwas

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
import keiyoushi.utils.applicationContext
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach
import kotlin.collections.map
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Catoons : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3, 1.seconds)

    override suspend fun getPopularManga(page: Int) = browse(page, sort = "popular")

    override suspend fun getLatestUpdates(page: Int) = browse(page, sort = "recent")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList) = browse(
        page,
        query,
        genre = filters.firstInstanceOrNull<GenreFilter>()?.selected,
        sort = filters.firstInstanceOrNull<OrderFilter>()?.selected,
    )

    private suspend fun browse(page: Int, query: String = "", genre: String? = null, sort: String? = null): MangasPage {
        val url = "$baseUrl/series/__data.json".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            genre?.let { addQueryParameter("genre", it) }
            sort?.let { addQueryParameter("sort", it) }
            if (query.isNotBlank()) {
                addQueryParameter("search", query)
            }
            addQueryParameter("x-sveltekit-invalidated", "001")
        }.build()

        val dataNode = client.get(url).parseAs<SvelteDataDto>().getDataNode()
        val data = decodeSvelte(dataNode).parseAs<BrowseDto>()
        return MangasPage(data.series.map { it.toSManga() }, data.hasNextPage())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "series") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null

        return fetchDetails(slug).apply { this.url = slug }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/series/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val details = async { if (fetchDetails) fetchDetails(manga.url) else manga }
        val chapterList = async { if (fetchChapters) fetchChapters(manga.url) else chapters }
        SMangaUpdate(details.await(), chapterList.await())
    }

    private suspend fun fetchDetails(slug: String): SManga {
        val document = client.get("$baseUrl/series/$slug").asJsoup()
        getRemoteChunks("$baseUrl/series/$slug")
        val details = getDetailsFromApi(slug)
        return SManga.create().apply {
            title = document.selectFirst("h1.font-bold")!!.text()
            thumbnail_url = document.selectFirst("div.mx-auto > div > img.object-cover")?.attr("abs:src")
            description = document.selectFirst("p.leading-relaxed")?.text()
            status = details.getStatus()
            genre = details.getGenres()
        }
    }

    private suspend fun getDetailsFromApi(slug: String): DetailsDto {
        val url = "$baseUrl/_app/remote/$detailsChunk/getSerieDetails".toHttpUrl().newBuilder()
            .addQueryParameter("payload", """["$slug"]""".toBase64())
            .build()

        val result = client.get(url).parseAs<SvelteResultDto>().getResult()
        return decodeSvelte(result.jsonArray).parseAs<DetailsDto>()
    }

    private suspend fun fetchChapters(slug: String): List<SChapter> {
        getRemoteChunks("$baseUrl/series/$slug")

        val chapters = mutableListOf<SChapter>()
        var page = 1
        do {
            val url = "$baseUrl/_app/remote/$chaptersChunk/getChapters".toHttpUrl().newBuilder()
                .addQueryParameter("payload", getChapterPayload(slug, page))
                .build()
            val result = client.get(url).parseAs<SvelteResultDto>().getResult()
            val data = decodeSvelte(result.jsonArray).parseAs<ChapterDataDto>()
            chapters += data.data.map { it.toSChapter(slug) }
            page = data.pagination.currentPage + 1
        } while (data.pagination.hasNextPage())

        return chapters
    }

    private fun getChapterPayload(slug: String, page: Int): String {
        val payload = """[["__skrao",1],{"page":2,"slug":3,"perPage":4},$page,"$slug",$CHAPTERS_PER_PAGE]"""
        return payload.toBase64()
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/series/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = "$baseUrl/series/${chapter.url}/__data.json?x-sveltekit-invalidated=001"
        val dataNode = client.get(url).parseAs<SvelteDataDto>().getDataNode()
        return decodeSvelte(dataNode).parseAs<PageListDto>().toPages()
    }

    override fun getFilterList(data: JsonElement?) = getFilters()

    private fun String.toBase64() = Base64.encodeToString(this.toByteArray(), Base64.DEFAULT)

    @Volatile
    private var detailsChunk: String? = null

    @Volatile
    private var chaptersChunk: String? = null

    @Synchronized
    private fun getRemoteChunks(seriesUrl: String) {
        if (detailsChunk != null && chaptersChunk != null) {
            return
        }

        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())

        var webView: WebView? = null

        handler.post {
            webView = WebView(applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.blockNetworkImage = true

                webViewClient = object : WebViewClient() {

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url.toString()

                        DETAILS_CHUNK_REGEX
                            .find(url)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.let { chunk ->
                                detailsChunk = chunk
                            }

                        CHAPTERS_CHUNK_REGEX
                            .find(url)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.let { chunk ->
                                chaptersChunk = chunk
                            }

                        if (detailsChunk != null && chaptersChunk != null) {
                            latch.countDown()
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }

                loadUrl(seriesUrl)
            }
        }

        latch.await(20, TimeUnit.SECONDS)

        handler.post {
            webView?.destroy()
        }
    }

    fun decodeSvelte(data: JsonArray): JsonElement = resolve(data, data[0])

    private fun dereference(data: JsonArray, index: Int): JsonElement = when (val value = data[index]) {
        is JsonArray, is JsonObject -> resolve(data, value)
        else -> value
    }

    private fun resolveReference(data: JsonArray, element: JsonElement): JsonElement {
        val index = (element as? JsonPrimitive)?.intOrNull

        return if (
            index != null &&
            !element.isString &&
            index in data.indices
        ) {
            dereference(data, index)
        } else {
            resolve(data, element)
        }
    }

    private fun resolve(data: JsonArray, element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map { resolveReference(data, it) })
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                put(key, resolveReference(data, value))
            }
        }
        else -> element
    }

    companion object {
        private val DETAILS_CHUNK_REGEX = """/_app/remote/([^/]+)/getSerieDetails""".toRegex()
        private val CHAPTERS_CHUNK_REGEX = """/_app/remote/([^/]+)/getChapters""".toRegex()
        private const val CHAPTERS_PER_PAGE = 100
    }
}
