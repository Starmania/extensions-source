package eu.kanade.tachiyomi.extension.all.hennojin

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.head
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.select.Evaluator
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Hennojin : KeiSource() {

    // Popular is latest
    override val supportsLatest = false

    private val httpUrl by lazy { "$baseUrl/home".toHttpUrl() }

    override suspend fun getLatestUpdates(page: Int) = getPopularManga(page)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = httpUrl.newBuilder().apply {
            when (lang) {
                "ja" -> {
                    addEncodedPathSegments("page/$page/")
                    addQueryParameter("archive", "raw")
                }
                else -> addEncodedPathSegments("page/$page")
            }
        }.build()
        return parseMangasPage(client.get(url))
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = httpUrl.newBuilder()
            .addEncodedPathSegments("page/$page")
            .addQueryParameter("keyword", query)
            .addQueryParameter("_wpnonce", WP_NONCE)
            .build()
        return parseMangasPage(client.get(url))
    }

    private fun parseMangasPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".grid-items .layer-content").map { element ->
            SManga.create().apply {
                element.selectFirst(".title_link > a")?.let {
                    title = it.text()
                    setUrlWithoutDomain(it.absUrl("href"))
                }
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
            }
        }
        val hasNextPage = document.selectFirst(".paginate .next") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != httpUrl.host || url.pathSegments.getOrNull(1) != "manga") return null

        val document = client.get(url).asJsoup()
        return parseMangaDetails(document).apply {
            setUrlWithoutDomain(url.toString())
            title = document.selectFirst(".manga-title")!!.textNodes().first().text().trim()
            thumbnail_url = document.selectFirst(".manga-thumbnail > img")?.absUrl("src")
            initialized = true
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        return SMangaUpdate(parseMangaDetails(document), parseChapterList(document))
    }

    private fun parseMangaDetails(document: Document) = SManga.create().apply {
        description = document.select(".manga-subtitle + p + p")
            .joinToString("\n") {
                it
                    .apply { select(Evaluator.Tag("br")).prepend("\\n") }
                    .text()
                    .replace("\\n", "\n")
                    .replace("\n ", "\n")
            }
        genre = document.select(
            ".tags-list a[href*=/parody/]," +
                ".tags-list a[href*=/tags/]," +
                ".tags-list a[href*=/character/]",
        ).joinToString { it.text() }
        artist = document.selectFirst(".tags-list a[href*=/artist/]")?.text()
        author = document.selectFirst(".tags-list a[href*=/group/]")?.text() ?: artist
        status = SManga.COMPLETED
    }

    private suspend fun parseChapterList(document: Document): List<SChapter> {
        val date = document
            .selectFirst(".manga-thumbnail > img")
            ?.absUrl("src")
            ?.let { url -> client.head(url, ensureSuccess = false).use { it.date } }

        return document.select("a:contains(Read Online)").map {
            SChapter.create().apply {
                setUrlWithoutDomain(
                    it
                        .absUrl("href")
                        .toHttpUrlOrNull()
                        ?.newBuilder()
                        ?.removeAllQueryParameters("view")
                        ?.addQueryParameter("view", "multi")
                        ?.build()
                        ?.toString()
                        ?: it.absUrl("href"),
                )
                name = "Chapter"
                date?.let { date_upload = it }
                chapter_number = -1f
            }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url).asJsoup()
        return document.select(".slideshow-container > img")
            .mapIndexed { idx, img -> Page(idx, imageUrl = img.absUrl("src")) }
    }

    private inline val Response.date: Long
        get() = headers["Last-Modified"]?.let { httpDate.tryParse(it) } ?: 0L

    companion object {
        // Let's hope this doesn't change
        private const val WP_NONCE = "40229f97a5"

        private val httpDate by lazy {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
        }
    }
}
