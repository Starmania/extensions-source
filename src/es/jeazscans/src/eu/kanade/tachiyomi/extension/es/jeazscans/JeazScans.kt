package eu.kanade.tachiyomi.extension.es.jeazscans

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
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
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDate
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

@Source
abstract class JeazScans : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2)

    private val dateFormat by lazy {
        DateTimeFormatter.ofPattern("d MMM, yyyy", Locale.US)
    }

    // The site migrated to custom home sections and PHP routes for search.
    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/").asJsoup()
        val mangas = document.select("section.popular-section a.popular-card").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.selectFirst("strong")!!.text()
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }.distinctBy { it.url } // swiper loop renders the first slides twice
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/").asJsoup()
        val mangas = document.select("#manga-grid .manga-card")
            .map { element ->
                SManga.create().apply {
                    val link = element.selectFirst("a.release-title")!!
                    setUrlWithoutDomain(link.attr("abs:href"))
                    title = link.text()
                    thumbnail_url = element.selectFirst("img")?.attr("abs:src")
                }
            }

        return MangasPage(mangas, false)
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

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.blood-title")!!.text()

        description = buildString {
            val descriptionBlock = document.selectFirst("div.text-gray-200:has(h3:matchesOwn((?i)SINOPSIS))")
                ?: document.selectFirst("div.text-gray-200")
            descriptionBlock?.let {
                append(it.ownText().ifEmpty { it.text().replace(SINOPSIS_REGEX, "") })
            }
        }

        thumbnail_url = document.selectFirst("div.lg\\:col-span-3 div.cultivation-panel img")?.attr("abs:src")

        genre = document.select("a[href*='directorio.php?genero=']").joinToString { it.text() }

        val statusText = document.selectFirst("span.status-badge")?.text().orEmpty().lowercase()
        if (statusText.isNotEmpty()) {
            status = when {
                statusText.contains("complet") -> SManga.COMPLETED
                arrayOf("pausa", "hiato").any { statusText.contains(it) } -> SManga.ON_HIATUS
                arrayOf("cancel", "aband").any { statusText.contains(it) } -> SManga.CANCELLED
                arrayOf("cultivo", "curso", "ongoing", "emision").any { statusText.contains(it) } -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select("#chaptersContainer a.chapter-item").map { element ->
        SChapter.create().apply {
            val chapterUrl = element.attr("abs:href")
            setUrlWithoutDomain(chapterUrl)

            val parsedChapterNumber = element.attr("data-chapter-number")
                .toFloatOrNull()
                ?: CHAPTER_NUMBER_REGEX
                    .find(chapterUrl)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toFloatOrNull()
                ?: -1f
            chapter_number = parsedChapterNumber

            val chapterTitle = element.selectFirst(".chapter-title")?.text().orEmpty()
            name = if (chapterTitle.isNotEmpty()) {
                chapterTitle
            } else {
                "Chapter ${parsedChapterNumber.toString().removeSuffix(".0")}"
            }

            val dateText = element.selectFirst("span:has(i.ph-clock)")?.text()
            date_upload = parseChapterDate(dateText)
        }
    }

    private fun parseChapterDate(date: String?): Long {
        if (date.isNullOrEmpty()) return 0L
        val lowercaseDate = date.lowercase()
        return when {
            lowercaseDate.contains("hace") -> {
                val number = NUMBER_REGEX.find(lowercaseDate)?.value?.toIntOrNull() ?: return 0L
                val cal = Calendar.getInstance()
                when {
                    lowercaseDate.contains("segundo") -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
                    lowercaseDate.contains("minuto") -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
                    lowercaseDate.contains("hora") -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
                    lowercaseDate.contains("día") || lowercaseDate.contains("dia") -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
                    lowercaseDate.contains("semana") -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
                    lowercaseDate.contains("mes") -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
                    lowercaseDate.contains("año") -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
                    else -> 0L
                }
            }
            lowercaseDate.contains("ayer") -> {
                Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
            }
            lowercaseDate.contains("hoy") -> {
                Calendar.getInstance().timeInMillis
            }
            else -> dateFormat.tryParseDate(date)
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url).asJsoup()
        val imageElements = document.select(
            ".page-container img.reader-page-image, .page-container img.protected-img, .reader-body img, .reading-content img",
        )

        val htmlPages = imageElements.mapIndexed { index, element ->
            val imageUrl = when {
                element.hasAttr("data-verify") -> decodeVerifyToUrl(element.attr("data-verify"))
                element.hasAttr("data-sec-src") -> element.attr("abs:data-sec-src")
                element.hasAttr("data-src") -> element.attr("abs:data-src")
                else -> element.attr("abs:src")
            }

            Page(index, imageUrl = imageUrl)
        }

        if (htmlPages.isNotEmpty()) return htmlPages

        return fetchPagesFromApi(document)
    }

    private suspend fun fetchPagesFromApi(document: Document): List<Page> {
        val (slug, cap) = extractSlugAndCap(document) ?: throw Exception("Could not extract slug/cap for API")
        val apiUrl = buildApiUrl(document.location(), slug, cap) ?: throw Exception("Could not build API URL")

        val requestHeaders = headers.newBuilder()
            .set("Referer", document.location())
            .build()

        val payload = client.get(apiUrl, requestHeaders).parseAs<ApiLectorResponse>()
        if (!payload.success) throw Exception("API returned error")

        return payload.paginas.filter { it.dataVerify.isNotBlank() }
            .sortedBy { it.orden }
            .mapNotNull { decodeVerifyToUrl(it.dataVerify) }
            .distinct()
            .mapIndexed { idx, imageUrl -> Page(idx, imageUrl = imageUrl) }
    }

    private fun decodeVerifyToUrl(dataVerify: String): String? {
        val decoded = runCatching {
            String(Base64.decode(dataVerify, Base64.DEFAULT))
        }.getOrNull() ?: return null

        val url = decoded.reversed().trim()
        if (!url.startsWith("http")) return null
        return url
    }

    private fun extractSlugAndCap(document: Document): Pair<String, String>? {
        val locationUrl = document.location().toHttpUrlOrNull()
        val slugFromQuery = locationUrl?.queryParameter("manga")?.trim().orEmpty()
        val capFromQuery = locationUrl?.queryParameter("cap")?.trim().orEmpty()
        if (slugFromQuery.isNotBlank() && capFromQuery.isNotBlank()) {
            return slugFromQuery to capFromQuery
        }

        val fromPath = PATH_SLUG_CAP_REGEX
            .find(document.location())
            ?.groupValues
        if (fromPath != null && fromPath.size >= 3) {
            return fromPath[1] to fromPath[2]
        }

        val scriptContent = document.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
        val slugFromScript = MANGA_SLUG_REGEX
            .find(scriptContent)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val capFromScript = CAP_INICIAL_REGEX
            .find(scriptContent)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

        if (slugFromScript.isNotEmpty() && capFromScript.isNotEmpty()) {
            return slugFromScript to capFromScript
        }

        return null
    }

    private fun buildApiUrl(location: String, slug: String, cap: String): String? {
        val current = location.toHttpUrlOrNull() ?: return null

        return runCatching {
            current.newBuilder()
                .encodedPath("/api_lector.php")
                .setQueryParameter("slug", slug)
                .setQueryParameter("cap", cap)
                .build()
                .toString()
        }.getOrNull()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getLatestUpdates(page)

        val url = "$baseUrl/ajax_search.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .build()
        val items = client.get(url).parseAs<List<SearchResponseItem>>()
        val mangas = items.mapNotNull { it.toSManga(baseUrl) }

        return MangasPage(mangas, false)
    }

    // Pasted links are not resolved; answering null gives an empty result instead of an error.
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = null

    // /api/imagen-capitulo answers 403 unless the request carries an image Accept (KeiSource already sets Referer).
    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers.newBuilder().set("Accept", "image/*").build())

    companion object {
        private val SINOPSIS_REGEX = Regex("^SINOPSIS:?\\s*", RegexOption.IGNORE_CASE)
        private val CHAPTER_NUMBER_REGEX = Regex("capitulo-([0-9.]+)", RegexOption.IGNORE_CASE)
        private val NUMBER_REGEX = Regex("""\d+""")
        private val PATH_SLUG_CAP_REGEX = Regex("/leer/([^/]+)/capitulo-([0-9.]+)", RegexOption.IGNORE_CASE)
        private val MANGA_SLUG_REGEX = Regex("""MANGA_SLUG\s*=\s*["']([^"']+)["']""")
        private val CAP_INICIAL_REGEX = Regex("""CAP_INICIAL\s*=\s*["']([^"']+)["']""")
    }
}
