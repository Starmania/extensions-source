package eu.kanade.tachiyomi.multisrc.greenshit

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.jvm.Synchronized

abstract class GreenShit :
    KeiSource(),
    ConfigurableSource {

    abstract val apiUrl: String
    abstract val cdnApiUrl: String
    abstract val cdnUrl: String
    abstract val scanId: String

    protected open val emailPreferenceKey: String
        get() = "email_$id"
    protected open val passwordPreferenceKey: String
        get() = "password_$id"

    protected open val rateLimitPerSecond = 2
    protected open val defaultGenreId = "1"
    protected open val limitPerPage = "26"

    protected open val supportsFilters: Boolean = true
    protected open val formatsList: Array<Pair<String, String>> = FormatoFilter.FORMATOS
    protected open val statusList: Array<Pair<String, String>> = StatusFilter.STATUS
    protected open val ordersList: Array<Pair<String, String>> = SortFilter.ORDENAR
    protected open val genresList: Array<Pair<String, String>> = emptyArray()
    protected open val tagsList: Array<Pair<String, String>> = emptyArray()

    private val preferences: SharedPreferences by getPreferencesLazy()

    private var cachedToken: String? = null
    private var tokenExpiryTime: Long = 0L

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(::authIntercept)
        .rateLimit(rateLimitPerSecond)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("scan-id", scanId)

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$apiUrl/obras/ranking".toHttpUrl().newBuilder()
            .addQueryParameter("tipo", "visualizacoes_geral")
            .addQueryParameter("limite", limitPerPage)
            .addQueryParameter("pagina", page.toString())
            .addQueryParameter("gen_id", defaultGenreId)
            .build()
        return client.get(url).parseMangasPage()
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$apiUrl/obras/atualizacoes".toHttpUrl().newBuilder()
            .addQueryParameter("pagina", page.toString())
            .addQueryParameter("limite", limitPerPage)
            .addQueryParameter("gen_id", defaultGenreId)
            .build()
        return client.get(url).parseMangasPage()
    }

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/obras/buscar".toHttpUrl().newBuilder()
            .addQueryParameter("limite", limitPerPage)
            .addQueryParameter("pagina", page.toString())

        if (query.isNotEmpty()) {
            url.addQueryParameter("obr_nome", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is GenresFilter -> {
                    val selected = filter.state.filter { it.state }
                    if (selected.isNotEmpty()) {
                        selected.forEach { genre ->
                            url.addQueryParameter("gen_id", genre.value)
                        }
                    } else {
                        url.addQueryParameter("todos_generos", "1")
                    }
                }
                is FormatoFilter -> url.addQueryParameterIfNotEmpty("formt_id", filter.selected)
                is StatusFilter -> url.addQueryParameterIfNotEmpty("stt_id", filter.selected)
                is SortFilter -> url.addQueryParameterIfNotEmpty("orderBy", filter.selected)
                is TagsFilter -> {
                    filter.state.filter { it.state }.forEach { tag ->
                        url.addQueryParameter("tag_ids", tag.value)
                    }
                }
                else -> {}
            }
        }

        return client.get(url.build()).parseMangasPage()
    }

    // URL search was never supported here; null keeps a pasted link an empty result instead of KeiSource's default exception.
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = null

    private fun Response.parseMangasPage(): MangasPage {
        val dto = parseAs<GreenShitListDto<List<GreenShitMangaDto>>>()
        val mangas = dto.obras.map { it.toSManga(cdnApiUrl) }
        return MangasPage(mangas, hasNextPage = dto.hasNextPage)
    }

    // =========================== Details & Chapters =======================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url.replace("obra/", "obras/")}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = manga.url.substringAfter("/obra/").substringBefore("/")
        val dto = client.get("$apiUrl/obras/$id").parseAs<GreenShitMangaDto>()
        return SMangaUpdate(
            manga = dto.toSManga(cdnApiUrl, isDetails = true),
            chapters = dto.chapters
                .map { it.toSChapter() }
                .distinctBy { it.url }
                .sortedByDescending { it.chapter_number },
        )
    }

    // ================================ Pages ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfter("/capitulo/")
        val response = client.get("$apiUrl/capitulos/$chapterId", ensureSuccess = false)
        if (response.code == 403) {
            val message = runCatching { response.parseAs<GreenShitErrorDto>().message }
                .getOrElse { "Faça login" }
            response.close()
            throw Exception(message)
        }
        return response.parseAs<GreenShitChapterDetailDto>().toPageList(cdnUrl)
    }

    // =============================== Filters ==============================

    override val supportsFilterFetching get() = genresList.isEmpty() && tagsList.isEmpty()

    override suspend fun fetchFilterData(): JsonElement = client.get("$apiUrl/obras/filtros").parseAs<JsonElement>()

    override fun getFilterList(data: JsonElement?): FilterList {
        if (!supportsFilters) return FilterList()

        val filters = mutableListOf<Filter<*>>()

        if (formatsList.isNotEmpty()) filters.add(FormatoFilter(formatsList))
        if (statusList.isNotEmpty()) filters.add(StatusFilter(statusList))
        if (ordersList.isNotEmpty()) filters.add(SortFilter(ordersList))

        val fetched = data?.parseAs<GreenShitFiltersDto>()
        val currentGenres = if (genresList.isNotEmpty()) genresList.toList() else fetched?.genresList.orEmpty()
        val currentTags = if (tagsList.isNotEmpty()) tagsList.toList() else fetched?.tagsList.orEmpty()

        if (currentGenres.isNotEmpty()) {
            filters.add(GenresFilter(currentGenres.map { CheckBoxFilter(it.first, it.second) }))
        }
        if (currentTags.isNotEmpty()) {
            filters.add(TagsFilter(currentTags.map { CheckBoxFilter(it.first, it.second) }))
        }

        return FilterList(filters)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = emailPreferenceKey
            title = "Email"
            summary = "Email para login automático"
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = passwordPreferenceKey
            title = "Senha"
            summary = "Senha para login automático"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    private fun authIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)

        val token = getValidToken() ?: return chain.proceed(request)
        val response = chain.proceed(request.withAuth(token))

        if (response.code != 401) return response
        response.close()
        val newToken = clearTokenAndRefresh()
        return chain.proceed(request.withAuth(newToken.orEmpty()))
    }

    private fun Request.withAuth(token: String): Request = if (token.isNotEmpty()) {
        newBuilder().header("Authorization", "Bearer $token").build()
    } else {
        this
    }

    @Synchronized
    private fun clearTokenAndRefresh(): String? {
        cachedToken = null
        tokenExpiryTime = 0L
        return getValidToken()
    }

    @Synchronized
    private fun getValidToken(): String? {
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpiryTime) {
            return cachedToken
        }
        return fetchNewToken()
    }

    @Synchronized
    private fun fetchNewToken(): String? {
        return try {
            val email = preferences.getString(emailPreferenceKey, "")
            val password = preferences.getString(passwordPreferenceKey, "")
            if (email.isNullOrEmpty() || password.isNullOrEmpty()) {
                return null
            }
            return loginAndGetToken(email, password)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    protected open fun loginAndGetToken(email: String, password: String): String? {
        try {
            val body = GreenShitLoginRequestDto(
                login = email.trim(),
                senha = password,
                tipoUsuario = "usuario",
            ).toJsonRequestBody()

            val headers = headersBuilder().set("Accept", "application/json").build()

            val response = network.client.newCall(
                POST("$apiUrl/auth/login", headers, body),
            ).execute()

            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val auth = response.parseAs<GreenShitLoginResponseDto>()
            val token = auth.accessToken
            cachedToken = token
            tokenExpiryTime = System.currentTimeMillis() + (auth.expiresIn * 1000)
            return token
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun HttpUrl.Builder.addQueryParameterIfNotEmpty(name: String, value: String?): HttpUrl.Builder {
        if (!value.isNullOrEmpty()) {
            addQueryParameter(name, value)
        }
        return this
    }
}
