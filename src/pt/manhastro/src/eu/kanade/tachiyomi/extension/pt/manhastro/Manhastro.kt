package eu.kanade.tachiyomi.extension.pt.manhastro

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Manhastro :
    KeiSource(),
    ConfigurableSource {

    private val apiUrl = "https://api2.manhastro.net"

    private val preferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient() = connectTimeout(30.seconds)
        .readTimeout(30.seconds)
        .rateLimit(2)

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val result = client.get("$apiUrl/rank/diario").parseAs<ApiResponse<List<MangaDto>>>(transform = ::cleanJsonResponse)

        return MangasPage(result.data.map { it.toSManga() }, false)
    }

    // ============================== Latest ==============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val result = client.get("$apiUrl/lancamentos").parseAs<ApiResponse<List<MangaDto>>>(transform = ::cleanJsonResponse)

        return MangasPage(result.data.distinctBy { it.mangaId }.map { it.toSManga() }, false)
    }

    // ============================== Search ==============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/dados".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "100")

        if (query.isNotBlank()) {
            url.addQueryParameter("nome", query.trim())
        }

        filters.filterIsInstance<TypeFilter>().firstOrNull()?.state
            ?.filter { it.state }
            ?.takeIf { it.isNotEmpty() }
            ?.let { url.addQueryParameter("categoria", it.joinToString(",") { type -> type.value }) }

        filters.filterIsInstance<GenreFilter>().firstOrNull()?.state
            ?.filter { it.state }
            ?.takeIf { it.isNotEmpty() }
            ?.let { url.addQueryParameter("genero", it.joinToString(",") { genre -> genre.value }) }

        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected ?: SortFilter().selected
        url.addQueryParameter("sort", sort.key)
        url.addQueryParameter("order", sort.order)

        val result = client.get(url.build()).parseAs<CatalogResponse>(transform = ::cleanJsonResponse)

        return MangasPage(result.data.map { it.toSManga() }, result.meta.hasMore)
    }

    override fun getFilterList(data: JsonElement?) = getFilters()

    // ============================== URL search ==============================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.getOrNull(0) != "manga") return null
        val mangaId = url.pathSegments.getOrNull(1)?.toIntOrNull() ?: return null

        return getMangaDetails(mangaId)
    }

    // ============================== Details + Chapters ==============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val mangaId = manga.url.substringAfterLast("/")
        val detailsDeferred = async { if (fetchDetails) getMangaDetails(mangaId.toInt()) else manga }
        val chaptersDeferred = async { if (fetchChapters) getChapterList(mangaId) else chapters }

        SMangaUpdate(detailsDeferred.await(), chaptersDeferred.await())
    }

    private suspend fun getMangaDetails(mangaId: Int): SManga {
        val result = client.get("$apiUrl/dados?manga_id=$mangaId").parseAs<ApiResponse<List<MangaDto>>>(transform = ::cleanJsonResponse)

        return result.data.firstOrNull()?.toSManga() ?: throw Exception("Manga not found")
    }

    private suspend fun getChapterList(mangaId: String): List<SChapter> {
        val result = client.get("$apiUrl/dados/$mangaId").parseAs<ApiResponse<List<ChapterDto>>>(transform = ::cleanJsonResponse)

        return result.data.map { chapter ->
            SChapter.create().apply {
                url = "/capitulo/${chapter.capituloId}"
                name = chapter.capituloNome
                chapter_number = extractChapterNumber(chapter.capituloNome)
                date_upload = DATE_FORMAT.tryParseDateTime(chapter.capituloData)
            }
        }.sortedByDescending { it.chapter_number }
    }

    private fun extractChapterNumber(name: String): Float {
        val regex = Regex("""(\d+(?:\.\d+)?)""")
        val match = regex.find(name)
        return match?.value?.toFloatOrNull() ?: -1f
    }

    // ============================== Pages ==============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val result = client.get("$apiUrl/paginas/${chapter.url.substringAfterLast("/")}").parseAs<PagesResponse>(transform = ::cleanJsonResponse)
        val chapterData = result.data.chapter ?: return emptyList()

        return chapterData.data.mapIndexed { i, filename ->
            Page(i, imageUrl = "${chapterData.baseUrl}/${chapterData.hash}/$filename")
        }
    }

    // ============================== Helpers ==============================

    private fun cleanJsonResponse(body: String): String = body.removePrefix("\uFEFF")
        .removePrefix(")]}'")
        .removePrefix(",")
        .removePrefix("_")
        .trim()

    private fun MangaDto.toSManga() = SManga.create().apply {
        url = "/manga/$mangaId"
        title = if (useEnglishTitle) {
            titulo.takeIf { it.isNotBlank() } ?: displayTitle
        } else {
            displayTitle
        }
        description = displayDescription
        genre = generos.joinToString()
        thumbnail_url = thumbnailUrl
        status = SManga.UNKNOWN
    }

    private val useEnglishTitle: Boolean
        get() =
            preferences.getBoolean(ENGLISH_TITLE_PREF, false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = ENGLISH_TITLE_PREF
            title = "Títulos em inglês"
            summary = "Use títulos em inglês como principal quando disponível. (Requer ativar \"Atualizar os títulos dos mangás da biblioteca para corresponder à fonte\" em \"Avançado\")"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private const val ENGLISH_TITLE_PREF = "englishTitlePref"
    }
}
