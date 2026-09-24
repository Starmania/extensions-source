package eu.kanade.tachiyomi.multisrc.senkuro

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.graphQLBody
import keiyoushi.utils.parseAs
import keiyoushi.utils.parseGraphQLAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

abstract class Senkuro : KeiSource() {

    override val supportsLatest = false

    override fun Headers.Builder.configureHeaders() = apply {
        set("User-Agent", "Tachiyomi (+https://github.com/keiyoushi/extensions-source)")
        add("Content-Type", "application/json")
        add("App-Id", if (name == "Senkuro") "4026531840100" else "5033164800100")
        add("App-Version", "060626")
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(3)
    }

    private val apiUrl: String
        get() = baseUrl.replace("https://", "https://api.") + "/graphql"

    private suspend inline fun <reified V : Any> graphQL(operationName: String, query: String, variables: V): Response = client.post(apiUrl, graphQLBody(query, operationName, variables))

    // ============================== Popular ==============================
    override suspend fun getPopularManga(page: Int): MangasPage = searchManga(
        SearchTachiyomiMangaVariables(
            orderBy = OrderByDto("DESC", "POPULARITY_SCORE"),
            offset = (page - 1) * OFFSET_COUNT,
        ),
    )

    private suspend fun searchManga(variables: SearchTachiyomiMangaVariables): MangasPage {
        val data = graphQL("searchTachiyomiManga", SEARCH_QUERY, variables).parseGraphQLAs<TachiyomiSearchResponseDto>()
        val mangasList = data.mangaTachiyomiSearch.mangas.map { it.toSManga() }
        return MangasPage(mangasList, mangasList.size >= OFFSET_COUNT)
    }

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    // ============================== Search ===============================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val includeGenres = mutableListOf<String>()
        val excludeGenres = mutableListOf<String>()
        val includeTypes = mutableListOf<String>()
        val excludeTypes = mutableListOf<String>()
        val includeFormats = mutableListOf<String>()
        val excludeFormats = mutableListOf<String>()
        val includeStatus = mutableListOf<String>()
        val excludeStatus = mutableListOf<String>()
        val includeTStatus = mutableListOf<String>()
        val excludeTStatus = mutableListOf<String>()
        val includeAges = mutableListOf<String>()
        val excludeAges = mutableListOf<String>()
        var orderField = "POPULARITY_SCORE"
        var orderDirection = "DESC"

        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is OrderBy -> {
                    orderField = arrayOf("SCORE", "POPULARITY_SCORE")[filter.state!!.index]
                    orderDirection = if (filter.state!!.ascending) "ASC" else "DESC"
                }
                is GenreList -> filter.state.forEach {
                    if (it.state != Filter.TriState.STATE_IGNORE) {
                        if (it.isIncluded()) includeGenres.add(it.slug) else excludeGenres.add(it.slug)
                    }
                }
                is WorldsList -> filter.state.forEach {
                    if (it.state != Filter.TriState.STATE_IGNORE) {
                        if (it.isIncluded()) includeGenres.add(it.slug) else excludeGenres.add(it.slug)
                    }
                }
                is ElementsList -> filter.state.forEach {
                    if (it.state != Filter.TriState.STATE_IGNORE) {
                        if (it.isIncluded()) includeGenres.add(it.slug) else excludeGenres.add(it.slug)
                    }
                }
                is ChartsList -> filter.state.forEach {
                    if (it.state != Filter.TriState.STATE_IGNORE) {
                        if (it.isIncluded()) includeGenres.add(it.slug) else excludeGenres.add(it.slug)
                    }
                }
                is AgeDemoList -> filter.state.forEach {
                    if (it.state != Filter.TriState.STATE_IGNORE) {
                        if (it.isIncluded()) includeGenres.add(it.slug) else excludeGenres.add(it.slug)
                    }
                }
                is TypeList -> filter.state.forEach { type ->
                    if (type.state != Filter.TriState.STATE_IGNORE) {
                        if (type.isIncluded()) includeTypes.add(type.slug) else excludeTypes.add(type.slug)
                    }
                }
                is FormatList -> filter.state.forEach { format ->
                    if (format.state != Filter.TriState.STATE_IGNORE) {
                        if (format.isIncluded()) includeFormats.add(format.slug) else excludeFormats.add(format.slug)
                    }
                }
                is StatList -> filter.state.forEach { stat ->
                    if (stat.state != Filter.TriState.STATE_IGNORE) {
                        if (stat.isIncluded()) includeStatus.add(stat.slug) else excludeStatus.add(stat.slug)
                    }
                }
                is StatTranslateList -> filter.state.forEach { tstat ->
                    if (tstat.state != Filter.TriState.STATE_IGNORE) {
                        if (tstat.isIncluded()) includeTStatus.add(tstat.slug) else excludeTStatus.add(tstat.slug)
                    }
                }
                is AgeList -> filter.state.forEach { age ->
                    if (age.state != Filter.TriState.STATE_IGNORE) {
                        if (age.isIncluded()) includeAges.add(age.slug) else excludeAges.add(age.slug)
                    }
                }
                else -> {}
            }
        }

        return searchManga(
            SearchTachiyomiMangaVariables(
                query = query.takeIf { it.isNotEmpty() },
                type = ExcludeInclude(includeTypes, excludeTypes).takeIf { it.isNotEmpty() },
                status = ExcludeInclude(includeStatus, excludeStatus).takeIf { it.isNotEmpty() },
                rating = ExcludeInclude(includeAges, excludeAges).takeIf { it.isNotEmpty() },
                format = ExcludeInclude(includeFormats, excludeFormats).takeIf { it.isNotEmpty() },
                translationStatus = ExcludeInclude(includeTStatus, excludeTStatus).takeIf { it.isNotEmpty() },
                label = ExcludeInclude(includeGenres, excludeGenres).takeIf { it.isNotEmpty() },
                orderBy = OrderByDto(orderDirection, orderField),
                offset = (page - 1) * OFFSET_COUNT,
            ),
        )
    }

    // The API only looks manga up by id, and site URLs only carry the slug.
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.pathSegments.takeIf { it.getOrNull(0) == "manga" }?.getOrNull(1) ?: return null
        return searchManga(SearchTachiyomiMangaVariables(query = slug, offset = 0))
            .mangas.firstOrNull { it.url.substringAfter(",,") == slug }
    }

    // ============================== Details ==============================
    override fun getMangaUrl(manga: SManga): String {
        val slug = manga.url.split(",,").getOrNull(1) ?: return ""
        return "$baseUrl/manga/$slug"
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val mangaId = manga.url.split(",,").first()
        val details = async {
            if (fetchDetails) {
                graphQL("fetchTachiyomiManga", DETAILS_QUERY, FetchTachiyomiMangaVariables(mangaId = mangaId))
                    .parseGraphQLAs<TachiyomiMangaInfoResponseDto>().mangaTachiyomiInfo?.toSManga()
                    ?: throw Exception("Manga not found")
            } else {
                manga
            }
        }
        val chapterList = async {
            if (fetchChapters) fetchChapterList(manga.url, mangaId) else chapters
        }
        SMangaUpdate(details.await(), chapterList.await())
    }

    // ============================= Chapters ==============================
    private val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.S", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private suspend fun fetchChapterList(mangaUrl: String, mangaId: String): List<SChapter> {
        val data = graphQL("fetchTachiyomiChapters", CHAPTERS_QUERY, FetchTachiyomiChaptersVariables(mangaId = mangaId))
            .parseGraphQLAs<TachiyomiChaptersResponseDto>().mangaTachiyomiChapters
        val teamsMap = data.teams.associateBy { it.id }

        return data.chapters.map { chapter ->
            SChapter.create().apply {
                chapter_number = chapter.number.toFloatOrNull() ?: -2f
                name = "${chapter.volume}. Глава ${chapter.number} " + (chapter.name ?: "")
                url = "$mangaUrl,,${chapter.id},,${chapter.slug}"
                date_upload = simpleDateFormat.tryParse(chapter.updatedAt ?: chapter.createdAt)
                scanlator = chapter.teamIds.mapNotNull { teamsMap[it]?.name }.joinToString().takeIf { it.isNotEmpty() }
            }
        }
    }

    // =============================== Pages ===============================
    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.split(",,")
        val mangaSlug = parts.getOrNull(1) ?: return ""
        val chapterSlug = parts.getOrNull(3) ?: return ""
        return "$baseUrl/manga/$mangaSlug/chapters/$chapterSlug"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val parts = chapter.url.split(",,")
        val mangaId = parts.getOrNull(0) ?: ""
        val chapterId = parts.getOrNull(2) ?: ""
        val pagesDto = graphQL(
            "fetchTachiyomiChapterPages",
            PAGES_QUERY,
            FetchTachiyomiChapterPagesVariables(mangaId = mangaId, chapterId = chapterId),
        ).parseGraphQLAs<TachiyomiChapterPagesResponseDto>().mangaTachiyomiChapterPages.pages
        return pagesDto.mapIndexed { index, page ->
            Page(index, imageUrl = page.url)
        }
    }

    // ============================== Filters ==============================
    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = graphQL("fetchTachiyomiSearchFilters", FILTERS_QUERY, EmptyObject)
        .parseGraphQLAs<TachiyomiSearchFiltersResponseDto>().mangaTachiyomiSearchFilters.labels.toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList {
        val labelsList = data?.parseAs<List<LabelDto>>().orEmpty().map { label ->
            FilterersTriRoot(
                label.titles.find { it.lang == "RU" }?.content?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } ?: label.slug,
                label.slug,
                label.rootId ?: "",
            )
        }.sortedBy { it.name }

        val filters = mutableListOf<Filter<*>>()
        filters += listOf(
            OrderBy(),
            TypeList(getTypeList()),
            FormatList(getFormatList()),
            StatList(getStatList()),
            StatTranslateList(getStatTranslateList()),
            AgeList(getAgeList()),
        )
        if (labelsList.isNotEmpty()) {
            filters += listOf(
                GenreList(labelsList.filter { it.rootId == "TEFCRUw6NQ" }), // Темы
                WorldsList(labelsList.filter { it.rootId == "TEFCRUw6NA" }), // Сеттинг
                ElementsList(labelsList.filter { it.rootId == "TEFCRUw6Ng" }), // Элементы
                ChartsList(labelsList.filter { it.rootId == "TEFCRUw6Mw" }), // Черты
                AgeDemoList(labelsList.filter { it.rootId == "TEFCRUw6Nw" }), // Демография
            )
        }
        return FilterList(filters)
    }

    private class FilterersTriRoot(name: String, val slug: String, val rootId: String) : Filter.TriState(name)
    private class GenreList(labels: List<FilterersTriRoot>) : Filter.Group<FilterersTriRoot>("Темы", labels)
    private class WorldsList(labels: List<FilterersTriRoot>) : Filter.Group<FilterersTriRoot>("Сеттинг", labels)
    private class ElementsList(labels: List<FilterersTriRoot>) : Filter.Group<FilterersTriRoot>("Элементы", labels)
    private class ChartsList(labels: List<FilterersTriRoot>) : Filter.Group<FilterersTriRoot>("Черты", labels)
    private class AgeDemoList(labels: List<FilterersTriRoot>) : Filter.Group<FilterersTriRoot>("Демография", labels)
    private class FilterersTri(name: String, val slug: String) : Filter.TriState(name)
    private class TypeList(types: List<FilterersTri>) : Filter.Group<FilterersTri>("Тип", types)
    private class FormatList(formats: List<FilterersTri>) : Filter.Group<FilterersTri>("Формат", formats)
    private class StatList(status: List<FilterersTri>) : Filter.Group<FilterersTri>("Статус", status)
    private class StatTranslateList(tstatus: List<FilterersTri>) : Filter.Group<FilterersTri>("Статус перевода", tstatus)
    private class AgeList(ages: List<FilterersTri>) : Filter.Group<FilterersTri>("Возрастное ограничение", ages)

    private class OrderBy :
        Filter.Sort(
            "Сортировка",
            arrayOf("По рейтингу", "По популярности"),
            Selection(1, false),
        )

    private fun getTypeList() = listOf(
        FilterersTri("Манга", "MANGA"),
        FilterersTri("Манхва", "MANHWA"),
        FilterersTri("Маньхуа", "MANHUA"),
        FilterersTri("Комикс", "COMICS"),
        FilterersTri("OEL Манга", "OEL_MANGA"),
        FilterersTri("РуМанга", "RU_MANGA"),
    )

    private fun getStatList() = listOf(
        FilterersTri("Анонс", "ANNOUNCE"),
        FilterersTri("Онгоинг", "ONGOING"),
        FilterersTri("Выпущено", "FINISHED"),
        FilterersTri("Приостановлено", "HIATUS"),
        FilterersTri("Отменено", "CANCELLED"),
    )

    private fun getStatTranslateList() = listOf(
        FilterersTri("Переводится", "IN_PROGRESS"),
        FilterersTri("Завершён", "FINISHED"),
        FilterersTri("Заморожен", "FROZEN"),
        FilterersTri("Заброшен", "ABANDONED"),
    )

    private fun getAgeList() = listOf(
        FilterersTri("0+", "GENERAL"),
        FilterersTri("12+", "SENSITIVE"),
        FilterersTri("16+", "QUESTIONABLE"),
        FilterersTri("18+", "EXPLICIT"),
    )

    private fun getFormatList() = listOf(
        FilterersTri("Сборник", "DIGEST"),
        FilterersTri("Додзинси", "DOUJINSHI"),
        FilterersTri("В цвете", "IN_COLOR"),
        FilterersTri("Сингл", "SINGLE"),
        FilterersTri("Веб", "WEB"),
        FilterersTri("Вебтун", "WEBTOON"),
        FilterersTri("Ёнкома", "YONKOMA"),
        FilterersTri("Short", "SHORT"),
    )

    // ============================= Utilities =============================
    companion object {
        private const val OFFSET_COUNT = 10
    }
}
