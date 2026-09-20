package eu.kanade.tachiyomi.multisrc.moonlighttl

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.lib.i18n.Intl
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import kotlin.math.min

abstract class MoonlightTL : KeiSource() {
    protected val intl = Intl(
        lang,
        setOf("en", "es"),
        "en",
        this::class.java.classLoader!!,
    )

    private val seriesPath = "/ver"

    override suspend fun getPopularManga(page: Int): MangasPage {
        val responseData = client.get("$baseUrl/api/topSerie").parseAs<ResponseDto<TopSeriesDto>>()

        val topDaily = responseData.response.topDaily.flatten().map { it.data }
        val topWeekly = responseData.response.topWeekly.flatten().map { it.data }
        val topMonthly = responseData.response.topMonthly.flatten().map { it.data }

        val mangas = (topDaily + topWeekly + topMonthly).distinctBy { it.slug }
            .map { it.toSManga(seriesPath) }

        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val responseData = client.get("$baseUrl/api/lastUpdates").parseAs<ResponseDto<List<SeriesDto>>>()

        val mangas = responseData.response
            .map { it.toSManga(seriesPath) }

        return MangasPage(mangas, false)
    }

    private var comicsList = listOf<SeriesDto>()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (comicsList.isEmpty()) {
            comicsList = client.get("$baseUrl/api/comics").parseAs<ResponseDto<List<SeriesDto>>>().response
        }
        return applyFilters(comicsList, page, query, filters)
    }

    private fun applyFilters(comics: List<SeriesDto>, page: Int, query: String, filterList: FilterList): MangasPage {
        var filteredList = mutableListOf<SeriesDto>()

        if (query.isNotBlank()) {
            if (query.length < 2) throw Exception(intl["search_length_error"])
            filteredList.addAll(
                comicsList.filter {
                    it.name.contains(query, ignoreCase = true) || it.alternativeName?.contains(query, ignoreCase = true) == true
                },
            )
        } else {
            filteredList.addAll(comics)
        }

        val statusFilter = filterList.firstInstanceOrNull<StatusFilter>()

        if (statusFilter != null) {
            if (statusFilter.toUriPart() != 0) {
                filteredList = filteredList.filter { it.status == statusFilter.toUriPart() }.toMutableList()
            }
        }

        val sortByFilter = filterList.firstInstanceOrNull<SortByFilter>()

        if (sortByFilter != null) {
            when (sortByFilter.selected) {
                "name" -> filteredList.sortBy { it.name }
                "views" -> filteredList.sortBy { it.trending?.views }
                "updated_at" -> filteredList.sortBy { it.lastChapterDate }
                "created_at" -> filteredList.sortBy { it.createdAt }
            }

            if (sortByFilter.state?.ascending == false) {
                filteredList.reverse()
            }
        }

        val hasNextPage = filteredList.size > page * MANGAS_PER_PAGE

        return MangasPage(
            filteredList.subList((page - 1) * MANGAS_PER_PAGE, min(page * MANGAS_PER_PAGE, filteredList.size))
                .map { it.toSManga(seriesPath) },
            hasNextPage,
        )
    }

    override fun getFilterList(data: JsonElement?) = getFilters(intl)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.substringAfterLast('/')
        val series = client.get("$baseUrl/api/showProject/$slug").parseAs<ResponseDto<SeriesDto>>().response
        return SMangaUpdate(
            series.toSMangaDetails(intl),
            series.chapters.map { it.toSChapter(seriesPath, series.slug, intl) },
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (seriesSlug, chapterSlug) = chapter.url.removePrefix("$seriesPath/").split('/')
        val response = client.get("$baseUrl/api/showProject/$seriesSlug/$chapterSlug")
            .parseAs<ResponseDto<ChapterPagesDto>>().response
        return response.pages.urlImg.parseAs<List<String>>().mapIndexed { i, url ->
            Page(i, imageUrl = url)
        }
    }

    companion object {
        const val MANGAS_PER_PAGE = 15
    }
}
