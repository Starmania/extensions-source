package eu.kanade.tachiyomi.extension.id.soulscans

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
abstract class SoulScans : KeiSource() {

    private suspend fun getMangaList(url: HttpUrl): MangasPage {
        val result = client.get(url, headers).parseAs<MangaListResponseDto>()

        val page = url.queryParameter("page")!!.toInt()
        return MangasPage(result.data.map { it.toSManga() }, page < result.totalPages)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = getMangaList(searchUrl(page, sort = "popular"))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getMangaList(searchUrl(page, sort = "latest"))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = getMangaList(searchUrl(page, query = query))

    private fun searchUrl(page: Int, query: String = "", sort: String = "latest") = baseUrl.toHttpUrl()
        .newBuilder()
        .addPathSegments("api/search")
        .addQueryParameter("type", "COMIC")
        .addQueryParameter("limit", "20")
        .addQueryParameter("page", page.toString())
        .apply {
            if (query.isNotBlank()) {
                addQueryParameter("q", query)
            } else {
                addQueryParameter("sort", sort)
                addQueryParameter("order", "desc")
            }
        }
        .build()

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.pathSegments.lastOrNull { it.isNotBlank() } ?: return null
        return fetchSeriesDetail(slug).toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val detail = fetchSeriesDetail(manga.url.substringAfterLast("/"))

        return SMangaUpdate(detail.toSManga(), detail.toSChapterList())
    }

    private suspend fun fetchSeriesDetail(slug: String) = client.get("$baseUrl/api/series/comic/$slug", headers).parseAs<SeriesDetailDto>()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val path = chapter.url.removePrefix("/comic/")
        val (seriesSlug, chapterSlug) = path.split("/chapter/")

        return client.get("$baseUrl/api/series/comic/$seriesSlug/chapter/$chapterSlug", headers)
            .parseAs<ChapterPagesResponseDto>()
            .toPageList()
    }
}
