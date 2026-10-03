package eu.kanade.tachiyomi.extension.all.pornpics

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.lib.i18n.Intl
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document

@Source
abstract class PornPics :
    KeiSource(),
    ConfigurableSource {

    private val preferences = getPreferences()

    private val intl = Intl(
        language = lang,
        baseLanguage = "en",
        availableLanguages = setOf("en", "zh"),
        classLoader = this::class.java.classLoader!!,
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        Preferences.buildPreferences(screen.context, intl).forEach(screen::addPreference)
    }

    private val mangaSelector = "#main li.thumbwook > a.rel-link"

    private fun parseMangasPage(response: Response): MangasPage {
        val url = response.request.url
        val isSearch = url.queryParameter("q") != null
        val isDefault = url.queryParameter("period") != null
        val offset = url.queryParameter("offset")!!.toInt()
        val responseAsJson = isSearch || isDefault || offset > 0

        val mangas = if (responseAsJson) {
            response.parseAs<List<MangaDto>>().map {
                SManga.create().apply {
                    setUrlWithoutDomain(it.url)
                    title = it.desc
                    thumbnail_url = it.thumbnailUrl
                }
            }
        } else {
            response.asJsoup().select(mangaSelector).map {
                val imgEl = it.selectFirst("img")!!
                SManga.create().apply {
                    setUrlWithoutDomain(it.absUrl("href"))
                    title = imgEl.attr("alt")
                    thumbnail_url = imgEl.absUrl("data-src")
                }
            }
        }
        // response may be []. Add +1 to requested image count per page;
        // compare actual received count with pageSize to determine next page.
        val hasNextPage = mangas.size > QUERY_PAGE_SIZE
        val readerMangas = if (hasNextPage) mangas.dropLast(1) else mangas
        return MangasPage(readerMangas, hasNextPage)
    }

    override suspend fun getPopularManga(page: Int) = parseMangasPage(client.get(buildMangasPageUrl(page, popular = true)))

    override suspend fun getLatestUpdates(page: Int) = parseMangasPage(client.get(buildMangasPageUrl(page, popular = false)))

    private fun buildMangasPageUrl(page: Int, popular: Boolean): HttpUrl {
        val categoryOption = Preferences.getCategoryOption(preferences)
        if (Preferences.DEFAULT_CATEGORY_OPTION == categoryOption) {
            // the source of is the options under the pics menu in the nav bar
            val period = if (popular) 1 else 2
            val categoryId = 2585 + period
            return "$baseUrl/popular/api/galleries/list/".toHttpUrl().newBuilder()
                .addQueryParameterPage(page)
                .addQueryParameter("lang", intl.chosenLanguage)
                .addQueryParameter("period", period)
                .addQueryParameter("category_id", categoryId)
                .build()
        }

        // the source is the options under the categories/tags/pornstars/channels menu in the nav bar
        val requestBaseUrl = if (popular) "$baseUrl/$categoryOption/" else "$baseUrl/$categoryOption/recent/"
        return requestBaseUrl.toHttpUrl().newBuilder()
            .addQueryParameterPage(page)
            .addQueryParameter("lang", intl.chosenLanguage)
            .build()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || "galleries" !in url.pathSegments) return null
        val document = client.get(url).asJsoup()
        return SManga.create().apply {
            setUrlWithoutDomain(url.toString())
            thumbnail_url = document.selectFirst("$mangaSelector img")?.absUrl("data-src")
            applyDetails(document)
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (fetchDetails) {
            manga.applyDetails(client.get(getMangaUrl(manga)).asJsoup())
        }
        val chapter = SChapter.create().apply {
            chapter_number = 0F
            setUrlWithoutDomain(manga.url)
            name = intl["chapter.name.default"]
        }
        return SMangaUpdate(manga, listOf(chapter))
    }

    private fun SManga.applyDetails(document: Document) {
        val thumbEl = document.selectFirst(mangaSelector)!!
        val imgEl = thumbEl.selectFirst("img")!!
        val infoEl = document.selectFirst("div.gallery-info.to-gall-info")

        title = imgEl.attr("alt")
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        status = SManga.COMPLETED
        author = infoEl?.select("div.gallery-info__item:nth-child(2) a")?.joinToString { it.text() }
        genre = infoEl?.select("div.gallery-info__item:not(:nth-child(2)) a")?.joinToString { it.text() }
        description = infoEl?.selectFirst("div.gallery-info__item:nth-child(4)")?.text()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter)).asJsoup().select(mangaSelector).mapIndexed { index, element ->
        Page(index, imageUrl = element.absUrl("href"))
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = if (query.isBlank()) {
            buildCategoryUrl(page, filters)
        } else {
            buildSearchUrl(page, query, filters)
        }
        return parseMangasPage(client.get(url))
    }

    private fun buildCategoryUrl(page: Int, filters: FilterList): HttpUrl {
        val activeCategoryTypeOption = filters.firstInstance<Filters.ActiveCategoryTypeSelector>()
        val categoryOption = activeCategoryTypeOption.selectedCategoryOption(filters)
        val sortOption = filters.firstInstance<Filters.SortSelector>()
        val builder = baseUrl.toHttpUrl().newBuilder()
            .addUrlPart(categoryOption.toUrlPart())
            .addQueryParameter("lang", intl.chosenLanguage)
            .addUrlPart(sortOption.toUriPart(), addPath = !categoryOption.useSearch())
            .addQueryParameterPage(page)
        return builder.build()
    }

    private fun buildSearchUrl(page: Int, query: String, filters: FilterList): HttpUrl {
        val sortOption = filters.firstInstance<Filters.SortSelector>()
        val builder = "$baseUrl/search/srch.php".toHttpUrl().newBuilder()
            .addQueryParameter("lang", intl.chosenLanguage)
            .addUrlPart(sortOption.toUriPart(), addPath = false)
            .addQueryParameterPage(page)
            .addQueryParameter("q", query)
        return builder.build()
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filters.createSortSelector(intl),
        Filter.Separator(),
        Filter.Header(intl["filter.header.ignored-when-search"]),
        Filter.Separator(),
        Filter.Header(intl["filter.header.select-active-category-type"]),
        Filters.createActiveCategoryTypeSelector(intl),
        Filter.Separator(),
        Filter.Header(intl["filter.header.select-category-type-param"]),
        Filters.createRecommendSelector(intl),
        Filters.createCategorySelector(intl),
        Filters.createTagSelector(intl),
        Filters.createPornStarSelector(intl),
        Filters.createChannelSelector(intl),
    )
}
