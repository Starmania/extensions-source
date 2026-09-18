package eu.kanade.tachiyomi.extension.en.mangadass

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParseDate
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

@Source
abstract class MangaDass : KeiSource() {
    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(3)
    }

    override suspend fun getPopularManga(page: Int) = browse(page, "trending")

    override suspend fun getLatestUpdates(page: Int) = browse(page, "latest")

    // The site paginates as /manga/2; /manga/page/2/ is a 404
    private suspend fun browse(page: Int, order: String): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("manga")
            .addPathSegment(page.toString())
            .addQueryParameter("m_orderby", order)
            .build()
        return parseListing(client.get(url).asJsoup())
    }

    // The WordPress-style /?s=&post_type=wp-manga query is ignored and returns the default listing
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()
        return parseListing(client.get(url).asJsoup())
    }

    private fun parseListing(document: Document): MangasPage {
        val entries = document.select("div.page-item-detail").map { element ->
            SManga.create().apply {
                title = element.selectFirst("h3")!!.text()
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
            }
        }
        return MangasPage(entries, document.selectFirst("li.next:not(.disabled)") != null)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSize < 2 || url.pathSegments[0] != "manga") return null
        val path = "/manga/${url.pathSegments[1]}"
        return parseDetails(client.get(baseUrl + path).asJsoup()).apply { this.url = path }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(parseDetails(document), parseChapters(document))
    }

    private fun parseDetails(document: Document) = SManga.create().apply {
        title = document.selectFirst("div.post-title h1")!!.text()
        author = document.select("div.author-content > a").eachText().joinToString().takeIf(String::isNotBlank)
        artist = document.select("div.artist-content > a").eachText().joinToString().takeIf(String::isNotBlank)
        description = document.selectFirst(".post-content_item:contains(Alt) .summary-content")
            ?.ownText()?.takeIf(String::isNotBlank)?.let { "Alternative Names: $it" }
        thumbnail_url = document.selectFirst("div.summary_image img")?.absUrl("src")
        status = when (document.select("div.summary-content").last()?.text()) {
            "Ongoing" -> SManga.ONGOING
            "Completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        val type = document.selectFirst(".post-content_item:contains(Type) .summary-content")?.ownText()
        genre = (document.select("div.genres-content a").eachText() + listOfNotNull(type?.takeIf { it.isNotEmpty() && it != "-" }))
            .distinctBy(String::lowercase)
            .joinToString()
    }

    private fun parseChapters(document: Document) = document.select(".row-content-chapter li").map { element ->
        SChapter.create().apply {
            val link = element.selectFirst("a")!!
            name = link.text()
            setUrlWithoutDomain(link.absUrl("href"))
            date_upload = dateFormat.tryParseDate(element.selectFirst(".chapter-time")?.text())
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter)).asJsoup()
        .select(".read-content img")
        .mapIndexed { index, element -> Page(index, imageUrl = element.absUrl("src")) }
}
