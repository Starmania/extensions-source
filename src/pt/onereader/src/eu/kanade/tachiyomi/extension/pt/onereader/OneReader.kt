package eu.kanade.tachiyomi.extension.pt.onereader

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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.string
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import java.io.IOException
import okio.Source as OkioSource

@Source
abstract class OneReader : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(3)
        addInterceptor(::protectedPageInterceptor)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = catalog(page, sort = "VIEWS")

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = apiUrl("home", "updates").newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("filter", "all")
            .build()

        return client.get(url).parseAs<HomeUpdatesDto>().toMangasPage()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = catalog(
        page = page,
        sort = filters.firstInstanceOrNull<OrderFilter>()?.selected() ?: "AZ",
        query = query.trim(),
        format = filters.firstInstanceOrNull<TypeFilter>()?.selected().orEmpty(),
        status = filters.firstInstanceOrNull<StatusFilter>()?.selected().orEmpty(),
        genre = filters.firstInstanceOrNull<TagFilter>()?.selected().orEmpty(),
    )

    private suspend fun catalog(
        page: Int,
        sort: String,
        query: String = "",
        format: String = "",
        status: String = "",
        genre: String = "",
    ): MangasPage {
        val url = apiUrl("catalog", "page").newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("sort", sort)
            .apply {
                if (query.isNotEmpty()) addQueryParameter("q", query)
                if (format.isNotEmpty()) addQueryParameter("format", format)
                if (status.isNotEmpty()) addQueryParameter("status", status)
                if (genre.isNotEmpty()) addQueryParameter("genre", genre)
            }
            .build()

        return client.get(url).parseAs<CatalogDto>().toMangasPage()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val id = url.queryParameter("id") ?: return null

        return client.get(apiUrl("works", id)).parseAs<WorkDetailsDto>().toSManga()
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/obra".toHttpUrl().newBuilder()
        .addQueryParameter("id", manga.url)
        .build()
        .toString()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // One endpoint returns both the work and its chapter list.
        val details = client.get(apiUrl("works", manga.url)).parseAs<WorkDetailsDto>()

        return SMangaUpdate(
            manga = if (fetchDetails) details.toSManga() else manga,
            chapters = if (fetchChapters) details.toSChapters() else chapters,
        )
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/leitor".toHttpUrl().newBuilder()
        .addQueryParameter("id", chapter.memo["id"]!!.string)
        .addQueryParameter("cap", chapter.memo["number"]!!.string)
        .build()
        .toString()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.memo["id"]?.string ?: throw Exception("Atualize a lista de capítulos")
        val number = chapter.memo["number"]!!.string

        return client.get(apiUrl("works", id, "chapters", number))
            .parseAs<ChapterManifestDto>()
            .toPages(baseUrl.toHttpUrl())
    }

    // Page URLs answer with a short-lived link to an "ORX3" envelope: 4 magic bytes, a big-endian
    // count, then the image with its first `count` bytes XORed by a per-request key. The grant in
    // the page URL is tied to the `or.reader` cookie set by the chapter manifest request.
    private fun protectedPageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.contains("/pages/") || request.url.host != baseUrl.toHttpUrl().host) {
            return chain.proceed(request)
        }

        val media = chain.proceed(
            request.newBuilder().header("Accept", "application/vnd.onereader.media+json").build(),
        ).parseAs<MediaDto>()
        if (media.mode != "xor-prefix-v3") throw IOException("Modo de proteção não suportado: ${media.mode}")

        val response = chain.proceed(Request.Builder().url(media.url).headers(request.headers).build())
        if (!response.isSuccessful) return response

        val source = response.body.source()
        if (source.readUtf8(4) != "ORX3") throw IOException("Envelope de imagem inválido")
        val count = source.readInt().toLong()
        val prefix = if (source.request(count)) source.readByteArray(count) else source.readByteArray()
        val key = media.key()
        for (i in prefix.indices) {
            prefix[i] = (prefix[i].toInt() xor key[i % key.size].toInt()).toByte()
        }

        val mediaType = media.mediaType()
        val body = PrefixedSource(Buffer().write(prefix), source).buffer().asResponseBody(mediaType, -1)

        return response.newBuilder()
            .header("Content-Type", mediaType.toString())
            .body(body)
            .build()
    }

    private class PrefixedSource(private val prefix: Buffer, private val rest: BufferedSource) : OkioSource by rest {
        override fun read(sink: Buffer, byteCount: Long): Long = if (prefix.size > 0) prefix.read(sink, byteCount) else rest.read(sink, byteCount)
    }

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get(apiUrl("catalog", "meta"))
        .parseAs<CatalogMetaDto>()
        .toFilterData()
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterData>()

        return FilterList(
            buildList {
                add(OrderFilter())
                add(StatusFilter())
                add(TypeFilter())
                if (filterData != null) add(TagFilter(filterData.tags))
            },
        )
    }

    private fun apiUrl(vararg pathSegments: String): HttpUrl = "$baseUrl/api/reader".toHttpUrl().newBuilder()
        .apply { pathSegments.forEach { addPathSegment(it) } }
        .build()

    companion object {
        private const val PAGE_SIZE = 24
    }
}
