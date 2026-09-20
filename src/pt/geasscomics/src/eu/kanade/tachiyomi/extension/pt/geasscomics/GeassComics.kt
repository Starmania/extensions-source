package eu.kanade.tachiyomi.extension.pt.geasscomics

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
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
import keiyoushi.utils.toJsonString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Source
abstract class GeassComics :
    KeiSource(),
    ConfigurableSource {

    private val apiUrl = "https://api.geasscomics.xyz"

    private val preferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor { chain ->
            val request = chain.request()
            val token = getToken()
            val newRequest = if (token.isNotEmpty()) {
                request.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                request
            }
            chain.proceed(newRequest)
        }
        rateLimit(2)
        addInterceptor(GeassComicsDescrambler)
    }

    override fun Headers.Builder.configureHeaders() = add("Accept", "application/json, text/plain, */*")

    // ============================= Auth ===================================

    private fun getToken(): String {
        val email = preferences.getString(PREF_EMAIL, "") ?: ""
        val password = preferences.getString(PREF_PASSWORD, "") ?: ""
        if (email.isEmpty() || password.isEmpty()) {
            return ""
        }

        val cachedToken = preferences.getString(PREF_TOKEN, "") ?: ""
        if (cachedToken.isNotEmpty()) return cachedToken

        return runCatching { login(email, password) }.getOrDefault("")
    }

    private fun login(email: String, password: String): String {
        val payload = LoginRequest(email, password).toJsonString()
        val requestBody = payload.toRequestBody(JSON_MEDIA_TYPE)
        val request = POST("$apiUrl/api/auth/login", headers, requestBody)
        val response = network.client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("Login failed: ${response.code}")
        }
        val loginResponse = response.parseAs<ApiResponse<LoginResponseData>>()
        val token = loginResponse.data.accessToken
        preferences.edit().putString(PREF_TOKEN, token).apply()
        return token
    }

    private fun checkLogin(email: String, password: String) {
        if (email.isEmpty() || password.isEmpty()) return

        Thread {
            val token = runCatching { login(email, password) }.getOrDefault("")
            val message = if (token.isNotEmpty()) {
                "Login realizado com sucesso"
            } else {
                "Falha no login"
            }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(Injekt.get<Application>(), message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // ============================= Popular ================================

    // The ranking is capped at 50 entries and has no pagination.
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$apiUrl/api/ranking".toHttpUrl().newBuilder()
            .addQueryParameter("period", "all")
            .addQueryParameter("limit", "50")
            .build()
        val works = client.get(url).parseAs<ApiResponse<List<RankingEntryDto>>>().data.map { it.work }
        val mangas = works
            .filter { showNsfwPref() || !it.isNsfw }
            .map { it.toSManga() }
        return MangasPage(mangas, false)
    }

    // ============================= Latest =================================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    // ============================= Search =================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/api/works".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        if (!showNsfwPref()) {
            url.addQueryParameter("safe", "true")
        }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> filter.params.forEach { (key, value) ->
                    url.addQueryParameter(key, value)
                }

                is StatusFilter -> {
                    filter.selected?.let { url.addQueryParameter("status", it) }
                }

                is GenreFilter -> {
                    val selectedGenres = filter.state.filter { it.state }.map { it.id }
                    if (selectedGenres.isNotEmpty()) {
                        url.addQueryParameter("genres", selectedGenres.joinToString(","))
                    }
                }

                is TagFilter -> {
                    val selectedTags = filter.state.filter { it.state }.map { it.id }
                    if (selectedTags.isNotEmpty()) {
                        url.addQueryParameter("tags", selectedTags.joinToString(","))
                    }
                }

                else -> {}
            }
        }

        val result = client.get(url.build()).parseAs<ApiResponse<WorkListDto>>().data
        return MangasPage(result.items.map { it.toSManga() }, result.hasNextPage())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() !in setOf("work", "obra")) {
            return null
        }
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return fetchWork(slug).toSManga()
    }

    // ============================= Filters ================================

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement {
        val genres = client.get("$apiUrl/api/genres").parseAs<ApiResponse<JsonElement>>().data
        val tags = client.get("$apiUrl/api/tags").parseAs<ApiResponse<JsonElement>>().data
        return buildJsonObject {
            put("genres", genres)
            put("tags", tags)
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterDataDto>()
        val genres = filterData?.genres.orEmpty()
            .filter { showNsfwPref() || !it.isNsfw }
            .map { it.label to it.slug }
        val tags = filterData?.tags.orEmpty().map { it.label to it.slug }

        return getFilters(genres, tags)
    }

    // ============================= Details ================================

    private suspend fun fetchWork(slug: String): WorkDto = client.get("$apiUrl/api/works/$slug").parseAs<ApiResponse<WorkDto>>().data

    // Details and the chapter list share one response, so both are always returned.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val work = fetchWork(manga.url.removePrefix("/manga/"))
        return SMangaUpdate(work.toSManga(), work.chapters.map { it.toSChapter(work.slug) })
    }

    // ============================= Pages ==================================

    // Served by the site itself rather than the API host.
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val segments = "$baseUrl${chapter.url}".toHttpUrl().pathSegments
        val result = client.get("$baseUrl/api/read/${segments[2]}/${segments[3]}").parseAs<ReadDto>()
        return result.pages.mapIndexed { index, url ->
            val scramble = result.pageScrambles.getOrNull(index)
            Page(index, imageUrl = if (scramble.isNullOrEmpty()) url else "$url#$scramble")
        }
    }

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    // ============================= Utils ==================================

    override fun getMangaUrl(manga: SManga): String {
        val slug = manga.url.removePrefix("/manga/")
        return "$baseUrl/work/$slug"
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val pathSegments = "$baseUrl${chapter.url}".toHttpUrl().pathSegments
        return "$baseUrl/read/${pathSegments[2]}/${pathSegments[3]}"
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val warning =
            "⚠️ Os dados inseridos nesta seção serão usados somente para realizar o login na fonte"
        val message = "Insira %s para prosseguir com o acesso aos recursos disponíveis na fonte"

        EditTextPreference(screen.context).apply {
            key = PREF_EMAIL
            title = "📧 Email"
            summary = "Email de acesso"
            dialogMessage = buildString {
                appendLine(message.format("seu email"))
                append("\n$warning")
            }
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().remove(PREF_TOKEN).apply()
                val password = preferences.getString(PREF_PASSWORD, "") ?: ""
                checkLogin(newValue as String, password)
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "🔑 Senha"
            summary = "Senha de acesso"
            dialogMessage = buildString {
                appendLine(message.format("sua senha"))
                append("\n$warning")
            }
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().remove(PREF_TOKEN).apply()
                val email = preferences.getString(PREF_EMAIL, "") ?: ""
                checkLogin(email, newValue as String)
                true
            }
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ADULT_KEY
            title = "Exibir conteúdo adulto"
            summary = "Habilita a visualização de mangás Hentai nas listas."
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    private fun showNsfwPref() = preferences.getBoolean(PREF_ADULT_KEY, false)

    companion object {
        private const val PAGE_LIMIT = 24
        private const val PREF_EMAIL = "pref_email"
        private const val PREF_PASSWORD = "pref_password"
        private const val PREF_TOKEN = "pref_token"
        private const val PREF_ADULT_KEY = "pref_adult_content"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
