package eu.kanade.tachiyomi.extension.pt.taimumangas

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
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Source
abstract class TaimuMangas : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("Accept", "application/json")

    override suspend fun getPopularManga(page: Int): MangasPage = getLibrary(page, sort = "rating")

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$API_BASE_URL/updates".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PAGE_SIZE.toString())
            .addQueryParameter("adult_mode", "true")
            .build()

        val result = client.get(url).parseAs<UpdatesResponse>()
        return MangasPage(result.items.map { it.toSManga() }, result.hasMore)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = getLibrary(
        page = page,
        query = query.takeIf(String::isNotBlank),
        filters = filters,
    )

    override fun getFilterList(data: JsonElement?): FilterList = getFilters()

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null

        val identifier = url.pathSegments.getOrNull(1) ?: return null
        val seriesIdentifier = when (url.pathSegments.first()) {
            "series" -> identifier
            "reader" -> fetchChapter(identifier).series?.identifier ?: return null
            else -> return null
        }

        return fetchMangaDetails(seriesIdentifier)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val identifier = extractIdentifier(manga.url)
        val mangaDeferred = async { if (fetchDetails) fetchMangaDetails(identifier) else manga }
        val chaptersDeferred = async { if (fetchChapters) fetchChapterList(identifier) else chapters }
        SMangaUpdate(mangaDeferred.await(), chaptersDeferred.await())
    }

    private suspend fun fetchMangaDetails(identifier: String): SManga = client.get("$API_BASE_URL/series/$identifier").parseAs<SeriesDetail>().toSManga()

    private suspend fun fetchChapterList(identifier: String): List<SChapter> {
        val chapters = mutableListOf<ChapterSummary>()
        var page = 1

        do {
            val url = "$API_BASE_URL/series/$identifier/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", CHAPTER_PAGE_SIZE.toString())
                .addQueryParameter("order", "desc")
                .build()

            val result = client.get(url).parseAs<ChapterListResponse>()
            chapters += result.items
            page = result.page + 1
        } while (result.hasMore)

        return chapters.map { it.toSChapter() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = fetchChapter(extractIdentifier(chapter.url))
        .pages
        .sortedBy { it.number }
        .mapIndexed { index, page -> page.toPage(index) }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${extractIdentifier(manga.url)}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/reader/${extractIdentifier(chapter.url)}"

    private suspend fun fetchChapter(identifier: String): ChapterDetailResponse {
        val url = "$API_BASE_URL/chapters/$identifier".toHttpUrl().newBuilder()
            .addQueryParameter("adult", "true")
            .build()

        return client.get(url).parseAs<ChapterDetailResponse>()
    }

    private suspend fun getLibrary(
        page: Int,
        query: String? = null,
        filters: FilterList = FilterList(),
        sort: String? = null,
    ): MangasPage {
        val url = "$API_BASE_URL/library".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PAGE_SIZE.toString())
            .addQueryParameter("adult", "true")

        if (!query.isNullOrBlank()) {
            url.addQueryParameter("q", query)
        }

        if (!sort.isNullOrBlank()) {
            url.addQueryParameter("sort", sort)
            url.addQueryParameter("order", "desc")
        }

        filters.forEach { filter ->
            when (filter) {
                is SelectFilter -> filter.selectedValue().takeIf(String::isNotBlank)?.let {
                    url.addQueryParameter(filter.queryName, it)
                }
                is GenreFilter -> {
                    val includedGenres = filter.includedGenreSlugs()

                    if (includedGenres.isNotEmpty()) {
                        url.addQueryParameter("genres", includedGenres.joinToString(","))
                    }
                }
                else -> {}
            }
        }

        val result = client.get(url.build()).parseAs<LibraryResponse>()
        return MangasPage(result.items.map { it.toSManga() }, result.hasNextPage)
    }

    private fun extractIdentifier(url: String): String = url.trimEnd('/').substringAfterLast('/')

    companion object {
        private const val API_BASE_URL = "https://api.taimumangas.com/api/v1/reader"
        private const val PAGE_SIZE = 24
        private const val CHAPTER_PAGE_SIZE = 100
    }
}
