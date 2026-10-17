package eu.kanade.tachiyomi.extension.en.milftoon

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.time.Instant

@Source
abstract class Milftoon : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage = fetchList(catalogUrl(page, "views"))

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchList(catalogUrl(page, "latest"))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/search_result".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
        return fetchList(url.toString())
    }

    private fun catalogUrl(page: Int, orderBy: String) = "$baseUrl/manga_list/all".toHttpUrl().newBuilder()
        .addQueryParameter("orderby", orderBy)
        .apply { if (page > 1) addQueryParameter("page", page.toString()) }
        .toString()

    // The site is a Vue SSR app; every page ships its data as JSON in a script tag.
    private suspend inline fun <reified T> fetchRouteData(url: String): T = client.get(url).asJsoup()
        .select("script")
        .map { it.data() }
        .first { it.startsWith(ROUTE_DATA_PREFIX) }
        .removePrefix(ROUTE_DATA_PREFIX)
        .substringBefore(";window.")
        .parseAs<RouteDto<T>>()
        .data

    private suspend fun fetchList(url: String): MangasPage {
        val data = fetchRouteData<ListDto>(url)
        return MangasPage(data.items.map { it.toSManga() }, data.page * data.pageSize < data.total)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val data = fetchRouteData<ReaderDto>(getMangaUrl(manga))
        val details = manga.apply {
            title = data.comic.title
            thumbnail_url = CDN_URL + data.comic.coverPath
            status = SManga.COMPLETED
        }
        val chapter = SChapter.create().apply {
            url = "/comics/${data.comic.slug}/${data.chapter.slug}"
            name = data.chapter.name
            chapter_number = data.chapter.position.toFloat()
            date_upload = Instant.tryParse(data.chapter.publishedAt)
        }
        return SMangaUpdate(details, listOf(chapter))
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = fetchRouteData<ReaderDto>(getChapterUrl(chapter))
        .pages
        .mapIndexed { i, page -> Page(i, imageUrl = page.src) }

    private fun ListItemDto.toSManga() = SManga.create().also {
        it.url = "/comics/$slug"
        it.title = title
        it.author = author
        it.thumbnail_url = CDN_URL + coverPath
        it.status = SManga.COMPLETED
    }

    companion object {
        private const val ROUTE_DATA_PREFIX = "window.__INITIAL_ROUTE_DATA__="
        private const val CDN_URL = "https://fdc1.azhentai.com/"
    }
}
