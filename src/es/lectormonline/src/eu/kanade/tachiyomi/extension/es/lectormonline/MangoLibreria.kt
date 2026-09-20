package eu.kanade.tachiyomi.extension.es.lectormonline

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class MangoLibreria : HttpSource() {

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    // ============================== Popular ==============================
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/comics?sort=views&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val results = response.svelteData<ResultsDto>("results")
        return MangasPage(
            results.comics.map { it.toSManga() },
            results.page < results.totalPages,
        )
    }

    // ============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/comics?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Search ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/comics".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) {
                addQueryParameter("q", query.trim())
            } else {
                addQueryParameter("sort", "views")
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Details ==============================
    override fun mangaDetailsParse(response: Response): SManga = response.svelteData<ComicDetailsDto>("comic").toSManga()

    // ============================= Chapters ==============================
    override fun chapterListParse(response: Response): List<SChapter> {
        val comic = response.svelteData<ComicDetailsDto>("comic")
        return comic.comicScans
            .flatMap { scan ->
                scan.chapters.map { ch ->
                    ch.toSChapter(scan.groupName).apply {
                        date_upload = dateFormat.tryParse(ch.releaseDate)
                    }
                }
            }
            .sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ===============================
    override fun pageListParse(response: Response): List<Page> = response.svelteData<ChapterPagesDto>("chapter").urlPages.mapIndexed { index, url ->
        Page(index, imageUrl = proxiedImage(url))
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // The site is SvelteKit: page data is inlined as a JS object literal (unquoted keys)
    // in the `kit.start(app, element, { data: [...] })` bootstrap script.
    private inline fun <reified T> Response.svelteData(key: String): T = use {
        val nodes = it.body.string().svelteDataToJson().parseAs<List<SvelteNode>>()
        nodes.firstNotNullOf { node -> node.data?.get(key) }.parseAs<T>()
    }

    private fun String.svelteDataToJson(): String {
        var i = indexOf('[', indexOf("data:", indexOf("kit.start(")))
        val out = StringBuilder()
        var depth = 0
        do {
            val c = this[i++]
            out.append(c)
            when (c) {
                '"' -> while (true) {
                    val s = this[i++]
                    out.append(s)
                    if (s == '\\') {
                        out.append(this[i++])
                    } else if (s == '"') {
                        break
                    }
                }
                '[', '{' -> depth++
                ']', '}' -> depth--
            }
            if (c == '{' || c == ',') {
                var end = i
                while (this[end].isLetterOrDigit() || this[end] == '_' || this[end] == '$') end++
                if (end > i && this[end] == ':') {
                    out.append('"').append(this, i, end).append('"')
                    i = end
                }
            }
        } while (depth > 0)
        return out.toString()
    }
}
