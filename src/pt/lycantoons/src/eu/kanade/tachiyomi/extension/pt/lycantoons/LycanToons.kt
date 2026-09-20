package eu.kanade.tachiyomi.extension.pt.lycantoons

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response

@Source
abstract class LycanToons : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = addInterceptor(WebViewInterceptor(baseUrl, headers["User-Agent"]))
        .rateLimit(2)

    // =====================Popular=====================

    override suspend fun getPopularManga(page: Int): MangasPage = client.get("$baseUrl/api/metrics/popular?limit=$PAGE_LIMIT&page=$page")
        .parseAs<PopularResponse>()
        .toMangasPage()

    // =====================Latest=====================

    override suspend fun getLatestUpdates(page: Int): MangasPage = client.get("$baseUrl/api/metrics/recently-updated?limit=$PAGE_LIMIT&page=$page")
        .parseAs<PopularResponse>()
        .toMangasPage()

    // =====================Search=====================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        var search = query
        val tags = filters.selectedTags().toMutableList()

        val genreEntry = tagMapping.entries.find { it.value.equals(query, ignoreCase = true) }
        if (genreEntry != null) {
            tags.add(genreEntry.key)
            search = ""
        }

        val payload = SearchRequestBody(
            limit = PAGE_LIMIT,
            page = page,
            search = search,
            seriesType = filters.valueOrEmpty<SeriesTypeFilter>(),
            status = filters.valueOrEmpty<StatusFilter>(),
            tags = tags.distinct(),
        )

        return client.post("$baseUrl/api/series", headers, payload.toJsonRequestBody())
            .parseAs<SearchResponse>()
            .toMangasPage()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "series") return null
        val slug = url.pathSegments.getOrNull(1) ?: return null

        return getRsc("$baseUrl/series/$slug").extractNextJs<SeriesDto>()!!.toSManga()
    }

    override fun getFilterList(data: JsonElement?): FilterList = LycanToonsFilters.get()

    // =====================Details=====================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.slug()

        val details = if (fetchDetails) {
            getRsc("$baseUrl/series/$slug").extractNextJs<SeriesDto>()!!.toSManga()
        } else {
            manga
        }

        val chapterList = if (fetchChapters) {
            getRsc("$baseUrl/series/$slug/1").extractNextJs<ChapterResponse>()!!.capitulos
                .map { it.toSChapter(slug) }
                .sortedByDescending { it.chapter_number }
        } else {
            chapters
        }

        return SMangaUpdate(details, chapterList)
    }

    // =====================Pages========================

    // The chapter page no longer embeds its images (`initialPages` is null); the reader loads them
    // from this API using the chapter id found in the page.
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = getRsc("$baseUrl${chapter.url}").extractNextJs<ChapterRefDto>()!!.capituloId

        return client.get("$baseUrl/api/chapters/$chapterId/view-pages")
            .parseAs<PageList>()
            .pages
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    // =====================Utils=====================

    private fun SManga.slug(): String = url.substringBefore("?").substringAfterLast("/")

    private fun String.rscBust() = "$this?_rsc=${List(5) { BASE36.random() }.joinToString("")}"

    private suspend fun getRsc(url: String): Response {
        val rscHeaders = headers.newBuilder()
            .add("next-router-state-tree", NEXT_ROUTER)
            .add("next-url", url.removePrefix(baseUrl))
            .add("RSC", "1")
            .build()

        return client.get(url.substringBefore("?").rscBust(), rscHeaders)
    }

    companion object {
        private const val PAGE_LIMIT = 20
        private const val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"
        private const val NEXT_ROUTER = "%5B%22%22%2C%7B%22children%22%3A%5B%22__PAGE__%22%2C%7B%7D%2Cnull%2Cnull%5D%7D%2Cnull%2Cnull%2Ctrue%5D"
    }
}
