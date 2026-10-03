package eu.kanade.tachiyomi.extension.pt.rfdragonscan

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
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
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

@Source
abstract class RFDragonScan :
    KeiSource(),
    ConfigurableSource {

    override val supportsLatest = false

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient() = addInterceptor(::loginInterceptor)
        .addInterceptor(::migrationInterceptor)
        .rateLimit(2)

    private val apiHeaders by lazy {
        headersBuilder().add("Rsc", "1").build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseProjects(client.get("$baseUrl/projetos?page=$page", apiHeaders))

    override suspend fun getLatestUpdates(page: Int) = throw UnsupportedOperationException()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/projetos".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("term", query)
        }

        return parseProjects(client.get(url.build(), apiHeaders))
    }

    private fun parseProjects(response: Response): MangasPage {
        val dto = response.extractNextJs<ProjectsPageDto>()
            ?: return MangasPage(emptyList(), false)

        val mangas = dto.projects.map { it.toSManga() }

        return MangasPage(mangas, dto.pagination?.hasNextPage == true)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!UUID_REGEX.matches(url.encodedPath)) return null
        val manga = SManga.create().apply {
            this.url = "/" + url.pathSegments.take(2).joinToString("/")
        }
        return getDetails(manga)
    }

    override fun getMangaUrl(manga: SManga): String {
        if (!UUID_REGEX.matches(manga.url)) {
            return "$baseUrl/projetos?term=${manga.url.trim('/').split('/').last()}"
        }
        return baseUrl + manga.url
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val details = async { if (fetchDetails) getDetails(manga) else manga }
        val chapterList = async { if (fetchChapters) getChapters(manga) else chapters }

        SMangaUpdate(details.await(), chapterList.await())
    }

    // Entries saved before the site moved to /<uuid>/<slug> urls are resolved by migrationInterceptor.
    private suspend fun projectAction(manga: SManga, legacyPath: String, actionId: String): Response {
        if (!UUID_REGEX.matches(manga.url)) {
            return client.get("$baseUrl/$legacyPath${manga.url}", apiHeaders)
        }
        val pathSegments = manga.url.trim('/').split('/').filter { it.isNotEmpty() }
        val mangaId = pathSegments[0]
        val mangaSlug = pathSegments[1]

        val payload = "[\"$mangaId\",\"$mangaSlug\"]"
        val requestBody = payload.toRequestBody("text/plain;charset=UTF-8".toMediaType())

        val stateTree = """["",{"children":[["projectId","$mangaId","d"],{"children":[["linkId","$mangaSlug","d"],{"children":["__PAGE__",{},null,null]},null,null]}]},null,null,true]"""

        return client.post(
            baseUrl + manga.url,
            actionHeaders(actionId, baseUrl + manga.url, stateTree),
            requestBody,
        )
    }

    private suspend fun getDetails(manga: SManga): SManga {
        val response = projectAction(manga, "migrate", DETAILS_ACTION_ID)

        val dto = response.use { it.body.string() }.extractNextJsRsc<MangaDetailsDto> {
            it is JsonObject && "synopsis" in it && "title" in it
        } ?: throw IOException("Manga details not found")

        return dto.toSManga().apply { url = manga.url }
    }

    private suspend fun getChapters(manga: SManga): List<SChapter> {
        val response = projectAction(manga, "migrate-chapters", CHAPTERS_ACTION_ID)

        // After a legacy-url migration the response belongs to the rewritten request.
        val pathSegments = response.request.url.pathSegments.filter { it.isNotEmpty() }
        val mangaId = pathSegments[pathSegments.size - 2]
        val mangaSlug = pathSegments.last()

        val seasonList = response.use { it.body.string() }.extractNextJsRsc<SeasonListDto> {
            it is JsonObject && "groups" in it
        } ?: throw IOException("Chapters not found")

        val chapters = mutableListOf<SChapter>()

        seasonList.groups?.forEach { group ->
            group.chapters?.forEach { ch ->
                if (ch.isUpcoming == true || ch.hasRestriction == true) {
                    return@forEach
                }
                chapters.add(ch.toSChapter(mangaId, mangaSlug))
            }
        }

        return chapters.sortedByDescending {
            it.name.substringAfter("Capítulo ").toFloatOrNull() ?: 0f
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pathSegments = chapter.url.trim('/').split('/').filter { it.isNotEmpty() }
        val mangaId = pathSegments[0]
        val mangaSlug = pathSegments[1]
        val chapterTitle = pathSegments[3]

        val payload = "[\"$mangaId\",\"$chapterTitle\"]"
        val requestBody = payload.toRequestBody("text/plain;charset=UTF-8".toMediaType())

        val stateTree = """["",{"children":[["projectId","$mangaId","d"],{"children":[["linkId","$mangaSlug","d"],{"children":["capitulo",{"children":[["chapterId","$chapterTitle","d"],{"children":["__PAGE__",{},null,null]}]}]}]}]},null,null,true]"""

        val response = client.post(
            baseUrl + chapter.url,
            actionHeaders("60390ae612bb67d3d0614b47c7fa396fa4201aa323", baseUrl + chapter.url, stateTree),
            requestBody,
        )

        val dto = response.use { it.body.string() }.extractNextJsRsc<PagesDto> {
            it is JsonObject && "pages" in it
        } ?: throw IOException("Pages not found")

        return dto.toPages()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = EMAIL_PREF
            title = "Email"
            summary = "Email utilizado para login no RF Dragon Scan"
            dialogTitle = "Email"
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "Senha"
            summary = "Senha utilizada para login no RF Dragon Scan"
            dialogTitle = "Senha"
        }.also(screen::addPreference)
    }

    private var actionIdCache: String? = null

    private fun getActionId(): String {
        actionIdCache?.let { return it }

        val html = network.client.newCall(GET("$baseUrl/login", headers)).execute().use { it.body.string() }

        ACTION_ID_HTML_REGEX.find(html)?.let {
            val id = it.groupValues[1]
            actionIdCache = id
            return id
        }

        val chunkUrls = CHUNK_URL_REGEX.findAll(html)
            .map { it.groupValues[1] }
            .toList()

        for (url in chunkUrls) {
            try {
                network.client.newCall(GET(baseUrl + url, headers)).execute().use { res ->
                    val js = res.body.string()
                    if (js.contains("\"login\"")) {
                        val idMatch = ACTION_ID_JS_REGEX.find(js)
                        if (idMatch != null) {
                            val id = idMatch.groupValues[1]
                            actionIdCache = id
                            return id
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore and continue searching
            }
        }

        return "600165150b15a3870c9e076c863daec8d24748e458"
    }

    private fun actionHeaders(actionId: String, referer: String, stateTree: String): Headers {
        val encodedStateTree = java.net.URLEncoder.encode(stateTree, "UTF-8")
        return headersBuilder()
            .add("next-action", actionId)
            .add("next-router-state-tree", encodedStateTree)
            .add("Accept", "text/x-component")
            .set("Referer", referer)
            .build()
    }

    private fun loginInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val cookies = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
        val isLoggedIn = cookies.any { it.name == "access_token" && it.value.isNotEmpty() }

        if (isLoggedIn) {
            val response = chain.proceed(request)
            if (response.code != 401 && response.code != 403) {
                return response
            }
            response.close()
        }

        if (request.url.pathSegments.lastOrNull() == "login") {
            return chain.proceed(request)
        }

        synchronized(this) {
            val currentCookies = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
            if (currentCookies.any { it.name == "access_token" && it.value.isNotEmpty() }) {
                return chain.proceed(request)
            }

            val email = preferences.getString(EMAIL_PREF, "") ?: ""
            val password = preferences.getString(PASSWORD_PREF, "") ?: ""

            if (email.isBlank() || password.isBlank()) {
                throw IOException("Configure seu email e senha nas configurações da extensão para acessar capítulos restritos.")
            }

            val actionId = getActionId()
            val payload = "[\"$email\",\"$password\"]"
            val loginBody = payload.toRequestBody("text/plain;charset=UTF-8".toMediaType())

            val loginHeaders = headersBuilder()
                .add("next-action", actionId)
                .add(
                    "next-router-state-tree",
                    "%5B%22%22%2C%7B%22children%22%3A%5B%22login%22%2C%7B%22children%22%3A%5B%22__PAGE__%22%2C%7B%7D%2Cnull%2Cnull%5D%7D%2Cnull%2Cnull%2Ctrue%5D%7D%2Cnull%2Cnull%2Ctrue%5D",
                )
                .add("Accept", "text/x-component")
                .set("Referer", "$baseUrl/login")
                .build()

            val loginReq = POST("$baseUrl/login", loginHeaders, loginBody)

            val success = network.client.newCall(loginReq).execute().use { loginRes ->
                if (!loginRes.isSuccessful) {
                    throw IOException("Falha no login. Verifique suas credenciais.")
                }
                client.cookieJar.loadForRequest(baseUrl.toHttpUrl()).any { it.name == "access_token" && it.value.isNotEmpty() }
            }

            if (!success) {
                throw IOException("Falha no login. Token de acesso não recebido.")
            }

            return chain.proceed(request)
        }
    }

    private fun migrationInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val firstSegment = request.url.pathSegments.firstOrNull()

        if (firstSegment == "migrate" || firstSegment == "migrate-chapters") {
            val oldPath = request.url.encodedPath
                .removePrefix("/migrate-chapters")
                .removePrefix("/migrate")
            val slug = oldPath.trim('/').split('/').last { it.isNotEmpty() }

            val searchUrl = "$baseUrl/projetos?term=$slug".toHttpUrl()
            val searchReq = GET(searchUrl, apiHeaders)
            val searchRes = chain.proceed(searchReq)

            val newUrlPath = searchRes.use { res ->
                val dto = res.body.string().extractNextJsRsc<ProjectsPageDto>()
                val project = dto?.projects?.firstOrNull { it.link == slug || it.title.contains(slug, ignoreCase = true) }
                    ?: throw IOException("Manga not found during migration")

                "/${project.id}/${project.link}"
            }

            val pathSegments = newUrlPath.trim('/').split('/')
            val mangaId = pathSegments[0]
            val mangaSlug = pathSegments[1]

            val actionId = if (firstSegment == "migrate") DETAILS_ACTION_ID else CHAPTERS_ACTION_ID

            val payload = "[\"$mangaId\",\"$mangaSlug\"]"
            val requestBody = payload.toRequestBody("text/plain;charset=UTF-8".toMediaType())

            val stateTree = """["",{"children":[["projectId","$mangaId","d"],{"children":[["linkId","$mangaSlug","d"],{"children":["__PAGE__",{},null,null]},null,null]}]},null,null,true]"""

            val actionRequest = POST(
                "$baseUrl$newUrlPath",
                actionHeaders(actionId, "$baseUrl$newUrlPath", stateTree),
                requestBody,
            )

            return chain.proceed(actionRequest)
        }

        return chain.proceed(request)
    }

    companion object {
        private const val EMAIL_PREF = "pref_email"
        private const val PASSWORD_PREF = "pref_password"

        private const val DETAILS_ACTION_ID = "60d532a2a6a7a0ff42de5f69dcdf2db5860a2f76b0"
        private const val CHAPTERS_ACTION_ID = "607bcd9f90d5db5edaa2cf1aff7a002b5b14ead30a"

        private val UUID_REGEX = Regex("^/[0-9a-fA-F\\-]{36}/.*")

        private val ACTION_ID_HTML_REGEX = Regex("""name="\x24ACTION_ID_([a-f0-9]{40})"""")
        private val CHUNK_URL_REGEX = Regex("""src="(/_next/static/chunks/[^"]+\.js)"""")
        private val ACTION_ID_JS_REGEX = Regex("""createServerReference\("([a-f0-9]{40})",.*?,"login"\)""")
    }
}
