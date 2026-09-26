package eu.kanade.tachiyomi.extension.all.danbooru

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Danbooru :
    KeiSource(),
    ConfigurableSource {

    // Make image requests mimic a standard browser <img> fetch to bypass CF 403s on the CDN
    private val cdnInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.url.host == "cdn.donmai.us") {
            val newRequest = request.newBuilder()
                .removeHeader("Cookie") // CF flags CDN requests containing main-domain session cookies
                .removeHeader("Origin") // KeiSource adds it; a browser <img> fetch never sends one
                .header("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
                .header("Sec-Fetch-Dest", "image")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", "same-site")
                .build()
            return@Interceptor chain.proceed(newRequest)
        }
        chain.proceed(request)
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(cdnInterceptor)
        rateLimit(2)
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

    private val preference by getPreferencesLazy()

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(filterOrder("created_at")))

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchUrl = "$baseUrl/pools/gallery".toHttpUrl().newBuilder()

        searchUrl.setEncodedQueryParameter("search[category]", "series")

        filters.forEach {
            when (it) {
                is FilterTags -> if (it.state.isNotBlank()) {
                    searchUrl.addQueryParameter("search[post_tags_match]", it.state)
                }
                is FilterDescription -> if (it.state.isNotBlank()) {
                    searchUrl.addQueryParameter("search[description_matches]", it.state)
                }
                is FilterIsDeleted -> if (it.state) {
                    searchUrl.addEncodedQueryParameter("search[is_deleted]", "true")
                }
                is FilterCategory -> {
                    searchUrl.setEncodedQueryParameter("search[category]", it.selected)
                }
                is FilterOrder -> if (it.selected != null) {
                    searchUrl.addEncodedQueryParameter("search[order]", it.selected)
                }
                else -> {}
            }
        }

        searchUrl.addEncodedQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            searchUrl.addQueryParameter("search[name_contains]", query)
        }

        val document = client.get(searchUrl.build()).asJsoup()

        val entries = document.select("article.post-preview").map { element ->
            SManga.create().apply {
                url = element.selectFirst(".post-preview-link")!!.attr("href")
                title = element.selectFirst("div.text-center")!!.text()

                thumbnail_url = element.selectFirst("source")?.attr("srcset")
                    ?.substringAfterLast(',')?.trim()
                    ?.substringBeforeLast(' ')?.trimStart()
            }
        }
        val hasNextPage = document.selectFirst("a.paginator-next") != null

        return MangasPage(entries, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val path = url.pathSegments
        if (path.size < 2 || path[0] != "pools") return null

        return fetchDetails("/pools/${path[1]}")
    }

    // ============================== Details ==============================

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

    private suspend fun fetchDetails(mangaUrl: String) = SManga.create().apply {
        val document = client.get(baseUrl + mangaUrl).asJsoup()

        setUrlWithoutDomain(document.location())
        title = document.selectFirst(".pool-category-series, .pool-category-collection")?.text()
            ?: document.selectFirst("h1")!!.text()
        description = document.getElementById("description")?.wholeText()
        author = document.selectFirst("#description a[href*=artists]")?.ownText()
        artist = author
        update_strategy = if (!preference.splitChaptersPref) {
            UpdateStrategy.ONLY_FETCH_ONCE
        } else {
            UpdateStrategy.ALWAYS_UPDATE
        }
    }

    // ============================= Chapters ==============================

    private suspend fun fetchChapters(mangaUrl: String): List<SChapter> {
        val data = client.get("$baseUrl$mangaUrl.json").parseAs<Pool>()

        return if (preference.splitChaptersPref) {
            data.postIds.mapIndexed { index, id ->
                SChapter.create().apply {
                    url = "/posts/$id"
                    name = "Post ${index + 1}"
                    chapter_number = index + 1f
                }
            }.reversed().apply {
                if (isNotEmpty()) {
                    this[0].date_upload = dateFormat.tryParse(data.updatedAt)
                }
            }
        } else {
            listOf(
                SChapter.create().apply {
                    url = "/pools/${data.id}"
                    name = "Oneshot"
                    date_upload = dateFormat.tryParse(data.updatedAt)
                    chapter_number = 0F
                },
            )
        }
    }

    // =============================== Pages ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get("$baseUrl${chapter.url}.json")

        return if (chapter.url.startsWith("/posts/")) {
            listOf(Page(0, imageUrl = response.parseAs<Post>().absoluteUrl()))
        } else {
            response.parseAs<Pool>().postIds.mapIndexed { index, id ->
                Page(index, url = "$baseUrl/posts/$id")
            }
        }
    }

    override suspend fun getImageUrl(page: Page): String = client.get("${page.url}.json").parseAs<Post>().absoluteUrl()

    private fun Post.absoluteUrl() = bestUrl.let { if (it.startsWith("http")) it else "$baseUrl$it" }

    // ============================== Filters ==============================

    override fun getFilterList(data: JsonElement?) = FilterList(
        FilterDescription(),
        FilterTags(),
        FilterIsDeleted(),
        FilterCategory(),
        FilterOrder(),
    )

    // ============================= Utilities =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = CHAPTER_LIST_PREF
            title = "Split posts into individual chapters"
            summary = """
                Instead of showing one 'OneShot' chapter,
                each post will be it's own chapter
            """.trimIndent()
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    private val SharedPreferences.splitChaptersPref: Boolean
        get() = getBoolean(CHAPTER_LIST_PREF, false)
}

private const val CHAPTER_LIST_PREF = "prefChapterList"
