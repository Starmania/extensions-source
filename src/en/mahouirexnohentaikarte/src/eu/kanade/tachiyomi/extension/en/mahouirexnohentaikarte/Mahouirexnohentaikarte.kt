package eu.kanade.tachiyomi.extension.en.mahouirexnohentaikarte

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
import okhttp3.HttpUrl
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.ZoneOffset

// The site hosts a single series, and its home page is the series page.
@Source
abstract class Mahouirexnohentaikarte : KeiSource() {
    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(listOf(parseManga(client.get("$baseUrl/").asJsoup())), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val manga = parseManga(client.get("$baseUrl/").asJsoup())
        val matches = manga.title.contains(query, ignoreCase = true) || ALT_TITLES.any { it.contains(query, ignoreCase = true) }
        return MangasPage(if (matches) listOf(manga) else emptyList(), false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.removePrefix("https://") || !url.encodedPath.startsWith(MANGA_URL)) return null
        return parseManga(client.get("$baseUrl/").asJsoup())
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl/").asJsoup()
        val chapterList = document.select("#chapters ol > li > a").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                name = element.selectFirst("span span")!!.text()
                chapter_number = name.substringAfter("Chapter ").toFloatOrNull() ?: -1f
                date_upload = element.selectFirst("time")?.attr("datetime")?.let {
                    runCatching { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
                } ?: 0L
            }
        }
        return SMangaUpdate(parseManga(document), chapterList)
    }

    private fun parseManga(document: Document) = SManga.create().apply {
        url = MANGA_URL
        title = document.selectFirst("h1")!!.text()
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        author = document.selectFirst("dt:contains(Author) + dd")?.text()
        description = document.selectFirst(".synopsis")?.text()
        genre = document.select("h1 ~ ul > li").joinToString { it.text() }
        status = when (document.selectFirst("dt:contains(Status) + dd")?.text()?.lowercase()) {
            "on going", "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(baseUrl + chapter.url).asJsoup()
        .select("main img[src*=/storage/manga/chapter-]")
        .mapIndexed { index, img -> Page(index, imageUrl = img.absUrl("src")) }

    companion object {
        private const val MANGA_URL = "/manga/mahoui-rex-no-hentai-karte"
        private val ALT_TITLES = listOf("Magic Doctor Rex's Perverted Medical Record", "Magic Doctor's Perverted Medical Record")
    }
}
