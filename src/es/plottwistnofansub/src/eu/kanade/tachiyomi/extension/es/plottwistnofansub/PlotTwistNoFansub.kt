package eu.kanade.tachiyomi.extension.es.plottwistnofansub

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerializationException
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class PlotTwistNoFansub : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2, 1.seconds)

    // ============================== Popular ===============================
    override suspend fun getPopularManga(page: Int): MangasPage = getLibrary(page, "trending")

    private suspend fun getLibrary(page: Int, orderBy: String): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("biblioteca3")
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
            addQueryParameter("m_orderby", orderBy)
        }.build()
        return parseMangaList(client.get(url).asJsoup())
    }

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("div.manga-grid-v2 figure").map { element ->
            SManga.create().apply {
                val a = element.selectFirst("a[href]")!!
                setUrlWithoutDomain(a.attr("abs:href").ifEmpty { a.attr("href") })
                title = a.attr("title").takeIf { it.isNotEmpty() }
                    ?: element.selectFirst("figcaption")?.text()
                    ?: throw Exception("Missing title for manga at ${a.attr("href")}")
                thumbnail_url = element.selectFirst("img")?.imgAttr()
            }
        }

        val hasNextPage = document.selectFirst("a.next.page-numbers, a.next, a:contains(Siguiente)") != null
        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): MangasPage = getLibrary(page, "latest3")

    // =============================== Search ===============================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isEmpty()) return getLibrary(page, "views3")

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
            addQueryParameter("s", query)
            addQueryParameter("post_type", "wp-manga")
        }.build()
        return parseMangaList(client.get(url).asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "manga") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        val manga = SManga.create().apply { this.url = "/manga/$slug/" }
        return parseDetails(client.get(getMangaUrl(manga)).asJsoup(), manga)
    }

    // =========================== Manga Details ============================
    // Details and the first batch of chapters come from the same page.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(
            parseDetails(document, manga),
            if (fetchChapters) getChapters(document) else chapters,
        )
    }

    private fun parseDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("h1.mn-detail-title")?.text()
            ?: document.selectFirst(".post-title h1")?.text()
            ?: throw Exception("Manga title not found")

        thumbnail_url = document.selectFirst(".mn-detail-cover-frame img")?.imgAttr()
            ?: document.selectFirst(".summary_image img")?.imgAttr()

        description = document.selectFirst(".mn-detail-synopsis")?.text()
            ?: document.selectFirst(".summary__content")?.text()

        genre = document.select(".mn-detail-genres-desktop a").joinToString { it.text() }
            .ifEmpty { document.select(".genres-content a").joinToString { it.text() } }

        author = document.selectFirst(".mn-detail-pill-label:contains(Autor) + .mn-detail-pill-value")?.text()
            ?: document.selectFirst(".author-content a")?.text()

        val statusPill = document.selectFirst(".mn-detail-pill-value")?.text() ?: ""
        val statusClass = document.selectFirst(".mn-detail-pill-value")?.classNames()
            ?.firstOrNull { it.startsWith("mn-st-") } ?: ""

        status = when {
            statusClass == "mn-st-emit" || statusPill.contains("en emisión", true) || statusPill.contains("en curso", true) -> SManga.ONGOING
            statusClass == "mn-st-comp" || statusPill.contains("finalizado", true) || statusPill.contains("completado", true) -> SManga.COMPLETED
            statusClass == "mn-st-cancel" || statusPill.contains("cancelado", true) -> SManga.CANCELLED
            statusClass == "mn-st-pause" || statusPill.contains("en espera", true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    // ============================== Chapters ==============================
    private suspend fun getChapters(document: Document): List<SChapter> {
        val mangaId = document.selectFirst("#mn-detail-load-more")?.attr("data-manga")
            ?: document.selectFirst("script:containsData(mnWpMangaId)")
                ?.data()
                ?.let { MANGA_ID_REGEX.find(it)?.groupValues?.get(1) }
            ?: document.selectFirst("script:containsData(manga_id)")
                ?.data()
                ?.let { OLD_MANGA_ID_REGEX.find(it)?.groupValues?.get(1) }
            ?: throw Exception("No se pudo encontrar el ID del manga")

        // Track URLs already seen to avoid duplicates between the HTML render
        // and page=1 of the API (both return the same initial batch of chapters).
        val seenUrls = mutableSetOf<String>()
        val chapters = mutableListOf<SChapter>()

        fun parseChapterElement(a: Element) {
            val url = a.attr("abs:href").ifEmpty { a.attr("href") }
            if (url.isEmpty() || !seenUrls.add(url)) return
            val num = a.selectFirst(".mn-detail-chapter-name")?.text() ?: ""
            val extend = a.selectFirst(".mn-detail-chapter-extend")?.text() ?: ""
            val dateText = a.selectFirst(".mn-detail-chapter-date")?.text()
                ?.replace(HTML_TAG_REGEX, "") ?: ""
            chapters.add(
                SChapter.create().apply {
                    setUrlWithoutDomain(url)
                    name = buildString {
                        append("Capítulo $num")
                        if (extend.isNotEmpty()) append(" - $extend")
                    }
                    date_upload = dateFormat.tryParse(dateText)
                },
            )
        }

        // Chapters rendered directly in the page HTML (first batch, most recent).
        document.select("a.mn-detail-chapter-item").forEach { parseChapterElement(it) }

        // Load remaining chapters via AJAX.
        // The API page numbering starts at 1 and mirrors what the HTML already shows —
        // seenUrls deduplication ensures we never add the same chapter twice.
        var page = 1
        var hasNextPage = true

        while (hasNextPage) {
            val form = FormBody.Builder()
                .add("action", "plot_load_chapters")
                .add("manga_id", mangaId)
                .add("page", page.toString())
                .build()

            val apiData = try {
                client.post("$baseUrl/wp-admin/admin-ajax.php", form, ensureSuccess = false)
                    .parseAs<ChapterAjaxResponse>()
            } catch (e: SerializationException) {
                break
            }

            if (apiData.data.html.isEmpty()) {
                // Empty HTML — no more chapters.
                break
            }

            val fragment = Jsoup.parseBodyFragment(apiData.data.html, baseUrl)
            val newChapters = fragment.body().select("a.mn-detail-chapter-item")

            if (newChapters.isEmpty()) {
                // HTML came back but contained no chapter links — we're done.
                break
            }

            newChapters.forEach { parseChapterElement(it) }

            // Trust the server's has_more signal to decide whether to fetch the next page.
            // The html.isEmpty() and newChapters.isEmpty() guards above already handle
            // the case where the server is wrong, so we don't need extra logic here.
            hasNextPage = apiData.data.hasMore
            page++
        }

        return chapters
    }

    // =============================== Pages ================================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return (
            document.select("div.reading-content img").ifEmpty {
                document.select("img.wp-manga-chapter-img")
            }.ifEmpty {
                document.select(".chapter-content img")
            }.ifEmpty {
                document.select("img.attachment-full")
            }.ifEmpty {
                document.select("div.pg-box img, div.page-break img")
            }
            ).mapIndexed { i, img ->
            Page(i, imageUrl = img.imgAttr())
        }
    }

    // ============================= Utilities ==============================
    private fun Element.imgAttr(): String {
        val url = when {
            hasAttr("data-src") -> attr("abs:data-src").ifEmpty { attr("data-src") }
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src").ifEmpty { attr("data-lazy-src") }
            hasAttr("srcset") -> attr("abs:srcset").ifEmpty { attr("srcset") }.substringBefore(" ")
            else -> attr("abs:src").ifEmpty { attr("src") }
        }
        return url.trim()
    }

    private val dateFormat by lazy {
        SimpleDateFormat("MMMM d, yyyy", Locale("es"))
    }

    companion object {
        private val MANGA_ID_REGEX = Regex("""mnWpMangaId\s*=\s*(\d+)""")
        private val OLD_MANGA_ID_REGEX = Regex(""""manga_id"\s*:\s*"(\d+)"""")
        private val HTML_TAG_REGEX = Regex("<[^>]*>")
    }
}
