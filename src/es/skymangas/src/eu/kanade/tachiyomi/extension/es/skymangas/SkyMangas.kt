package eu.kanade.tachiyomi.extension.es.skymangas

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class SkyMangas : KeiSource() {

    private val apiHost = "https://api.skymangas.com"

    private val apiUrl = "$apiHost/api/v1"

    override suspend fun getPopularManga(page: Int) = searchManga(page, "popular", "")

    override suspend fun getLatestUpdates(page: Int) = searchManga(page, "updated", "")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList) = searchManga(page, "popular", query)

    private suspend fun searchManga(page: Int, sort: String, query: String): MangasPage {
        val url = "$apiUrl/manhuas/search".toHttpUrl().newBuilder()
            // most catalogue entries are placeholders with no chapters; the site hides them by default too
            .addQueryParameter("availability", "readable")
            .addQueryParameter("sort", sort)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("perPage", "24")
            .apply { if (query.isNotBlank()) addQueryParameter("q", query.trim()) }
            .build()
        val result = client.get(url).parseAs<ResponseDto<List<SearchMangaDto>>>()
        return MangasPage(result.data.map { it.toSManga(apiHost) }, result.meta!!.hasNextPage())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments
        if (segments.firstOrNull() !in setOf("manhua", "leer")) return null
        val slug = segments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return requestDetails(slug).toSManga(apiHost)
    }

    // Entries saved before the site rebuild hold MangaThemesia paths (/manga/<slug>/)
    private val SManga.slug get() = url.trimEnd('/').substringAfterLast('/')

    private suspend fun requestDetails(slug: String) = client.get("$apiUrl/manhuas/$slug").parseAs<ResponseDto<MangaDetailsDto>>().data

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.slug
        // the chapter endpoint takes the numeric id, which only the details response carries
        val details = requestDetails(slug)
        val chapterList = if (fetchChapters) requestChapters(details.id, slug) else chapters
        return SMangaUpdate(if (fetchDetails) details.toSManga(apiHost) else manga, chapterList)
    }

    private suspend fun requestChapters(id: String, slug: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        do {
            val url = "$apiUrl/chapters/manhua/$id".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("perPage", "100")
                .build()
            val result = client.get(url).parseAs<ResponseDto<List<ChapterDto>>>()
            result.data.mapTo(chapters) { it.toSChapter(slug) }
            page++
        } while (result.meta!!.hasNextPage())
        return chapters
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get("$apiUrl/chapters/${chapter.url}")
        .parseAs<ResponseDto<ChapterDetailsDto>>()
        .data.pages.mapIndexed { i, page -> page.toPage(i, apiHost) }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/manhua/${manga.slug}"

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/leer/${chapter.url}"
}
