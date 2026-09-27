package eu.kanade.tachiyomi.extension.all.myreadingmanga

import android.net.Uri
import android.webkit.URLUtil
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
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)

@Source
abstract class MyReadingManga : KeiSource() {

    private val siteLang: String
        get() = when (lang) {
            "ar" -> "Arabic"
            "id" -> "Indonesia"
            "zh" -> "Chinese"
            "en" -> "English"
            "de" -> "German"
            "it" -> "Italian"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "pt-BR" -> "Portuguese"
            "ru" -> "Russian"
            "es" -> "Spanish"
            "tr" -> "Turkish"
            "vi" -> "Vietnamese"
            else -> lang
        }

    private val latestLang: String get() = if (lang == "ja") "jp" else siteLang

    // The random X-Requested-With only reaches WebView (masking the app's package name);
    // the interceptor strips it from OkHttp requests.
    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("User-Agent", USER_AGENT)
        .add("X-Requested-With", randomString((1..20).random()))

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor { chain ->
        val request = chain.request()
        chain.proceed(request.newBuilder().removeHeader("X-Requested-With").build())
    }

    // Popular - Random manga as returned by search
    override suspend fun getPopularManga(page: Int): MangasPage = parseSearchPage(page, client.get("$baseUrl/page/$page/?s=&ep_sort=rand&ep_filter_lang=$siteLang").asJsoup())

    // Latest - Home page
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/lang/${latestLang.lowercase()}" + if (page > 1) "/page/$page/" else "").asJsoup()
        val mangas = document.select("article").map { element ->
            buildManga(element.selectFirst("a[rel]")!!, element.selectFirst("a.entry-image-link img"))
        }
        val hasNextPage = document.selectFirst("li.pagination-next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // Search
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val uri = Uri.parse("$baseUrl/page/$page/").buildUpon()
            .appendQueryParameter("s", query)
        filters.forEach { filter ->
            if (filter is UriFilter) {
                filter.addToUri(uri)
            }
            if (filter is SearchSortTypeList) {
                uri.appendQueryParameter("ep_sort", listOf("date", "date_asc", "rand", "")[filter.state])
            }
        }

        return parseSearchPage(page, client.get(uri.toString()).asJsoup())
    }

    private var mangaParsedSoFar = 0

    private fun parseSearchPage(page: Int, document: Document): MangasPage {
        if (page == 1) mangaParsedSoFar = 0
        val mangas = document.select("article").map { element ->
            buildManga(element.selectFirst("a[rel]")!!, element.selectFirst("a.entry-image-link img"))
        }.also { mangaParsedSoFar += it.count() }
        val totalResults = TOTAL_RESULTS_REGEX.find(document.selectFirst(".ep-search-count")?.text() ?: "")?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
        return MangasPage(mangas, mangaParsedSoFar < totalResults)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.substringAfter("://")) return null
        val document = client.get(url).asJsoup()
        document.selectFirst("h1") ?: return null
        return mangaDetailsParse(document).apply { setUrlWithoutDomain(document.location()) }
    }

    // Build Manga From Element
    private fun buildManga(titleElement: Element, thumbnailElement: Element?): SManga {
        val manga = SManga.create().apply {
            setUrlWithoutDomain(titleElement.absUrl("href"))
            title = cleanTitle(titleElement.text())
        }
        if (thumbnailElement != null) manga.thumbnail_url = getThumbnail(getImage(thumbnailElement))
        return manga
    }

    private fun getImage(element: Element): String? {
        val url = when {
            element.attr("data-src").contains(EXTENSION_REGEX) -> element.attr("abs:data-src")
            element.attr("data-cfsrc").contains(EXTENSION_REGEX) -> element.attr("abs:data-cfsrc")
            element.attr("src").contains(EXTENSION_REGEX) -> element.attr("abs:src")
            else -> element.attr("abs:data-lazy-src")
        }

        return if (URLUtil.isValidUrl(url)) url else null
    }

    // removes resizing
    private fun getThumbnail(thumbnailUrl: String?): String? {
        thumbnailUrl ?: return null
        val url = thumbnailUrl.substringBeforeLast("-") + "." + thumbnailUrl.substringAfterLast(".")
        return if (URLUtil.isValidUrl(url)) url else null
    }

    // cleans up the name removing author and language from the title
    private fun cleanTitle(title: String) = title.replace(TITLE_REGEX, "").substringBeforeLast("(").trim()

    private fun cleanAuthor(author: String) = author.substringAfter("[").substringBefore("]").trim()

    // Details and chapters both come from the manga page
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val details = if (fetchDetails) {
            val needCover = manga.thumbnail_url?.let { url -> client.get(url, ensureSuccess = false).use { !it.isSuccessful } } ?: true
            mangaDetailsParse(document, needCover)
        } else {
            manga
        }
        return SMangaUpdate(details, chapterListParse(document))
    }

    private suspend fun mangaDetailsParse(document: Document, needCover: Boolean = true): SManga = SManga.create().apply {
        title = cleanTitle(document.selectFirst("h1")?.text() ?: "")
        author = cleanAuthor(document.selectFirst("h1")?.text() ?: "")
        artist = author
        genre = document.select(".entry-header p a[href*=genre], [href*=tag], span.entry-categories a").joinToString { it.text() }
        val basicDescription = document.selectFirst("h1")?.text()
        // too troublesome to achieve 100% accuracy assigning scanlator group during chapterListParse
        val scanlatedBy = document.selectFirst(".entry-terms:has(a[href*=group])")
            ?.select("a[href*=group]")?.joinToString(prefix = "Scanlated by: ") { it.text() }
        val extendedDescription = document.select(".entry-content p:not(p:containsOwn(|)):not(.chapter-class + p)").joinToString("\n") { it.text() }
        description = listOfNotNull(basicDescription, scanlatedBy, extendedDescription).joinToString("\n").trim()
        status = when (document.selectFirst("a[href*=status]")?.text()) {
            "Ongoing" -> SManga.ONGOING
            "Completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        if (needCover) {
            thumbnail_url = client.get("$baseUrl/?s=${document.location()}").asJsoup()
                .selectFirst("div.ep-search-content div.entry-content img")
                ?.let { getThumbnail(getImage(it)) }
        }
    }

    private fun chapterListParse(document: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        val date = dateFormat.tryParseDate(document.selectFirst(".entry-time")?.text())
        // create first chapter since its on main manga page
        chapters.add(createChapter("1", document.location(), date, "Part 1"))
        // see if there are multiple chapters or not
        val lastChapterNumber = document.select("a[class=page-numbers]").last()?.text()?.toIntOrNull()
        if (lastChapterNumber != null) {
            // There are entries with more chapters but those never show up,
            // so we take the last one and loop it to get all hidden ones.
            // Example: 1 2 3 4 .. 7 8 9 Next
            for (i in 2..lastChapterNumber) {
                chapters.add(createChapter(i.toString(), document.location(), date, "Part $i"))
            }
        }
        chapters.reverse()
        return chapters
    }

    private fun createChapter(pageNumber: String, mangaUrl: String, date: Long, chname: String): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain("$mangaUrl/$pageNumber")
        chapter.name = chname
        chapter.date_upload = date
        return chapter
    }

    // Pages
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return (document.select("div.entry-content img") + document.select("div.separator img[data-src]"))
            .mapNotNull { getImage(it) }
            .distinct()
            .mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    // Filters: Genres, Popular Tags, Categories, Pairings and Scan Groups are scraped from the site
    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = buildJsonObject {
        FILTER_PAGES.forEach { (key, path, css) ->
            val document = client.get("$baseUrl$path").asJsoup()
            put(
                key,
                buildJsonArray {
                    document.select(css).forEach {
                        add(JsonArray(listOf(JsonPrimitive(it.text()), JsonPrimitive(it.attr("href").split("/").dropLast(1).lastOrNull() ?: ""))))
                    }
                },
            )
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val options = data?.jsonObject
        fun optionsOf(key: String): Array<Pair<String, String>> = options?.get(key)?.jsonArray
            ?.map {
                val (name, value) = it.jsonArray
                Pair(name.jsonPrimitive.content, value.jsonPrimitive.content)
            }
            ?.toTypedArray()
            ?: emptyArray()

        val filters = listOf(EnforceLanguageFilter(siteLang), SearchSortTypeList())
        options ?: return FilterList(filters)
        return FilterList(
            filters + listOf(
                GenreFilter(optionsOf("genres")),
                TagFilter(optionsOf("tags")),
                CatFilter(optionsOf("categories")),
                PairingFilter(optionsOf("pairings")),
                ScanGroupFilter(optionsOf("groups")),
            ),
        )
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"
        private val EXTENSION_REGEX = Regex("""\.(jpg|png|jpeg|webp)""")
        private val TITLE_REGEX = Regex("""\[[^]]*]""")
        private val TOTAL_RESULTS_REGEX = Regex("""([\d,]+)""")

        private val FILTER_PAGES = listOf(
            Triple("genres", "", ".tagcloud a[href*=/genre/]"),
            Triple("tags", "/tags/", ".tag-groups-alphabetical-index a"),
            Triple("categories", "/cats/", ".tag-groups-alphabetical-index a"),
            Triple("pairings", "/pairing/", ".tag-groups-alphabetical-index a"),
            Triple("groups", "/group/", ".tag-groups-alphabetical-index a"),
        )
    }

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z')
        return List(length) { charPool.random() }.joinToString("")
    }
}
