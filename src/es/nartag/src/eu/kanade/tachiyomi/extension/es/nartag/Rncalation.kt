package eu.kanade.tachiyomi.extension.es.nartag

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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Rncalation : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2, 1.seconds)
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            // Without a valid mv_ck cookie the site answers 200 with a JS page that sets it and
            // reloads. The cookie is already in the jar by now, so replaying the request is enough.
            if (response.isChallenge()) {
                response.close()
                chain.proceed(request)
            } else {
                response
            }
        }

    private fun Response.isChallenge() = header("Content-Type")?.startsWith("text/html") == true &&
        peekBody(4096).string().contains("mv-verifying")

    override suspend fun getPopularManga(page: Int): MangasPage = parseLibrary(client.get("$baseUrl/library?sort=views&page=$page"))

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseLibrary(client.get("$baseUrl/library?sort=updated&page=$page"))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/library".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotEmpty()) {
                addQueryParameter("q", query)
            }
            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> {
                        addQueryParameter("sort", sortOptions[filter.state].value)
                    }
                    is TypeFilter -> {
                        if (filter.state > 0) {
                            addQueryParameter("type", filter.values[filter.state])
                        }
                    }
                    is StatusFilter -> {
                        if (filter.state > 0) {
                            addQueryParameter("status", filter.values[filter.state])
                        }
                    }
                    is GenreFilter -> {
                        if (filter.state > 0) {
                            addQueryParameter("genre", filter.values[filter.state])
                        }
                    }
                    else -> {}
                }
            }
        }.build()
        return parseLibrary(client.get(url))
    }

    private fun parseLibrary(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".lib-grid a.comic-card").mapNotNull { element ->
            val type = element.selectFirst("span.absolute.top-2.left-2")?.text()
            if (type != null && type.contains("Novel", ignoreCase = true)) {
                return@mapNotNull null
            }
            SManga.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                title = element.selectFirst("p.leading-snug")!!.text()
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.selectFirst("a.lib-page-btn--nav:last-child") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() != "comics") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null

        val document = client.get("$baseUrl/comics/$slug").asJsoup()
        return mangaDetailsParse(document).apply {
            this.url = "/comics/$slug"
            title = document.selectFirst("h1")!!.text()
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = async {
            if (fetchDetails) mangaDetailsParse(client.get(getMangaUrl(manga)).asJsoup()) else manga
        }
        val chaptersDeferred = async {
            if (fetchChapters) fetchChapterList(manga) else chapters
        }
        SMangaUpdate(detailsDeferred.await(), chaptersDeferred.await())
    }

    private fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        description = document.selectFirst("div.comic-page-wrap p[class*=text-][^data-astro-cid]")?.text() ?: ""

        val badges = document.select("span.inline-flex.items-center.rounded").map { it.text().lowercase() }
        status = when {
            badges.any { it.contains("emisión") || it.contains("curso") || it.contains("ongoing") } -> SManga.ONGOING
            badges.any { it.contains("completado") || it.contains("completed") } -> SManga.COMPLETED
            badges.any { it.contains("pausa") || it.contains("hiatus") } -> SManga.ON_HIATUS
            badges.any { it.contains("cancelado") || it.contains("cancelled") } -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        genre = document.select("span.inline-flex.items-center.rounded")
            .filter { it.text().lowercase() !in listOf("emisión", "completado", "pausa", "cancelado") }
            .joinToString(", ") { it.text() }

        val groupName = document.selectFirst("a[href^='/groups/']")?.text()
        if (!groupName.isNullOrEmpty()) {
            author = groupName
            artist = groupName
        }

        document.select(".flex.items-baseline.justify-between.gap-2").forEach { row ->
            val label = row.selectFirst("span.text-\\[var\\(--color-text3\\)\\]")?.text()
            val value = row.selectFirst("span.text-\\[var\\(--color-text2\\)\\]")?.text()
            if (label == "Autor") author = value
            if (label == "Arte") artist = value
        }
    }

    private suspend fun fetchChapterList(manga: SManga): List<SChapter> {
        val slug = manga.url.removeSuffix("/").substringAfterLast("/")
        val allChapters = mutableListOf<SChapter>()
        var page = 1
        do {
            val response = client.get("$baseUrl/comics/$slug/chapters?page=$page")
            val chapters = chapterListParse(response.asJsoup())
            if (chapters.isEmpty()) break
            allChapters.addAll(chapters)

            val currentPage = response.header("x-page")?.toIntOrNull() ?: break
            val totalPages = response.header("x-pages")?.toIntOrNull() ?: break

            page++
        } while (currentPage < totalPages)

        return allChapters
    }

    private fun chapterListParse(document: Document): List<SChapter> = document.select("a[data-chapter-id]").mapIndexed { num, it ->
        SChapter.create().apply {
            setUrlWithoutDomain(it.attr("href"))
            chapter_number = it.attr("data-chapter-num").toFloatOrNull() ?: num.toFloat()
            name = it.attr("data-chapter-label").trim().ifEmpty { "Capítulo ${chapter_number.toInt()}" }
            date_upload = it.selectFirst(".text-\\[0\\.65rem\\]")?.let { parseDate(it.text()) } ?: 0L
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return document.select("img.page-img, .page-wrap img").mapIndexed { index, element ->
            val imageUrl = element.attr("abs:data-src").ifEmpty { element.attr("abs:src") }
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(genresList),
    )
}
