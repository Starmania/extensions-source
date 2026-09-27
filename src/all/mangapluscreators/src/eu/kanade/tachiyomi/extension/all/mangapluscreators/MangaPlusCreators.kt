package eu.kanade.tachiyomi.extension.all.mangapluscreators

import eu.kanade.tachiyomi.network.GET
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
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class MangaPlusCreators : KeiSource() {

    private val apiUrl get() = "$baseUrl/api"

    private val chapterDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)

    // POPULAR Section
    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/titles/popular/?p=m&l=$lang").asJsoup()
        return parseMangasPage(document, "div.item-recent")
    }

    private fun parseMangasPage(document: Document, selector: String): MangasPage {
        val mangas = document.select(selector).map { element ->
            popularElementToSManga(element)
        }

        return MangasPage(mangas, false)
    }

    private fun popularElementToSManga(element: Element): SManga {
        val titleThumbnailUrl = element.selectFirst(".image-area img")!!.attr("src")
        val titleContentId = titleThumbnailUrl.toHttpUrl().pathSegments[2]
        return SManga.create().apply {
            title = element.selectFirst(".title-area .title")!!.text()
            thumbnail_url = titleThumbnailUrl
            url = "/titles/$titleContentId"
        }
    }

    // LATEST Section
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$apiUrl/titles/recent/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("l", lang)
            .addQueryParameter("t", "episode")
            .build()

        val result = client.get(url).parseAs<MpcResponse>()

        val titles = result.titles.orEmpty().map { title -> title.toSManga() }

        return MangasPage(titles, result.status != "error")
    }

    private fun MpcTitle.toSManga(): SManga {
        val mTitle = this.title
        val mAuthor = this.author.name
        return SManga.create().apply {
            title = mTitle
            thumbnail_url = thumbnail
            url = "/titles/${latestEpisode.titleConnectId}"
            author = mAuthor
        }
    }

    // SEARCH Section
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = "$baseUrl/keywords".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("s", "date")
                .addQueryParameter("lang", lang)
                .build()
            return parseMangasPage(client.get(url).asJsoup(), "div.item-search")
        }

        // nothing to search, filters active -> browsing /genres instead
        val genreUrl = baseUrl.toHttpUrl().newBuilder()
            .apply {
                addPathSegment("genres")
                addQueryParameter("l", lang)
                filters.forEach { filter ->
                    when (filter) {
                        is SortFilter -> {
                            if (filter.selected.isNotEmpty()) {
                                addQueryParameter("s", filter.selected)
                            }
                        }

                        is GenreFilter -> addPathSegment(filter.selected)

                        else -> { /* Nothing else is supported for now */ }
                    }
                }
            }.build()

        return parseMangasPage(client.get(genreUrl).asJsoup(), "div.item-recent")
    }

    override suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage {
        // medibang.com is the site's former domain, its links carry an extra leading path segment
        val pathIndex = when (url.host) {
            baseUrl.toHttpUrl().host -> 0
            "medibang.com" -> 1
            else -> return MangasPage(emptyList(), false)
        }
        val id = url.pathSegments.getOrNull(pathIndex + 1)?.takeIf { it.isNotEmpty() }
            ?: return MangasPage(emptyList(), false)

        val mangas = when (url.pathSegments[pathIndex]) {
            "titles" -> {
                val bookBox = client.get("$baseUrl/titles/$id").asJsoup().selectFirst(".book-box")!!
                listOf(
                    SManga.create().apply {
                        title = bookBox.selectFirst("div.title")!!.text()
                        thumbnail_url = bookBox.selectFirst("div.cover img")!!.attr("data-src")
                        this.url = "/titles/$id"
                    },
                )
            }

            "episodes" -> {
                val readerElement = client.get("$baseUrl/episodes/$id").asJsoup()
                    .selectFirst("div[react=viewer]")!!
                listOf(readerElement.attr("data-title").parseAs<MpcReaderDataTitle>().toSManga())
            }

            "authors" -> {
                client.get("$baseUrl/authors/$id").asJsoup()
                    .select("#works .manga-list li .md\\:block")
                    .map { element ->
                        val titleThumbnailUrl = element.selectFirst(".image-area img")!!.attr("src")
                        val titleContentId = titleThumbnailUrl.toHttpUrl().pathSegments[2]
                        SManga.create().apply {
                            title = element.selectFirst("p.text-white")!!.text()
                            thumbnail_url = titleThumbnailUrl
                            this.url = "/titles/$titleContentId"
                        }
                    }
            }

            else -> emptyList()
        }

        return MangasPage(mangas, false)
    }

    private fun MpcReaderDataTitle.toSManga(): SManga {
        val mTitle = title
        return SManga.create().apply {
            title = mTitle
            thumbnail_url = thumbnail
            url = "/titles/$contentsId"
        }
    }

    // MANGA Section
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // details and the first chapter page are the same document
        val titleContentId = (baseUrl + manga.url).toHttpUrl().pathSegments[1]
        val firstPage = client.get(chapterListPageUrl(1, titleContentId)).asJsoup()

        val firstChapters = chapterListPageParse(firstPage)
        val chapterList = firstChapters.chapters.toMutableList()
        var hasNextPage = firstChapters.hasNextPage
        var page = 1
        while (hasNextPage) {
            page += 1
            val nextPageResult = chapterListPageParse(client.get(chapterListPageUrl(page, titleContentId)).asJsoup())
            if (nextPageResult.chapters.isEmpty()) {
                break
            }
            chapterList.addAll(nextPageResult.chapters)
            hasNextPage = nextPageResult.hasNextPage
        }

        return SMangaUpdate(mangaDetailsParse(firstPage), chapterList.asReversed())
    }

    private fun mangaDetailsParse(document: Document): SManga {
        val bookBox = document.selectFirst(".book-box")!!

        return SManga.create().apply {
            title = bookBox.selectFirst("div.title")!!.text()
            author = bookBox.selectFirst("div.mod-btn-profile div.name")!!.text()
            description = bookBox.select("div.summary p")
                .joinToString("\n\n") { it.text() }
            status = when (bookBox.selectFirst("div.book-submit-type")!!.text()) {
                "Series" -> SManga.ONGOING
                "One-shot" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            genre = bookBox.select("div.genre-area div.tag-genre")
                .joinToString(", ") { it.text() }
            thumbnail_url = bookBox.selectFirst("div.cover img")!!.attr("data-src")
        }
    }

    // CHAPTER Section
    private fun chapterListPageUrl(page: Int, titleContentId: String) = "$baseUrl/titles/$titleContentId/?page=$page"

    private fun chapterListPageParse(document: Document): ChaptersPage {
        val chapters = document.select(".mod-item-series").map { element ->
            chapterElementToSChapter(element)
        }
        val hasResult = document.select(".mod-pagination .next").isNotEmpty()
        return ChaptersPage(
            chapters,
            hasResult,
        )
    }

    private fun chapterElementToSChapter(element: Element): SChapter {
        val episode = element.attr("href").substringAfterLast("/")
        val latestUpdatedDate = element.selectFirst(".first-update")!!.text()
        val chapterNumberElement = element.selectFirst(".number")!!.text()
        val chapterNumber = chapterNumberElement.substringAfter("#").toFloatOrNull()
        return SChapter.create().apply {
            url = "/episodes/$episode"
            date_upload = chapterDateFormat.tryParseDate(latestUpdatedDate)
            name = chapterNumberElement
            chapter_number = if (chapterNumberElement == "One-shot") {
                0F
            } else {
                chapterNumber ?: -1F
            }
        }
    }

    // PAGES & IMAGES Section
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val readerElement = client.get(chapterUrl).asJsoup().selectFirst("div[react=viewer]")!!
        val dataPages = readerElement.attr("data-pages")
        return dataPages.parseAs<MpcReaderDataPages>().pc.map { page ->
            Page(page.pageNo, chapterUrl, page.imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .removeAll("Origin")
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    // FILTERS Section
    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Separator(),
        Filter.Header("NOTE: Ignored if using text search!"),
        Filter.Separator(),
        SortFilter(),
        GenreFilter(),
        Filter.Separator(),
    )

    private class SortFilter :
        SelectFilter(
            "Sort",
            listOf(
                SelectFilterOption("Popularity", ""),
                SelectFilterOption("Date", "latest_desc"),
                SelectFilterOption("Likes", "like_desc"),
            ),
            0,
        )

    private class GenreFilter :
        SelectFilter(
            "Genres",
            listOf(
                SelectFilterOption("Fantasy", "fantasy"),
                SelectFilterOption("Action", "action"),
                SelectFilterOption("Romance", "romance"),
                SelectFilterOption("Horror", "horror"),
                SelectFilterOption("Slice of Life", "slice_of_life"),
                SelectFilterOption("Comedy", "comedy"),
                SelectFilterOption("Sports", "sports"),
                SelectFilterOption("Sci-Fi", "sf"),
                SelectFilterOption("Mystery", "mystery"),
                SelectFilterOption("Others", "others"),
            ),
            0,
        )

    private abstract class SelectFilter(
        name: String,
        private val options: List<SelectFilterOption>,
        default: Int = 0,
    ) : Filter.Select<String>(
        name,
        options.map { it.name }.toTypedArray(),
        default,
    ) {
        val selected: String
            get() = options[state].value
    }

    private class SelectFilterOption(val name: String, val value: String)
}
