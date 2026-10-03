package eu.kanade.tachiyomi.extension.es.insanosscan

import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class InsanosScan :
    KeiSource(),
    ConfigurableSource {

    private val preferences = getPreferences()

    private val showLockedChapters: Boolean
        get() = preferences.getBoolean(PREF_SHOW_LOCKED, false)

    // The API returns the whole catalogue in one response, so sorting and searching are done locally.
    private suspend fun fetchSeries() = client.get("$baseUrl/series/").parseAs<List<SeriesDto>>()

    private fun List<SeriesDto>.toMangasPage() = MangasPage(map { it.toSManga(baseUrl) }, false)

    override suspend fun getPopularManga(page: Int) = fetchSeries().sortedByDescending { it.viewCount }.toMangasPage()

    override suspend fun getLatestUpdates(page: Int) = fetchSeries().sortedByDescending { it.updatedAt }.toMangasPage()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList) = fetchSeries().filter { it.title.contains(query.trim(), ignoreCase = true) }.toMangasPage()

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "serie") return null
        return fetchDetails(segments[1])
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/serie/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val (seriesId, chapterId) = chapter.url.split("/")
        return "$baseUrl/reader?series=$seriesId&chapter=$chapterId"
    }

    private suspend fun fetchDetails(seriesId: String) = client.get("$baseUrl/series/$seriesId").parseAs<SeriesDto>().toSMangaDetails(baseUrl)

    private suspend fun fetchChapters(seriesId: String): List<SChapter> {
        val showLocked = showLockedChapters
        return client.get("$baseUrl/series/$seriesId/chapters").parseAs<List<ChapterDto>>()
            .filter { showLocked || it.isUnlocked }
            .sortedByDescending { it.chapterNumber }
            .map { it.toSChapter() }
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

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get("$baseUrl/series/${chapter.url.replace("/", "/chapters/")}/pages")
        .parseAs<PagesDto>()
        .pages
        .mapIndexed { i, path -> Page(i, imageUrl = baseUrl + path) }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        CheckBoxPreference(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Mostrar capítulos bloqueados"
            summary = "Incluye los capítulos de acceso anticipado VIP que aún no se pueden leer gratis"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "show_paid_chapters"
    }
}
