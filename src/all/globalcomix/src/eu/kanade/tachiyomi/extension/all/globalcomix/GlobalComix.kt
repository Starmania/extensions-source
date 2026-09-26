package eu.kanade.tachiyomi.extension.all.globalcomix

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.extension.all.globalcomix.dto.ChapterDataDto.Companion.createChapter
import eu.kanade.tachiyomi.extension.all.globalcomix.dto.ChapterDto
import eu.kanade.tachiyomi.extension.all.globalcomix.dto.ChaptersDto
import eu.kanade.tachiyomi.extension.all.globalcomix.dto.EntityDto
import eu.kanade.tachiyomi.extension.all.globalcomix.dto.MangaDataDto.Companion.createManga
import eu.kanade.tachiyomi.extension.all.globalcomix.dto.MangaDto
import eu.kanade.tachiyomi.extension.all.globalcomix.dto.MangasDto
import eu.kanade.tachiyomi.extension.all.globalcomix.dto.UnknownEntity
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.lib.i18n.Intl
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class GlobalComix :
    KeiSource(),
    ConfigurableSource {

    // the site's own lang codes for these differ from Tachiyomi's lang codes
    private val extLang: String
        get() = when (lang) {
            "sq" -> "al"
            "pt-BR" -> "br"
            "zh-Hans" -> "cn"
            "cs" -> "cz"
            "da" -> "dk"
            "fil" -> "fo"
            "he" -> "iw"
            "ja" -> "jp"
            "ko" -> "kr"
            "ms" -> "my"
            "sv" -> "se"
            "uk" -> "ua"
            "zh-Hant" -> "zh"
            else -> lang
        }

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        serializersModule += SerializersModule {
            polymorphic(EntityDto::class) {
                defaultDeserializer { UnknownEntity.serializer() }
            }
        }
    }

    private val intl = Intl(
        language = lang,
        baseLanguage = ENGLISH,
        availableLanguages = setOf(ENGLISH),
        classLoader = this::class.java.classLoader!!,
        createMessageFileName = { lang -> Intl.createDefaultMessageFileName(lang) },
    )

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("x-gc-client", CLIENT_ID)
        set("x-gc-identmode", "cookie")
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    private suspend fun simpleQuery(page: Int, orderBy: String?, query: String?): MangasPage {
        val url = API_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("lang_id[]", extLang)
            .addQueryParameter("p", page.toString())

        orderBy?.let { url.addQueryParameter("sort", it) }
        query?.let { url.addQueryParameter("q", it) }

        return client.get(url.build()).parseAs<MangasDto>().payload!!.let { dto ->
            MangasPage(
                dto.results.map { it.createManga() },
                dto.pagination.hasNextPage,
            )
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage = simpleQuery(page, orderBy = null, query = null)

    override suspend fun getLatestUpdates(page: Int): MangasPage = simpleQuery(page, "recent", query = null)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.startsWith(PREFIX_ID_SEARCH)) {
            val mangaSlugId = query.removePrefix(PREFIX_ID_SEARCH)

            if (mangaSlugId.isEmpty()) {
                throw Exception(intl["invalid_manga_id"])
            }

            return MangasPage(listOf(fetchManga(mangaSlugId)), false)
        }

        return simpleQuery(page, orderBy = "relevance", query)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val titleId = url.pathSegments.getOrNull(1) ?: return null
        return fetchManga(titleId)
    }

    private suspend fun fetchManga(slug: String): SManga {
        val url = API_MANGA_URL.toHttpUrl().newBuilder()
            .addPathSegment(slug)
            .build()

        return client.get(url).parseAs<MangaDto>().payload!!
            .results
            .createManga()
    }

    override fun getMangaUrl(manga: SManga): String = "$WEB_COMIC_URL/${titleToSlug(manga.title)}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val details = if (fetchDetails) async { fetchManga(titleToSlug(manga.title)) } else null
        val chapterList = if (fetchChapters) async { fetchChapterList(manga) } else null

        SMangaUpdate(
            manga = details?.await() ?: manga,
            chapters = chapterList?.await() ?: chapters,
        )
    }

    private suspend fun fetchChapterList(manga: SManga): List<SChapter> {
        val url = API_SEARCH_URL.toHttpUrl().newBuilder()
            .addPathSegment(manga.url) // manga.url contains the the comic id
            .addPathSegment("releases")
            .addQueryParameter("lang_id", extLang)
            .addQueryParameter("all", "true")
            .build()

        return client.get(url).parseAs<ChaptersDto>().payload!!.results.filterNot { dto ->
            dto.isPremium && !preferences.showLockedChapters
        }.map { it.createChapter() }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterKey = chapter.url
        val chapterWebUrl = "$WEB_CHAPTER_URL/$chapterKey"

        return client.get("$API_CHAPTER_URL/$chapterKey").parseAs<ChapterDto>()
            .payload!!
            .results
            .page_objects!!
            .map { dto -> if (preferences.useDataSaver) dto.mobile_image_url else dto.desktop_image_url }
            .mapIndexed { index, url -> Page(index, "$chapterWebUrl/$index", url) }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val dataSaverPref = SwitchPreferenceCompat(screen.context).apply {
            key = getDataSaverPreferenceKey(extLang)
            title = intl["data_saver"]
            summary = intl["data_saver_summary"]
            setDefaultValue(false)
        }

        val showLockedChaptersPref = SwitchPreferenceCompat(screen.context).apply {
            key = getShowLockedChaptersPreferenceKey(extLang)
            title = intl["show_locked_chapters"]
            summary = intl["show_locked_chapters_summary"]
            setDefaultValue(true)
        }

        screen.addPreference(dataSaverPref)
        screen.addPreference(showLockedChaptersPref)
    }

    private inline fun <reified T> Response.parseAs(): T = parseAs(json)

    private val SharedPreferences.useDataSaver
        get() = getBoolean(getDataSaverPreferenceKey(extLang), false)

    private val SharedPreferences.showLockedChapters
        get() = getBoolean(getShowLockedChaptersPreferenceKey(extLang), true)

    companion object {
        fun titleToSlug(title: String) = title.trim()
            .lowercase(Locale.US)
            .replace(titleSpecialCharactersRegex, "-")

        val titleSpecialCharactersRegex = "[^a-z0-9]+".toRegex()
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
}
