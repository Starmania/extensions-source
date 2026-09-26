package eu.kanade.tachiyomi.extension.all.beauty3600000

import eu.kanade.tachiyomi.source.model.Filter
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
import keiyoushi.utils.firstInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Beauty3600000 : KeiSource() {

    override val supportsLatest = false

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = connectTimeout(120.seconds)
        .readTimeout(120.seconds)
        .rateLimit(1)

    private val searchingClient: OkHttpClient by lazy {
        client.newBuilder()
            .rateLimit(1, 30.seconds)
            .build()
    }

    private val apiUrl get() = baseUrl.toHttpUrl().newBuilder().addPathSegments(API_BASE)

    // ========================= Popular =========================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = apiUrl
            .addPathSegment("posts")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PER_PAGE.toString())
            .build()
        return parseMangasPage(client.get(url, headers))
    }

    // ========================= Latest =========================

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    // ========================= Search =========================

    override suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage {
        if (url.host != baseUrl.toHttpUrl().host) return MangasPage(emptyList(), false)

        val id = url.queryParameter("p")?.trim()
        val slug = if (id == null) url.pathSegments.lastOrNull { it.isNotBlank() }?.removeSuffix(".html") else null
        val postsUrl = apiUrl
            .addPathSegment("posts")
            .apply {
                if (id != null) {
                    id.toIntOrNull()?.let { addQueryParameter("include", it.toString()) }
                        // Allow copy old entry's `manga.url` to search for old entry for migration which is in format "https://3600000.xyz/?p=/{slug}/"
                        ?: addQueryParameter("slug", id.removeSurrounding("/"))
                } else if (slug != null) {
                    addQueryParameter("slug", slug)
                }
            }.build()
        return parseMangasPage(client.get(postsUrl, headers))
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val filterList = filters.ifEmpty { getFilterList() }
        val categoryFilter = filterList.firstInstance<CategoryFilter>()
        val tagFilter = filterList.firstInstance<TagFilter>()
        var tagSearch: Int? = null

        if (categoryFilter.state <= 0 && tagFilter.state <= 0) {
            if (query.isBlank()) {
                return getPopularManga(page)
            }

            val tags = runCatching { getTag(query.trim()) }.getOrNull()
            tagSearch = tags?.firstOrNull { it.name.equals(query.trim(), ignoreCase = true) }?.id
        }

        val textSearch = query.isNotBlank() && tagSearch == null
        val url = apiUrl
            .addPathSegment("posts")
            .apply {
                addQueryParameter("page", page.toString())
                addQueryParameter("per_page", PER_PAGE.toString())

                if (textSearch) {
                    addQueryParameter("search", query.trim())
                }

                when {
                    categoryFilter.state > 0 -> addQueryParameter("categories", categoryFilter.toUriPart())
                    tagFilter.state > 0 -> addQueryParameter("tags", tagFilter.toUriPart())
                    tagSearch != null -> addQueryParameter("tags", tagSearch.toString())
                }
            }.build()

        return parseMangasPage((if (textSearch) searchingClient else client).get(url, headers))
    }

    // ========================= Filters =========================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("NOTE: Only one filter will be applied!"),
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
        val post = fetchPost(manga)

        val details = if (fetchDetails) {
            post.toSManga().apply {
                genre = listOf("categories", "tags").parallelCatchingFlatMap { term ->
                    getTerms(post.id, term)
                }.takeIf { it.isNotEmpty() }
                    ?.joinToString { it.name }
            }
        } else {
            manga
        }

        val chapter = post.toSChapter().apply {
            date_upload = DATE_FORMAT.tryParse(post.date)
        }

        return SMangaUpdate(details, listOf(chapter))
    }

    private suspend fun fetchPost(manga: SManga): PostDto {
        val url = apiUrl.apply {
            addPathSegment("posts")
            manga.url.toIntOrNull()?.let { addPathSegment(manga.url) }
                ?: addQueryParameter("slug", manga.url.removeSurrounding("/"))
        }.build()
        return client.get(url, headers).toPost()
    }

    private suspend fun getTerms(mangaId: Int, term: String): List<TermDto> {
        val url = apiUrl
            .addPathSegment(term)
            .addQueryParameter("post", mangaId.toString())
            .build()
        return client.get(url, headers).parseAs<List<TermDto>>()
    }

    private suspend fun getTag(slug: String): List<TermDto> {
        val url = apiUrl
            .addPathSegment("tags")
            .addQueryParameter("slug", slug)
            .build()
        return client.get(url, headers).parseAs<List<TermDto>>()
    }

    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val mangaId = manga.url.toIntOrNull() ?: fetchPost(manga).id
        val tags = getTerms(mangaId, "tags")
            .sortedBy { it.name.startsWith('[') }

        return tags.parallelCatchingFlatMap { tag ->
            val url = apiUrl
                .addPathSegment("posts")
                .addQueryParameter("page", "1")
                .addQueryParameter("per_page", PER_PAGE.toString())
                .addQueryParameter("tags", tag.id.toString())
                .build()

            parseMangasPage(client.get(url, headers)).mangas
        }
    }

    override fun getMangaUrl(manga: SManga): String = manga.url.toIntOrNull()
        ?.let { "$baseUrl/?p=${manga.url}" }
        ?: "$baseUrl${manga.url}"

    private fun Response.toPost(): PostDto {
        val slugParam = request.url.queryParameter("slug")
        return if (slugParam != null) {
            val body = body.string()
            jsonArrayRegex.find(body)
                ?.value
                ?.parseAs<List<PostDto>>()
                ?.firstOrNull()
                ?: throw IllegalArgumentException("Post not found")
        } else {
            parseAs<PostDto>()
        }
    }

    // ========================= Chapters =========================

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/?p=${chapter.url}"

    // ========================= Pages =========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = apiUrl
            .addPathSegment("posts")
            .addPathSegment(chapter.url)
            .build()
        val post = client.get(url, headers).parseAs<PostDto>()
        val document = Jsoup.parseBodyFragment(post.content.rendered)
        return document.select("img").mapIndexed { i, it ->
            Page(i, imageUrl = it.attr("src"))
        }
    }

    // ========================= Helpers =========================

    private fun parseMangasPage(response: Response): MangasPage {
        val body = response.body.string()
        val posts = jsonArrayRegex.find(body)
            ?.value
            ?.parseAs<List<PostDto>>()
            ?: return MangasPage(emptyList(), false)
        val mangas = posts.map { it.toSManga() }
        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 0
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return MangasPage(mangas, currentPage < totalPages)
    }

    /**
     * Parallel implementation of [Iterable.flatMap], but running
     * the transformation function inside a try-catch block.
     */
    private suspend inline fun <A, B> Iterable<A>.parallelCatchingFlatMap(crossinline f: suspend (A) -> Iterable<B>): List<B> = withContext(Dispatchers.IO) {
        map {
            async {
                try {
                    f(it)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    companion object {
        private const val API_BASE = "wp-json/wp/v2"
        private const val PER_PAGE = 100
        private val jsonArrayRegex by lazy { Regex("""\[.*]\s*$""") }

        private val DATE_FORMAT by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }
}
