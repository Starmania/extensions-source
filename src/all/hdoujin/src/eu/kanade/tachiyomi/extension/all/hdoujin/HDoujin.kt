package eu.kanade.tachiyomi.extension.all.hdoujin

import CategoryFilter
import SelectFilter
import TagType
import TextFilter
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.extension.all.hdoujin.Entries.Entry
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import getFilters
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebViewBlocking
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException
import kotlin.time.Duration.Companion.seconds

@Source
abstract class HDoujin :
    KeiSource(),
    ConfigurableSource {

    private val siteLang: String
        get() = when (lang) {
            "en" -> "english"
            "es" -> "spanish"
            "ja" -> "japanese"
            "ko" -> "korean"
            "zh" -> "chinese"
            else -> lang
        }

    private val preferences = getPreferences()
    private fun quality() = preferences.getString(PREF_IMAGE_RES, "1280")!!
    private fun remadd() = preferences.getBoolean(PREF_REM_ADD, false)
    private fun alwaysIncludeTags() = preferences.getString(PREF_INCLUDE_TAGS, "")
    private fun alwaysExcludeTags() = preferences.getString(PREF_EXCLUDE_TAGS, "")
    private fun getTagsPreference(): String {
        val include = alwaysIncludeTags()
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotBlank)

        val exclude = alwaysExcludeTags()
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.map { "-$it" }

        val tags: List<String> = include?.plus(exclude ?: emptyList()) ?: exclude?.plus(include ?: emptyList()) ?: emptyList()
        if (tags.isNotEmpty()) {
            val tagGroups: Map<String, Set<String>> = tags
                .groupBy {
                    val tag = it.removePrefix("-")
                    val parts = tag.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].isNotBlank()) parts[0] else "tag"
                }
                .mapValues { (_, values) ->
                    values.mapTo(mutableSetOf()) {
                        val tag = it.removePrefix("-").split(":").last().trim()
                        if (it.startsWith("-")) "-$tag" else tag
                    }
                }

            return tagGroups.entries.joinToString(" ") { (key, values) ->
                "$key:\"${values.joinToString(",")}\""
            }
        }
        return ""
    }

    private val baseApiUrl: String get() = "https://api." + baseUrl.removePrefix("https://")
    private val bookApiUrl: String get() = "$baseApiUrl/books"

    private var cachedClearance: String? = null

    private fun getClearance(call: Call): String? = cachedClearance ?: try {
        runWebViewBlocking<String?>(call, timeout = 10.seconds) {
            onPageFinished {
                evaluateJs("localStorage.getItem('clearance')") { value ->
                    resolve(value.parseAs<String?>())
                }
            }
            loadData(baseUrl, "")
        }.also { cachedClearance = it }
    } catch (_: Exception) {
        null
    }

    private val clearanceClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val clearance = getClearance(chain.call())
                ?: throw IOException("Open webview to refresh token")

            val newUrl = request.url.newBuilder()
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
            cachedClearance = null
            throw IOException("Open webview to refresh token")
        }
        .rateLimit(3)
        .build()

    // ============================ Popular + Latest =========================

    override suspend fun getPopularManga(page: Int) = getMangaList(page, sort = "8")

    override suspend fun getLatestUpdates(page: Int) = getMangaList(page)

    private suspend fun getMangaList(page: Int, sort: String? = null): MangasPage {
        val url = bookApiUrl.toHttpUrl().newBuilder().apply {
            sort?.let { addQueryParameter("sort", it) }
            addQueryParameter("page", page.toString())

            val tags = getTagsPreference()
            val terms = mutableListOf<String>()
            if (lang != "all") terms += "language:\"^$siteLang\""
            if (tags.isNotBlank()) terms += tags

            if (terms.isNotEmpty()) addQueryParameter("s", terms.joinToString(" "))
        }.build()

        val data = client.get(url).parseAs<Entries>()
        return MangasPage(
            mangas = data.entries.map(Entry::toSManga),
            hasNextPage = data.limit * data.page < data.total,
        )
    }

    // ================================ Search ================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = bookApiUrl.toHttpUrl().newBuilder().apply {
            val terms = mutableListOf(query.trim())

            if (lang != "all") terms += "language:\"^$siteLang$\""
            filters.forEach { filter ->
                when (filter) {
                    is SelectFilter -> {
                        val value = filter.selected
                        if (value == "popular") {
                            addPathSegment(value)
                        } else {
                            addQueryParameter("sort", value)
                        }
                    }

                    is CategoryFilter -> {
                        val activeFilter = filter.state.filter { it.state }
                        if (activeFilter.isNotEmpty()) {
                            addQueryParameter("cat", activeFilter.sumOf { it.value }.toString())
                        }
                    }

                    is TextFilter -> {
                        if (filter.state.isNotEmpty()) {
                            val tags = filter.state.split(",").filter(String::isNotBlank).joinToString(",")
                            if (tags.isNotBlank()) {
                                terms += "${filter.type}:${if (filter.type == "pages") tags else "\"$tags\""}"
                            }
                        }
                    }

                    is TagType -> {
                        if (filter.state > 0) {
                            addQueryParameter(
                                filter.type,
                                when {
                                    filter.type == "i" && filter.state == 0 -> ""
                                    filter.type == "e" && filter.state == 0 -> "1"
                                    else -> ""
                                },
                            )
                        }
                    }

                    else -> {}
                }
            }
            if (query.isNotEmpty()) terms.add("title:\"$query\"")
            if (terms.isNotEmpty()) addQueryParameter("s", terms.joinToString(" "))
            addQueryParameter("page", page.toString())
        }.build()

        val data = client.get(url).parseAs<Entries>()
        return MangasPage(
            mangas = data.entries.map(Entry::toSManga),
            hasNextPage = data.limit * data.page < data.total,
        )
    }

    override fun getFilterList(data: JsonElement?): FilterList = getFilters()

    // ============================ Details + Chapters ========================

    override fun getMangaUrl(manga: SManga) = "$baseUrl/g/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments
        val index = segments.indexOf("g")
        if (index == -1 || index + 2 >= segments.size) return null

        val manga = SManga.create().apply { this.url = "${segments[index + 1]}/${segments[index + 2]}" }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaDetail = client.get("$bookApiUrl/detail/${manga.url}").parseAs<MangaDetail>()

        return SMangaUpdate(
            manga = mangaDetail.toSManga().apply {
                setUrlWithoutDomain("${mangaDetail.id}/${mangaDetail.key}")
                title = if (remadd()) {
                    mangaDetail.title_short ?: mangaDetail.title.shortenTitle()
                } else {
                    mangaDetail.title
                }
            },
            chapters = listOf(
                SChapter.create().apply {
                    name = "Chapter"
                    url = "${mangaDetail.id}/${mangaDetail.key}"
                    date_upload = mangaDetail.updated_at ?: mangaDetail.created_at
                },
            ),
        )
    }

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = replace(shortenTitleRegex, "").trim()

    // ================================= Pages =================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (entryId, entryKey) = chapter.url.split("/", limit = 2)
        val mangaData = clearanceClient.post("$bookApiUrl/detail/${chapter.url}", headers, "".toRequestBody()).parseAs<MangaData>()
        val (imagesInfo, realQuality) = getImagesByMangaData(mangaData, entryId, entryKey)

        return imagesInfo.entries.mapIndexed { index, image ->
            Page(index, imageUrl = "${imagesInfo.base}/${image.path}?w=$realQuality")
        }
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

        val imagesInfo = clearanceClient.get("$bookApiUrl/data/$entryId/$entryKey/$id/$public_key/$realQuality", headers).parseAs<ImagesInfo>()
        return imagesInfo to realQuality
    }

    // ================================ Settings ================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_IMAGE_RES
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
            key = PREF_INCLUDE_TAGS
            title = "Tags to include from browse/search"
            summary = "Separate tags with commas (,).\n" +
                "Excluding: ${alwaysIncludeTags()}"
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_EXCLUDE_TAGS
            title = "Tags to exclude from browse/search"
            summary = "Separate tags with commas (,). Supports tag types (females, male, etc), defaults to 'tag' if not specified.\n" +
                "Example: 'ai generated, female:hairy, male:hairy'\n" +
                "Excluding: ${alwaysExcludeTags()}"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_REM_ADD = "pref_remove_additional"
        private const val PREF_IMAGE_RES = "pref_image_quality"
        private const val PREF_INCLUDE_TAGS = "pref_include_tags"
        private const val PREF_EXCLUDE_TAGS = "pref_exclude_tags"
    }
}
