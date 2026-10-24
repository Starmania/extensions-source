package eu.kanade.tachiyomi.extension.tr.nemesisscans

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstance
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class Nemesisscans : KeiSource() {

    // The Angular front end on baseUrl reads everything from this API host.
    private val apiUrl = "https://yahsirou.xyz"

    override suspend fun getPopularManga(page: Int): MangasPage = getSeriesList("$apiUrl/series?page=$page")

    // The endpoint ignores paging and returns at most 50 entries.
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val mangas = client.get("$apiUrl/latestepisodes?limit=50").parseAs<LatestListDto>()
            .data.map { it.series.toSManga() }
        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = "$apiUrl/series/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query.trim())
                .addQueryParameter("page", page.toString())
                .build()
            return getSeriesList(url.toString())
        }

        val url = "$apiUrl/series/browse".toHttpUrl().newBuilder().apply {
            filters.firstInstance<GenreFilter>().toUriPart().takeIf { it.isNotEmpty() }
                ?.let { addQueryParameter("genre", it) }
            filters.firstInstance<StatusFilter>().toUriPart().takeIf { it.isNotEmpty() }
                ?.let { addQueryParameter("isFinished", it) }
            addQueryParameter("sort", filters.firstInstance<SortFilter>().toUriPart())
            addQueryParameter("page", page.toString())
        }.build()
        return getSeriesList(url.toString())
    }

    private suspend fun getSeriesList(url: String): MangasPage {
        val dto = client.get(url).parseAs<SeriesListDto>()
        return MangasPage(dto.data.map { it.toSManga() }, dto.pagination.page < dto.pagination.pages)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        GenreFilter(),
        StatusFilter(),
        SortFilter(),
    )

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "series") return null
        val id = url.pathSegments.getOrNull(1)?.toIntOrNull() ?: return null
        return fetchDetails(id.toString())
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val details = async { if (fetchDetails) fetchDetails(manga.url) else manga }
        val chapterList = async { if (fetchChapters) fetchChapters(manga.url) else chapters }
        SMangaUpdate(details.await(), chapterList.await())
    }

    private suspend fun fetchDetails(id: String): SManga = client.get("$apiUrl/series/$id").parseAs<SeriesDetailDto>().series.toSManga()

    private suspend fun fetchChapters(id: String): List<SChapter> = client.get("$apiUrl/series/$id/episodes").parseAs<EpisodeListDto>()
        .episodes.map { it.toSChapter(id) }
        .reversed()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (seriesId, episode) = chapter.url.split("/")
        return client.get("$apiUrl/series/$seriesId/episodes/$episode").parseAs<EpisodeDetailDto>()
            .episode.images
            .sortedBy { it.order }
            .mapIndexed { index, image -> Page(index, imageUrl = image.url) }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/series/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val (seriesId, episode) = chapter.url.split("/")
        return "$baseUrl/series/$seriesId/episode/$episode"
    }
}
