package eu.kanade.tachiyomi.extension.all.mangatoon

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
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MangaToon : KeiSource() {

    private val langUrl: String get() = when (lang) {
        "zh" -> "$baseUrl/cn"
        "pt-BR" -> "$baseUrl/pt"
        "fr" -> baseUrl
        else -> "$baseUrl/$lang"
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(1, 1.seconds)

    private val locale by lazy { Locale.forLanguageTag(lang) }

    private val lockedError = when (lang) {
        "pt-BR" ->
            "Este capítulo é pago e não pode ser lido. " +
                "Use o app oficial do MangaToon para comprar e ler."

        else ->
            "This chapter is paid and can't be read. " +
                "Use the MangaToon official app to purchase and read it."
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        // Portuguese website doesn't seem to have popular titles.
        val path = if (lang == "pt-BR") "comic" else "hot"
        return parseGenrePage(client.get("$langUrl/genre/$path?type=1&page=${page - 1}").asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseGenrePage(client.get("$langUrl/genre/new?type=1&page=${page - 1}").asJsoup())

    private fun parseGenrePage(document: Document): MangasPage {
        val mangas = document.select("div.genre-content div.items a").map { mangaFromElement(it) }
        val hasNextPage = document.selectFirst("span.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchUrl = "$langUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("word", query)
            .build()
        val document = client.get(searchUrl).asJsoup()
        val mangas = document.select("div.comics-result div.recommend-item:has(a[abs:href^=$baseUrl])").map { element ->
            SManga.create().apply {
                title = element.select("div.recommend-comics-title").text()
                thumbnail_url = element.select("img").imgAttr().toNormalPosterUrl()
                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
            }
        }
        val hasNextPage = document.selectFirst("span.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.select("div.content-title").text()
        thumbnail_url = element.select("img").imgAttr().toNormalPosterUrl()
        setUrlWithoutDomain(element.absUrl("href"))
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val document = client.get(url).asJsoup()
        val title = document.selectFirst("h1.detail-title")?.text() ?: return null
        return parseMangaDetails(document).apply {
            this.title = title
            setUrlWithoutDomain(url.toString())
            initialized = true
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val mangaDeferred = async { if (fetchDetails) parseMangaDetails(client.get(getMangaUrl(manga)).asJsoup()) else manga }
        val chaptersDeferred = async { if (fetchChapters) fetchChapterList(manga) else chapters }
        SMangaUpdate(mangaDeferred.await(), chaptersDeferred.await())
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        author = document.select("div.detail-author-name span").text()
            .substringAfter(": ")
        description = document.select("div.detail-description-short p")
            .joinToString("\n\n") { it.text() }
        genre = document.select("div.detail-tags-info span").text()
            .split("/")
            .map { it.capitalize(locale) }
            .sorted()
            .joinToString { it.trim() }
        status = document.select("div.detail-status").text().toStatus()
        val thumbnail = document.select("div.detail-img img").imgAttr().toNormalPosterUrl()
        if (!thumbnail.contains("cartoon-big-images")) {
            thumbnail_url = thumbnail
        }
    }

    private suspend fun fetchChapterList(manga: SManga): List<SChapter> {
        val document = client.get(getMangaUrl(manga) + "/episodes").asJsoup()
        val chapterList = document.select("a.episode-item-new").map { element ->
            SChapter.create().apply {
                name = element.select("div.episode-title-new:last-child").text()
                chapter_number = element.select("div.episode-number").text()
                    .toFloatOrNull() ?: -1f
                date_upload = DATE_FORMAT.tryParse(element.select("div.episode-date span.open-date").text())
                setUrlWithoutDomain(element.absUrl("href"))
            }
        }

        // Finds the last free chapter to filter the paid ones from the list.
        // The desktop website doesn't indicate which chapters are paid in
        // the title page, and the mobile API is heavily encrypted.
        val firstPaid = PAID_CHECK_BREAKPOINTS.find { breakpoint ->
            breakpoint <= chapterList.size &&
                runCatching { fetchPages(chapterList[breakpoint - 1]) }.getOrDefault(emptyList()).isEmpty()
        }

        return chapterList
            .let { if (firstPaid != null) it.take(firstPaid - 1) else it }
            .reversed()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = fetchPages(chapter).takeIf { it.isNotEmpty() } ?: throw Exception(lockedError)

    private suspend fun fetchPages(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter)).asJsoup()
        .select("div.pictures div img:first-child")
        .mapIndexed { i, element -> Page(i, imageUrl = element.imgAttr()) }

    protected open fun Element.imgAttr(): String = when {
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }

    protected open fun Elements.imgAttr(): String = this.first()!!.imgAttr()

    private fun String.toNormalPosterUrl(): String = replace(POSTER_SUFFIX, "$1")

    private fun String.toStatus(): Int = when (lowercase(locale)) {
        in ONGOING_STATUS -> SManga.ONGOING
        in COMPLETED_STATUS -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    companion object {
        private val ONGOING_STATUS = listOf(
            "连载", "on going", "sedang berlangsung", "tiếp tục cập nhật",
            "en proceso", "atualizando", "เซเรียล", "en cours", "連載中",
        )

        private val COMPLETED_STATUS = listOf(
            "完结",
            "completed",
            "tamat",
            "đã full",
            "terminada",
            "concluído",
            "จบ",
            "fin",
        )

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        private val POSTER_SUFFIX = "(jpg)-poster(.*)\\d+?$".toRegex()

        private val PAID_CHECK_BREAKPOINTS = arrayOf(5, 10, 15, 20)
    }
}
