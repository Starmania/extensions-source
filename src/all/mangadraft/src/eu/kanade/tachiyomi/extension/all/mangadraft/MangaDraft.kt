package eu.kanade.tachiyomi.extension.all.mangadraft

import eu.kanade.tachiyomi.extension.all.mangadraft.dto.MangaDraftCatalogResponseDto
import eu.kanade.tachiyomi.extension.all.mangadraft.dto.MangaDraftPageDTO
import eu.kanade.tachiyomi.extension.all.mangadraft.dto.MangaDraftProjectDto
import eu.kanade.tachiyomi.extension.all.mangadraft.dto.PagesByCategory
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
import keiyoushi.utils.firstInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class MangaDraft : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage = client.get(catalogUrl(page, "popular")).parseCatalog()

    override suspend fun getLatestUpdates(page: Int): MangasPage = client.get(catalogUrl(page, "news")).parseCatalog()

    private fun catalogUrl(page: Int, order: String): HttpUrl = baseUrl.toHttpUrl().newBuilder().apply {
        addPathSegment("api")
        addPathSegment("catalog")
        addPathSegment("projects")
        addQueryParameter("order", order)
        addQueryParameter("type", "all")
        addQueryParameter("page", page.toString())
        addQueryParameter("number", "20")
    }.build()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        val typeFilter = filterList.firstInstance<TypeFilter>()
        val orderFilter = filterList.firstInstance<OrderFilter>()
        val sectionFilter = filterList.firstInstance<SectionFilter>()
        val genreFilter = filterList.firstInstance<GenreFilter>()
        val formatFilter = filterList.firstInstance<FormatFilter>()
        val languageFilter = filterList.firstInstance<LanguageFilter>()
        val statusFilter = filterList.firstInstance<StatusFilter>()
        val sortFilter = filterList.firstInstance<SortFilter>()

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addPathSegment("catalog")
            addPathSegment("projects")
            addQueryParameter("type", typeFilter.toUriPart())
            addQueryParameter("order", orderFilter.toUriPart())
            addQueryParameter("section", sectionFilter.toUriPart())
            addQueryParameter("genre", genreFilter.toUriPart())
            addQueryParameter("format", formatFilter.toUriPart())
            addQueryParameter("language", languageFilter.toUriPart())
            addQueryParameter("status", statusFilter.toUriPart())
            addQueryParameter("order_all", sortFilter.toUriPart())
            addQueryParameter("page", page.toString())
            addQueryParameter("number", "20")
        }.build()

        return client.get(url).parseCatalog()
    }

    private fun Response.parseCatalog(): MangasPage {
        val mangas = parseAs<MangaDraftCatalogResponseDto>().data
        return MangasPage(
            mangas.map {
                SManga.create().apply {
                    setUrlWithoutDomain(it.url)
                    title = it.name
                    thumbnail_url = it.avatar
                    description = it.description
                    genre = it.genres
                }
            },
            // if there is less than 20 received there won't be a next page
            mangas.count() >= 20,
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.host.endsWith(baseUrl.toHttpUrl().host)) return null

        return fetchMangaUpdate(
            manga = SManga.create().apply { setUrlWithoutDomain(url.toString()) },
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        ).manga
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        TypeFilter(),
        OrderFilter(),
        SectionFilter(),
        GenreFilter(),
        FormatFilter(),
        LanguageFilter(),
        StatusFilter(),
    )

    // Details and chapters live on the same page
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(mangaDetailsParse(document, manga), chapterListParse(document))
    }

    private fun mangaDetailsParse(doc: Document, manga: SManga): SManga {
        val scriptContent = doc.selectFirst("script:containsData(window.project)")?.data()
            ?: throw Exception("Unable to find project script")

        val projectJson = regexWindowProject
            .find(scriptContent)
            ?.groups?.get(1)?.value
            ?: throw IllegalStateException("window.project not found")

        val project = projectJson.parseAs<MangaDraftProjectDto>()

        return manga.apply {
            title = project.name
            description = project.description
            author = doc.select("[title=Auteur]").text()
            artist = doc.select("[title=créateur]").text()
            genre = project.genres.joinToString(", ") { it.name }
            status = parseStatus(project.projectStatusId)
        }
    }

    private fun parseStatus(status: Int?) = when (status) {
        0 -> SManga.ONGOING
        1 -> SManga.COMPLETED
        2 -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private fun chapterListParse(document: Document): List<SChapter> {
        val chapterElements = document.select("div.mt-7 div a:not(:has(img))")

        val isNotOneShot = chapterElements[0].attr("href").contains("c.")
        return if (isNotOneShot) {
            chapterElements.mapIndexed { i, it -> chapterFromElement(it, i) }.reversed()
        } else {
            listOf(chapterFromElement(chapterElements[0], 0))
        }
    }

    private fun chapterFromElement(element: Element, index: Int): SChapter = SChapter.create().apply {
        chapter_number = index.toFloat()
        name = "$chapter_number. ${element.selectFirst(".group-hover\\:text-secondary")?.text() ?: ""}"

        // absolute, kept as-is so chapters already in users' libraries still match
        url = element.absUrl("href")

        val dateText = element.selectFirst("div>span")?.text()
        if (!dateText.isNullOrBlank()) {
            name = name.substringBefore(dateText)
            date_upload = dateFormat.tryParseDate(dateText)
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val firstPageId = if (chapter.url.contains("c.")) {
            // the chapter URL redirects to the reader URL of its first page
            client.get(chapter.url).use { response ->
                response.request.url.toString().substringAfterLast('/').filter { it.isDigit() }
            }
        } else {
            chapter.url.substringAfterLast('/').filter { it.isDigit() }
        }

        val result = client.get("$baseUrl/api/reader/listPages?first_page=$firstPageId&grouped_by_category=true")
            .parseAs<PagesByCategory>()

        return findCategoryByPageId(result, firstPageId.toLong()).map {
            Page(it.number, "${it.url}?size=full", "${it.url}?size=full")
        }
    }

    private fun findCategoryByPageId(pagesByCategory: PagesByCategory, pageId: Long): List<MangaDraftPageDTO> = pagesByCategory.values
        .first { pageList -> pageList.any { it.id == pageId } }
}

private val regexWindowProject = Regex("""window\.project\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)

private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)
