package eu.kanade.tachiyomi.extension.es.codearc

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
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebViewBlocking
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import kotlin.time.Duration.Companion.seconds

@Source
abstract class CodeArc : KeiSource() {
    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        connectTimeout(15.seconds)
        readTimeout(30.seconds)
        rateLimit(1, 2.seconds) { it.host == baseUrlHost }
        rateLimit(1, 1.seconds) { it.host == "cdn.codearctraducciones.com" }
        addInterceptor(::readerAccessInterceptor)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/ranking?mode=popular&page=$page").asJsoup()

        val mangas = document.select("a.group.relative.min-w-0[href]").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                title = element.selectFirst("div.truncate.text-base")!!.text()
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
                    ?: element.selectFirst("img")?.attr("srcSet")?.split(",")
                        ?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
            }
        }

        val hasNextPage = page < POPULAR_MAX_PAGE && mangas.isNotEmpty() &&
            document.selectFirst("a[aria-label=Pagina siguiente]:not([disabled]), button[aria-label=Pagina siguiente]:not([disabled])") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList(client.get("$baseUrl/list?page=$page").asJsoup())

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("a.group.overflow-hidden[href]").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                title = element.selectFirst("div.line-clamp-2")?.text()
                    ?: element.attr("aria-label").takeIf { it.isNotEmpty() }!!
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
                    ?: element.selectFirst("img")?.attr("srcSet")?.split(",")
                        ?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
            }
        }

        val hasNextPage = mangas.isNotEmpty() &&
            document.selectFirst("a[aria-label=Pagina siguiente]:not([disabled]), button[aria-label=Pagina siguiente]:not([disabled])") != null

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotEmpty() && filters.none { it is UriPartFilter && it.state != 0 } &&
            filters.none { it is GenreGroup && it.state.any { genre -> genre.state } }
        ) {
            val url = "$baseUrl/api/mangas/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("limit", "50")
                .build()
            val result = client.get(url).parseAs<SearchResponseDto>()
            return MangasPage(result.items.map { it.toSManga(baseUrl) }, false)
        }

        val url = "$baseUrl/list".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotEmpty()) {
                addQueryParameter("q", query)
            }
            filters.forEach { filter ->
                when (filter) {
                    is ContentTypeFilter -> {
                        val part = filter.toUriPart()
                        if (part.isNotEmpty()) addQueryParameter("tipo", part)
                    }
                    is FormatFilter -> {
                        val part = filter.toUriPart()
                        if (part != "both") addQueryParameter("formato", part)
                    }
                    is SortFilter -> {
                        val part = filter.toUriPart()
                        if (part != "latest") addQueryParameter("sort", part)
                    }
                    is GenreGroup -> {
                        val genres = filter.state
                            .filter { it.state }
                            .joinToString(",") { it.slug }
                        if (genres.isNotEmpty()) {
                            addQueryParameter("generos", genres)
                        }
                    }
                    else -> {}
                }
            }
        }.build()
        return parseMangaList(client.get(url).asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrlHost || url.pathSegments.size != 1) return null
        val manga = SManga.create().apply { this.url = "/${url.pathSegments[0]}" }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    // Details and chapters both come from the manga page, so it is fetched once for either flag.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        return SMangaUpdate(parseMangaDetails(document), parseChapterList(document))
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1")!!.text().replace("Vista Previa", "")
        description = document.selectFirst("p.whitespace-pre-line")?.text()
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        genre = document.select("a[href*=/list?generos=]").joinToString { it.text() }

        val htmlArtists = document.select("a[href*=/creador/]").joinToString { it.text() }
        if (htmlArtists.isNotEmpty()) {
            artist = htmlArtists
            author = htmlArtists
        }

        val statusText = document.selectFirst("span.inline-flex:has(span.rounded-full)")
            ?.text()?.lowercase()
        status = when {
            statusText == null -> SManga.UNKNOWN
            statusText.contains("finalizado") -> SManga.COMPLETED
            statusText.contains("publicándose") || statusText.contains("publicandose") -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        val chapterLinks = document.select("a.group.block[href*=/reader/][href*=/cascade]")

        if (chapterLinks.isNotEmpty()) {
            return chapterLinks.map { element ->
                val href = element.absUrl("href")
                val chapterText = element.selectFirst("h3")?.text() ?: ""
                val chapterNum = CHAPTER_NUM_REGEX.find(href)?.groupValues?.get(1)

                SChapter.create().apply {
                    setUrlWithoutDomain(href)
                    name = chapterText.ifEmpty { "Chapter ${chapterNum ?: "1"}" }
                    chapter_number = chapterNum?.toFloatOrNull() ?: 0f
                }
            }
        }

        val singleChapterBtn = document.selectFirst("a[href*=/cascade]:has(span:contains(Leer))")
            ?: document.selectFirst("a[href*=/reader/][href*=/cascade]")

        if (singleChapterBtn != null) {
            return listOf(
                SChapter.create().apply {
                    setUrlWithoutDomain(singleChapterBtn.absUrl("href"))
                    name = "Chapter 1"
                    chapter_number = 1f
                },
            )
        }

        return emptyList()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (slug, number) = CHAPTER_URL_REGEX.find(chapter.url)!!.destructured
        val first = client.get(readerPagesUrl(slug, number, 0)).parseAs<ReaderPagesDto>()
        val pages = first.items.toMutableList()

        while (pages.size < first.total) {
            val newPages = client.get(readerPagesUrl(slug, number, pages.size))
                .parseAs<ReaderPagesDto>().items
            if (newPages.isEmpty()) break
            pages += newPages
        }

        return pages.mapIndexed { index, page ->
            Page(index, imageUrl = page.imagenUrl)
        }
    }

    private fun readerPagesUrl(slug: String, number: String, offset: Int): HttpUrl = "$baseUrl/api/mangas/reader-pages".toHttpUrl().newBuilder()
        .addQueryParameter("slug", slug)
        .addQueryParameter("capitulo", number)
        .addQueryParameter("mode", "cascade")
        .addQueryParameter("offset", offset.toString())
        .addQueryParameter("limit", PAGES_LIMIT.toString())
        .build()

    // The reader API answers 403 until the page's own script has passed a Cloudflare Turnstile
    // challenge and exchanged it for an access cookie, so let the real reader page do that.
    private fun readerAccessInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code != 403 || !request.url.encodedPath.endsWith("/reader-pages")) return response
        response.close()

        val slug = request.url.queryParameter("slug")!!
        val number = request.url.queryParameter("capitulo")!!
        val probeUrl = request.url.newBuilder().setQueryParameter("limit", "1").build()
        runWebViewBlocking<Unit>(chain.call(), 60.seconds) {
            jsBridge("codearc") { resolve(Unit) }
            poll(1.seconds) {
                evaluateJs("fetch('$probeUrl',{credentials:'same-origin'}).then(r=>{if(r.ok)codearc.post('ok')})")
            }
            loadUrl("$baseUrl/reader/$slug/$number/cascade")
        }
        return chain.proceed(request)
    }

    override fun getFilterList(data: JsonElement?): FilterList = getFilters()

    private companion object {
        const val POPULAR_MAX_PAGE = 5
        const val PAGES_LIMIT = 100
        val CHAPTER_NUM_REGEX = """/reader/[^/]+/(\d+)/""".toRegex()
        val CHAPTER_URL_REGEX = """/reader/([^/]+)/([^/]+)/""".toRegex()
    }
}
