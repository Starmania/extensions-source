package eu.kanade.tachiyomi.extension.all.koharu

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
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
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebViewBlocking
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Koharu :
    KeiSource(),
    ConfigurableSource {

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val searchLang: String
        get() = when (lang) {
            "en" -> "english"
            "ja" -> "japanese"
            "zh" -> "chinese"
            else -> ""
        }

    private val apiUrl = API_DOMAIN

    private val apiBooksUrl = "$apiUrl/books"

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = replace(shortenTitleRegex, "").trim()

    private fun quality() = preferences.getString(PREF_IMAGERES, "1280")!!

    private fun remadd() = preferences.getBoolean(PREF_REM_ADD, false)

    private fun alwaysExcludeTags() = preferences.getString(PREF_EXCLUDE_TAGS, "")

    private var domainUrlCache: String? = null
    private val domainUrl: String
        get() {
            return domainUrlCache ?: run {
                val domain = getDomain()
                domainUrlCache = domain
                domain
            }
        }

    private fun getDomain(): String {
        try {
            val noRedirectClient = client.newBuilder().followRedirects(false).build()
            val host = noRedirectClient.newCall(Request.Builder().url(baseUrl).headers(headers).build()).execute()
                .headers["Location"]?.toHttpUrlOrNull()?.host
                ?: return baseUrl
            return "https://$host"
        } catch (_: Exception) {
            return baseUrl
        }
    }

    private val lazyHeaders by lazy {
        headersBuilder()
            .set("Referer", "$domainUrl/")
            .set("Origin", domainUrl)
            .build()
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    private val clearanceClient by lazy {
        client.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url
                val clearance = getClearance(chain.call())
                    ?: throw IOException("Open webview to refresh token")

                val newUrl = url.newBuilder()
                    .setQueryParameter("crt", clearance)
                    .build()
                val newRequest = request.newBuilder()
                    .url(newUrl)
                    .build()

                val response = chain.proceed(newRequest)

                if (response.code !in listOf(400, 403)) {
                    return@addInterceptor response
                }
                response.close()
                clearanceToken = null
                throw IOException("Open webview to refresh token")
            }
            .build()
    }

    private var clearanceToken: String? = null

    // The site stores the token in localStorage once its challenge is passed, which may need
    // the user to solve it in WebView; a timeout just means there is no token yet.
    private fun getClearance(call: Call): String? {
        clearanceToken?.also { return it }
        clearanceToken = try {
            runWebViewBlocking(call, timeout = 10.seconds) {
                blockImages = true
                onPageFinished {
                    evaluateJs("window.localStorage.getItem('clearance')") { clearance ->
                        resolve(clearance.takeUnless { it == "null" }?.removeSurrounding("\""))
                    }
                }
                loadUrl("$domainUrl/")
            }
        } catch (_: WebViewTimeoutException) {
            null
        }
        return clearanceToken
    }

    private fun getManga(book: Entry) = SManga.create().apply {
        setUrlWithoutDomain("${book.id}/${book.key}")
        title = if (remadd()) book.title.shortenTitle() else book.title
        thumbnail_url = book.thumbnail.path
    }

    private suspend fun getImagesByMangaData(entry: MangaData, entryId: String, entryKey: String): Pair<ImagesInfo, String> {
        val data = entry.data
        fun getIPK(
            ori: DataKey?,
            alt1: DataKey?,
            alt2: DataKey?,
            alt3: DataKey?,
            alt4: DataKey?,
        ): Pair<Int?, String?> = Pair(
            ori?.id ?: alt1?.id ?: alt2?.id ?: alt3?.id ?: alt4?.id,
            ori?.key ?: alt1?.key ?: alt2?.key ?: alt3?.key ?: alt4?.key,
        )
        val (id, public_key) = when (quality()) {
            "1600" -> getIPK(data.`1600`, data.`1280`, data.`0`, data.`980`, data.`780`)
            "1280" -> getIPK(data.`1280`, data.`1600`, data.`0`, data.`980`, data.`780`)
            "980" -> getIPK(data.`980`, data.`1280`, data.`0`, data.`1600`, data.`780`)
            "780" -> getIPK(data.`780`, data.`980`, data.`0`, data.`1280`, data.`1600`)
            else -> getIPK(data.`0`, data.`1600`, data.`1280`, data.`980`, data.`780`)
        }

        if (id == null || public_key == null) {
            throw Exception("No Images Found")
        }

        val realQuality = when (id) {
            data.`1600`?.id -> "1600"
            data.`1280`?.id -> "1280"
            data.`980`?.id -> "980"
            data.`780`?.id -> "780"
            else -> "0"
        }

        return clearanceClient.get("$apiBooksUrl/data/$entryId/$entryKey/$id/$public_key/$realQuality", lazyHeaders)
            .parseAs<ImagesInfo>() to realQuality
    }

    // Latest

    override suspend fun getLatestUpdates(page: Int): MangasPage = client.get(
        apiBooksUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())

            val terms: MutableList<String> = mutableListOf()
            if (lang != "all") terms += "language:\"^$searchLang$\""
            val alwaysExcludeTags = alwaysExcludeTags()?.split(",")
                ?.map { it.trim() }?.filter(String::isNotBlank) ?: emptyList()
            if (alwaysExcludeTags.isNotEmpty()) {
                terms += "tag:\"${alwaysExcludeTags.joinToString(",") { "-$it" }}\""
            }
            if (terms.isNotEmpty()) addQueryParameter("s", terms.joinToString(" "))
        }.build(),
        lazyHeaders,
    ).parseAs<Books>().toMangasPage()

    // Popular

    override suspend fun getPopularManga(page: Int): MangasPage = client.get(
        apiBooksUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", "8")
            addQueryParameter("page", page.toString())

            val terms: MutableList<String> = mutableListOf()
            if (lang != "all") terms += "language:\"^$searchLang$\""
            val alwaysExcludeTags = alwaysExcludeTags()?.split(",")
                ?.map { it.trim() }?.filter(String::isNotBlank) ?: emptyList()
            if (alwaysExcludeTags.isNotEmpty()) {
                terms += "tag:\"${alwaysExcludeTags.joinToString(",") { "-$it" }}\""
            }
            if (terms.isNotEmpty()) addQueryParameter("s", terms.joinToString(" "))
        }.build(),
        lazyHeaders,
    ).parseAs<Books>().toMangasPage()

    private fun Books.toMangasPage() = MangasPage(entries.map(::getManga), page * limit < total)

    // Search

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.size < 3 || url.pathSegments[0] != "g") return null
        return getMangaDetail("${url.pathSegments[1]}/${url.pathSegments[2]}")
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.startsWith(PREFIX_ID_KEY_SEARCH)) {
            val manga = getMangaDetail(query.removePrefix(PREFIX_ID_KEY_SEARCH))
            return MangasPage(listOf(manga), false)
        }

        val url = apiBooksUrl.toHttpUrl().newBuilder().apply {
            val terms: MutableList<String> = mutableListOf()
            val includedTags: MutableList<Int> = mutableListOf()
            val excludedTags: MutableList<Int> = mutableListOf()

            if (lang != "all") terms += "language:\"^$searchLang$\""
            val alwaysExcludeTags = alwaysExcludeTags()?.split(",")
                ?.map { it.trim() }?.filter(String::isNotBlank) ?: emptyList()
            if (alwaysExcludeTags.isNotEmpty()) {
                terms += "tag:\"${alwaysExcludeTags.joinToString(",") { "-$it" }}\""
            }

            filters.forEach { filter ->
                when (filter) {
                    is KoharuFilters.SortFilter -> addQueryParameter("sort", filter.getValue())

                    is KoharuFilters.CategoryFilter -> {
                        val activeFilter = filter.state.filter { it.state }
                        if (activeFilter.isNotEmpty()) {
                            addQueryParameter("cat", activeFilter.sumOf { it.value }.toString())
                        }
                    }

                    is KoharuFilters.TagFilter -> {
                        includedTags += filter.state
                            .filter { it.isIncluded() }
                            .map { it.id }
                        excludedTags += filter.state
                            .filter { it.isExcluded() }
                            .map { it.id }
                    }

                    is KoharuFilters.GenreConditionFilter -> {
                        if (filter.state > 0) {
                            addQueryParameter(filter.param, filter.toUriPart())
                        }
                    }

                    is KoharuFilters.TextFilter -> {
                        if (filter.state.isNotEmpty()) {
                            val tags = filter.state.split(",").filter(String::isNotBlank).joinToString(",")
                            if (tags.isNotBlank()) {
                                terms += "${filter.type}:" + if (filter.type == "pages") tags else "\"$tags\""
                            }
                        }
                    }

                    else -> {}
                }
            }

            if (includedTags.isNotEmpty()) {
                addQueryParameter("include", includedTags.joinToString(","))
            }
            if (excludedTags.isNotEmpty()) {
                addQueryParameter("exclude", excludedTags.joinToString(","))
            }

            if (query.isNotEmpty()) terms.add("title:\"$query\"")
            if (terms.isNotEmpty()) addQueryParameter("s", terms.joinToString(" "))
            addQueryParameter("page", page.toString())
        }.build()

        return client.get(url, lazyHeaders).parseAs<Books>().toMangasPage()
    }

    // Filters

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$apiBooksUrl/tags/filters", lazyHeaders).parseAs<JsonElement>()

    override fun getFilterList(data: JsonElement?): FilterList = KoharuFilters.getFilters(data?.parseAs<List<Filter>>()?.map { it.toTag() })

    // Details

    private suspend fun getMangaDetail(url: String): SManga = client.get("$apiBooksUrl/detail/$url", lazyHeaders).parseAs<MangaDetail>().toSMangaWithUrl()

    private fun MangaDetail.toSMangaWithUrl() = toSManga().apply {
        setUrlWithoutDomain("$id/$key")
        title = if (remadd()) this@toSMangaWithUrl.title.shortenTitle() else this@toSMangaWithUrl.title
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/g/${manga.url}"

    // Chapter

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val detail = client.get("$apiBooksUrl/detail/${manga.url}", lazyHeaders).parseAs<MangaDetail>()
        val chapter = SChapter.create().apply {
            name = "Chapter"
            url = "${detail.id}/${detail.key}"
            date_upload = (detail.updated_at ?: detail.created_at)
        }
        return SMangaUpdate(detail.toSMangaWithUrl(), listOf(chapter))
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/g/${chapter.url}"

    // Page List

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val mangaData = clearanceClient.post("$apiBooksUrl/detail/${chapter.url}", lazyHeaders, FormBody.Builder().build())
            .parseAs<MangaData>()
        val (entryId, entryKey) = chapter.url.split("/")
        val imagesInfo = getImagesByMangaData(mangaData, entryId, entryKey)

        return imagesInfo.first.entries.mapIndexed { index, image ->
            Page(index, imageUrl = "${imagesInfo.first.base}/${image.path}?w=${imagesInfo.second}")
        }
    }

    override fun imageRequest(page: Page): Request = Request.Builder().url(page.imageUrl!!).headers(lazyHeaders).build()

    override val supportsRelatedMangas = true

    override suspend fun fetchRelatedMangaList(manga: SManga) = clearanceClient.post("$apiBooksUrl/detail/${manga.url}", lazyHeaders, FormBody.Builder().build())
        .parseAs<MangaData>()
        .similar.map(::getManga)

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_IMAGERES
            title = "Image Resolution"
            entries = arrayOf("780x", "980x", "1280x", "1600x", "Original")
            entryValues = arrayOf("780", "980", "1280", "1600", "0")
            summary = "%s"
            setDefaultValue("1280")
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_REM_ADD
            title = "Remove additional information in title"
            summary = "Remove anything in brackets from manga titles.\n" +
                "Reload manga to apply changes to loaded manga."
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_EXCLUDE_TAGS
            title = "Tags to exclude from browse/search"
            summary = "Separate tags with commas (,).\n" +
                "Excluding: ${alwaysExcludeTags()}"
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_ID_KEY_SEARCH = "id:"
        private const val API_DOMAIN = "https://api.schale.network"
        private const val PREF_IMAGERES = "pref_image_quality"
        private const val PREF_REM_ADD = "pref_remove_additional"
        private const val PREF_EXCLUDE_TAGS = "pref_exclude_tags"

        internal val dateReformat = SimpleDateFormat("EEEE, d MMM yyyy HH:mm (z)", Locale.ENGLISH)
    }
}
