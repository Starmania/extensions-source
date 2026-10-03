package eu.kanade.tachiyomi.extension.en.readallcomicscom

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Source
abstract class ReadAllComics : KeiSource() {

    override val supportsLatest = false

    // Popular

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) baseUrl else "$baseUrl/?paged=$page"
        val document = client.get(url).asJsoup()
        val mangas = document.select("ul.list-story.categories li").map { mangaFromElement(it) }
        val hasNextPage = document.selectFirst(".pagination .page-numbers.current + .page-numbers") != null
        return MangasPage(mangas, hasNextPage)
    }

    // Latest (unsupported)

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    // Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("story", query)
            addQueryParameter("s", "")
            addQueryParameter("type", "comic")
            if (page > 1) addQueryParameter("paged", page.toString())
        }.build()

        val document = client.get(url).asJsoup()
        val mangas = document.select("ul.list-story.categories li").map { mangaFromElement(it) }
        val hasNextPage = document.selectFirst("a.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element) = SManga.create().apply {
        val titleAnchor = element.selectFirst("a.cat-title")!!
        setUrlWithoutDomain(titleAnchor.attr("abs:href"))
        title = titleAnchor.text()
        thumbnail_url = element.selectFirst("img.book-cover")?.attr("abs:src")
    }

    // Manga details and chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        return SMangaUpdate(mangaDetailsParse(manga, document), chapterListParse(document))
    }

    private fun mangaDetailsParse(manga: SManga, document: Document) = manga.apply {
        val archive = document.selectFirst(".description-archive")!!
        title = archive.selectFirst("h1")!!.text()
        thumbnail_url = archive.selectFirst("p img")?.attr("abs:src")
        val infoStrongs = archive.select(".b > p strong")
        genre = infoStrongs.firstOrNull()?.text()
        author = infoStrongs.lastOrNull()?.text()
        description = archive.selectFirst("#hidden-description")?.wholeText()?.trim()
    }

    private fun chapterListParse(document: Document): List<SChapter> = document.select(".list-story a").map { element ->
        SChapter.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            name = element.text()
            val year = name.substringAfterLast('(').substringBefore(')')
            date_upload = dateFormat.tryParseDate("$year-1-1", ZoneOffset.UTC)
        }
    }

    // Pages

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(baseUrl + chapter.url).asJsoup()
        .select("body img:not(div[id=logo] img)").mapIndexed { idx, element ->
            Page(idx, imageUrl = element.attr("abs:src"))
        }

    companion object {
        private val dateFormat = DateTimeFormatter.ofPattern("yyyy-M-d")
    }
}
