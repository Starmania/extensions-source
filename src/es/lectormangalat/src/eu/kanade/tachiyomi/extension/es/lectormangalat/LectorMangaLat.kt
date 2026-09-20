package eu.kanade.tachiyomi.extension.es.lectormangalat

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
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Source
abstract class LectorMangaLat : KeiSource() {

    // The site renders from this backend; it is the only place with structured data.
    private val apiUrl = "https://api.zerocomics.net/api"

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(2)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = getSeriesPage(page, sort = "rating")

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSeriesPage(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = getSeriesPage(
        page,
        query = query,
        type = filters.firstInstanceOrNull<TypeFilter>()?.value.orEmpty(),
        status = filters.firstInstanceOrNull<StatusFilter>()?.value.orEmpty(),
        genre = filters.firstInstanceOrNull<GenreFilter>()?.value.orEmpty(),
    )

    private suspend fun getSeriesPage(
        page: Int,
        sort: String = "",
        query: String = "",
        type: String = "",
        status: String = "",
        genre: String = "",
    ): MangasPage {
        val url = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .apply {
                if (sort.isNotEmpty()) addQueryParameter("sort", sort)
                if (query.isNotBlank()) addQueryParameter("q", query.trim())
                if (type.isNotEmpty()) addQueryParameter("tipo", type)
                if (status.isNotEmpty()) addQueryParameter("estado", status)
                if (genre.isNotEmpty()) addQueryParameter("generos", genre)
            }
            .build()

        val result = client.get(url).parseAs<SeriesPageDto>()
        return MangasPage(result.data.map { it.toSManga() }, result.hasNextPage)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(TypeFilter(), StatusFilter(), GenreFilter())

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "comics") {
            return null
        }
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() && it != "genre" } ?: return null

        return getSeries(slug).toSManga()
    }

    // Details and chapters share one response. The slug is read from the last segment so
    // entries saved by the old Madara version ("/biblioteca/<slug>/") still resolve.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val series = getSeries(manga.url.trim('/').substringAfterLast('/'))
        return SMangaUpdate(series.toSManga(), series.toSChapters())
    }

    private suspend fun getSeries(slug: String) = client.get("$apiUrl/series/$slug").parseAs<DataDto<SeriesDetailsDto>>().data

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val segments = chapter.url.trim('/').split('/')
        val slug = segments[1]
        val number = segments[2].removePrefix("capitulo-")

        return client.get("$apiUrl/series/$slug/capitulo/$number").parseAs<DataDto<ChapterPagesDto>>().data
            .paginas.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }
}
