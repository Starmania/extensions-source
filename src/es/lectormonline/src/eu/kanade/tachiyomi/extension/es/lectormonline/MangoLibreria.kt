package eu.kanade.tachiyomi.extension.es.lectormonline

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import kotlin.time.Instant

@Source
abstract class MangoLibreria : KeiSource() {

    private fun comicsUrl(page: Int) = "$baseUrl/comics".toHttpUrl().newBuilder()
        .addQueryParameter("page", page.toString())

    private suspend fun getComics(url: HttpUrl): MangasPage {
        val results = client.get(url).svelteData<ResultsDto>("results")
        return MangasPage(
            results.comics.map { it.toSManga() },
            results.page < results.totalPages,
        )
    }

    // ============================== Popular ==============================
    override suspend fun getPopularManga(page: Int): MangasPage = getComics(
        comicsUrl(page).addQueryParameter("sort", "views").build(),
    )

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): MangasPage = getComics(comicsUrl(page).build())

    // ============================== Search ===============================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = comicsUrl(page).apply {
            if (query.isNotBlank()) {
                addQueryParameter("q", query.trim())
            } else {
                addQueryParameter("sort", "views")
            }
        }.build()

        return getComics(url)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.getOrNull(0) != "comics") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null

        return client.get("$baseUrl/comics/$slug").svelteData<ComicDetailsDto>("comic").toSManga().apply {
            this.url = "/comics/$slug"
        }
    }

    // ======================= Details & Chapters ==========================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val comic = client.get("$baseUrl${manga.url}").svelteData<ComicDetailsDto>("comic")
        val chapterList = comic.comicScans
            .flatMap { scan ->
                scan.chapters.map { ch ->
                    ch.toSChapter(scan.groupName).apply {
                        date_upload = Instant.tryParse(ch.releaseDate)
                    }
                }
            }
            .sortedByDescending { it.chapter_number }
        return SMangaUpdate(comic.toSManga(), chapterList)
    }

    // =============================== Pages ===============================
    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get("$baseUrl${chapter.url}")
        .svelteData<ChapterPagesDto>("chapter").urlPages
        .mapIndexed { index, url -> Page(index, imageUrl = proxiedImage(url)) }

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
