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
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Source
abstract class GeassComics :
    HttpSource(),
    ConfigurableSource {

    private val apiUrl = "https://api.geasscomics.xyz"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor { chain ->
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
            .rateLimit(2)
            .addInterceptor(GeassComicsDescrambler)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "application/json, text/plain, */*")

    private var cachedGenres: List<FilterOptionDto> = emptyList()
    private var cachedTags: List<FilterOptionDto> = emptyList()
    private var fetchFiltersAttempts = 0
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun launchIO(block: () -> Unit) = scope.launch { block() }

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
    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/api/ranking".toHttpUrl().newBuilder()
            .addQueryParameter("period", "all")
            .addQueryParameter("limit", "50")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val works = response.parseAs<ApiResponse<List<RankingEntryDto>>>().data.map { it.work }
        val mangas = works
            .filter { showNsfwPref() || !it.isNsfw }
            .map { it.toSManga() }
        return MangasPage(mangas, false)
    }

    // ============================= Latest =================================

    override fun latestUpdatesRequest(page: Int): Request = searchMangaRequest(page, "", FilterList())

    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    // ============================= Search =================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
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

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val result = response.parseAs<ApiResponse<WorkListDto>>().data
        return MangasPage(result.items.map { it.toSManga() }, result.hasNextPage())
    }

    private fun fetchFilters() {
        if (cachedGenres.isNotEmpty() && cachedTags.isNotEmpty()) return
        if (fetchFiltersAttempts >= 3) return
        fetchFiltersAttempts++

        runCatching {
            val genresRequest = GET("$apiUrl/api/genres", headers)
            val genresResponse = client.newCall(genresRequest).execute()
            if (genresResponse.isSuccessful) {
                cachedGenres = genresResponse.parseAs<ApiResponse<List<FilterOptionDto>>>().data
            }

            val tagsRequest = GET("$apiUrl/api/tags", headers)
            val tagsResponse = client.newCall(tagsRequest).execute()
            if (tagsResponse.isSuccessful) {
                cachedTags = tagsResponse.parseAs<ApiResponse<List<FilterOptionDto>>>().data
            }
        }
    }

    // ============================= Details ================================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/manga/")
        return GET("$apiUrl/api/works/$slug", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<ApiResponse<WorkDto>>().data.toSManga()

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val work = response.parseAs<ApiResponse<WorkDto>>().data
        return work.chapters.map { it.toSChapter(work.slug) }
    }

    // ============================= Pages ==================================

    // Served by the site itself rather than the API host.
    override fun pageListRequest(chapter: SChapter): Request {
        val segments = "$baseUrl${chapter.url}".toHttpUrl().pathSegments
        return GET("$baseUrl/api/read/${segments[2]}/${segments[3]}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<ReadDto>()
        return result.pages.mapIndexed { index, url ->
            val scramble = result.pageScrambles.getOrNull(index)
            Page(index, imageUrl = if (scramble.isNullOrEmpty()) url else "$url#$scramble")
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
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

    // ============================= Filters ================================

    override fun getFilterList(): FilterList {
        launchIO { fetchFilters() }

        val showNsfw = showNsfwPref()

        val filteredGenres = (if (showNsfw) cachedGenres else cachedGenres.filter { !it.isNsfw })
            .map { it.label to it.slug }
        val filteredTags = cachedTags.map { it.label to it.slug }

        return getFilters(filteredGenres, filteredTags)
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
