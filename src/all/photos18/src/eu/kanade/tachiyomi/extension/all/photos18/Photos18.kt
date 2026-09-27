package eu.kanade.tachiyomi.extension.all.photos18

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.select.Evaluator

@Source
abstract class Photos18 :
    KeiSource(),
    ConfigurableSource {

    private val baseUrlWithLang get() = if (useTrad) baseUrl else "$baseUrl/zh-hans"
    private fun String.stripLang() = removePrefix("/zh-hans")

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = followRedirects(false)

    override suspend fun getPopularManga(page: Int): MangasPage = mangaListParse(client.get("$baseUrlWithLang/sort/views?page=$page").asJsoup())

    private fun mangaListParse(document: Document): MangasPage {
        parseCategories(document)
        val mangas = document.selectFirst(Evaluator.Id("videos"))!!.children().map {
            val cardBody = it.selectFirst(Evaluator.Class("card-body"))!!
            val link = cardBody.selectFirst(Evaluator.Tag("a"))!!
            SManga.create().apply {
                url = link.attr("href").stripLang()
                title = link.ownText()
                thumbnail_url = baseUrl + it.selectFirst(Evaluator.Tag("img"))!!.attr("src")
                genre = cardBody.selectFirst(Evaluator.Tag("label"))!!.ownText()
                status = SManga.COMPLETED
                initialized = true
            }
        }
        val isLastPage = document.selectFirst(Evaluator.Class("next")).run {
            this == null || hasClass("disabled")
        }
        return MangasPage(mangas, !isLastPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = mangaListParse(client.get("$baseUrlWithLang/?page=$page").asJsoup())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrlWithLang.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())

        for (filter in filters) {
            if (filter is QueryFilter) filter.addQueryTo(url)
        }

        return mangaListParse(client.get(url.build()).asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments.dropWhile { it == "zh-hans" }
        if (segments.size != 2 || segments[0] != "v") return null

        val document = client.get("$baseUrlWithLang/v/${segments[1]}").asJsoup()
        return SManga.create().apply {
            this.url = "/v/${segments[1]}"
            title = document.selectFirst("h1.title")!!.text()
            thumbnail_url = document.selectFirst("#content img")?.attr("src")
            genre = document.selectFirst("ol.breadcrumb > li:eq(1)")?.text()
            status = SManga.COMPLETED
            initialized = true
        }
    }

    // Everything is known from the listing card and a gallery is a single chapter, so no request.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val chapter = SChapter.create().apply {
            url = manga.url
            name = "Gallery"
            chapter_number = 0f
        }
        return SMangaUpdate(manga, listOf(chapter))
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val images = document.selectFirst(Evaluator.Id("content"))!!.select(Evaluator.Tag("img"))
        return images.mapIndexed { index, image ->
            Page(index, imageUrl = image.attr("src"))
        }
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        if (categories.isEmpty()) {
            Filter.Header("Tap 'Reset' to load categories")
        } else {
            CategoryFilter(categories)
        },
    )

    private open class QueryFilter(
        name: String,
        values: Array<String>,
        private val queryName: String,
        private val queryValues: Array<String>,
        state: Int = 0,
    ) : Filter.Select<String>(name, values, state) {
        fun addQueryTo(builder: HttpUrl.Builder) = builder.addQueryParameter(queryName, queryValues[state])
    }

    private class SortFilter :
        QueryFilter(
            "Sort by",
            arrayOf("Latest", "Popular", "Trend", "Recommended", "Best"),
            "sort",
            arrayOf("created", "hits", "views", "score", "likes"),
            state = 2,
        )

    private class CategoryFilter(categories: List<Pair<String, String>>) :
        QueryFilter(
            "Category",
            categories.map { it.first }.toTypedArray(),
            "category_id",
            categories.map { it.second }.toTypedArray(),
        )

    private var categories: List<Pair<String, String>> = emptyList()

    private fun parseCategories(document: Document) {
        if (categories.isNotEmpty()) return
        val items = document.selectFirst(Evaluator.Id("w3"))!!.children()
        categories = buildList(items.size + 1) {
            add(Pair("All", ""))
            items.mapTo(this) {
                val value = it.text().substringBefore(" (")
                val queryValue = it.selectFirst(Evaluator.Tag("a"))!!.attr("href").substringAfterLast('/')
                Pair(value, queryValue)
            }
        }
    }

    private val preferences by getPreferencesLazy()

    private val useTrad get() = preferences.getBoolean("ZH_HANT", false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = "ZH_HANT"
            title = "Use Traditional Chinese"
            setDefaultValue(false)
        }.let(screen::addPreference)
    }
}
