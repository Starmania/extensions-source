package eu.kanade.tachiyomi.extension.fr.scansfr

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import kotlin.time.Instant

@Source
abstract class ScansFR :
    KeiSource(),
    ConfigurableSource {

    private val apiUrl = "https://api.scansfr.com"

    private val imageHeaders = headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Android 13; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0")
        .build()

    private val sessionId = UUID.randomUUID().toString()

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val showNsfw get() = preferences.getBoolean(PREF_SHOW_NSFW, false)

    private fun nsfwQueryParam() = if (showNsfw) "&nsfw=true" else "&isNsfw=false"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_NSFW
            title = "Afficher le contenu NSFW"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = client.get("$apiUrl/api/v1/mangas?page=$page&sort=popular${nsfwQueryParam()}")
        .parseAs<MangaListDto>()
        .toMangasPage()

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = client.get("$apiUrl/api/v1/mangas?page=$page&sort=updated${nsfwQueryParam()}")
        .parseAs<MangaListDto>()
        .toMangasPage()

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/api/v1/mangas".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) addQueryParameter("search", query)
            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> if (query.isBlank()) addQueryParameter("sort", filter.selected)
                    is TypeFilter -> if (filter.selected.isNotEmpty()) addQueryParameter("type", filter.selected)
                    is StatusFilter -> if (filter.selected.isNotEmpty()) addQueryParameter("status", filter.selected)
                    is GenreFilter -> if (filter.selected.isNotEmpty()) addQueryParameter("genre", filter.selected)
                    else -> {}
                }
            }
        }.build()
        val hasChaptersOnly = filters.firstInstanceOrNull<HasChaptersFilter>()?.state == true

        val data = client.get("$url${nsfwQueryParam()}").parseAs<MangaListDto>()
        val filtered = if (hasChaptersOnly) data.mangas.filter { it.chapters > 0 } else data.mangas
        val mangas = filtered.map { it.toSManga() }
        val q = query.trim().lowercase()
        val sorted = if (q.isBlank()) {
            mangas
        } else {
            mangas.sortedBy { manga ->
                val t = manga.title.trim().lowercase()
                when {
                    t == q -> 0
                    t.startsWith(q) -> 1
                    t.contains(" $q") || t.contains("-$q") -> 2
                    t.contains(q) -> 3
                    else -> 4
                }
            }
        }
        return MangasPage(sorted, data.page < data.totalPages)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.size != 2 || url.pathSegments[0] != "manga") return null
        return getMangaDetail(url.pathSegments[1]).toSManga()
    }

    // ========================= Details & Chapters =========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.removePrefix("/manga/")
        val dto = getMangaDetail(slug)
        val chapterList = dto.chaptersList
            .sortedByDescending { it.number }
            .map { chapter ->
                SChapter.create().apply {
                    url = "/$slug/${chapter.number}"
                    name = chapter.title
                    date_upload = Instant.tryParse(chapter.date)
                    chapter_number = chapter.number.toFloat()
                }
            }
        return SMangaUpdate(dto.toSManga(), chapterList)
    }

    private suspend fun getMangaDetail(slug: String) = client.get("$apiUrl/api/v1/mangas/$slug").parseAs<MangaDetailDto>()

    private fun MangaDetailDto.toSManga() = SManga.create().apply {
        url = "/manga/$slug"
        title = this@toSManga.title
        thumbnail_url = "$apiUrl$cover"
        description = this@toSManga.description.takeIf { it.isNotBlank() }
        author = this@toSManga.author
        artist = this@toSManga.artist.takeIf { it != this@toSManga.author }
        genre = tags.joinToString()
        status = this@toSManga.status.toSMangaStatus()
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val parts = chapter.url.trim('/').split("/")
        val mangaSlug = parts[0]
        val chapterNumber = parts[1]
        val chapterId = "$mangaSlug-$chapterNumber"

        val body = "{}".toRequestBody(JSON_MEDIA_TYPE)
        val tokenHeaders = headersBuilder()
            .add("X-Session-ID", sessionId)
            .build()

        val dto = client.post("$apiUrl/api/v1/chapters/$chapterId/token", tokenHeaders, body)
            .parseAs<ChapterTokenDto>()
        return (1..dto.pageCount).map { pageNumber ->
            val imageUrl = "$apiUrl/api/v1/images/${dto.chapterId}/$pageNumber?sig=${dto.sig}&exp=${dto.exp}&s=${dto.sessionHash}"
            Page(pageNumber - 1, imageUrl = imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, imageHeaders)

    // ============================= Filters ================================

    override fun getFilterList(data: JsonElement?) = getFilters()

    // ============================= Utilities ==============================

    private fun MangaListDto.toMangasPage() = MangasPage(mangas.map { it.toSManga() }, page < totalPages)

    private fun String.toSMangaStatus() = when (this) {
        "ongoing", "En cours" -> SManga.ONGOING
        "completed", "Terminé" -> SManga.COMPLETED
        "hiatus", "En pause" -> SManga.ON_HIATUS
        "cancelled", "Abandonné" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    companion object {
        private const val PREF_SHOW_NSFW = "pref_show_nsfw"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
