package eu.kanade.tachiyomi.extension.pt.cerisescans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

@Source
abstract class CeriseScan : HttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(3, 2.seconds)
        .build()

    private fun listRequest(page: Int, query: String = "", sort: String? = null, filters: FilterList = FilterList()): Request {
        val url = "$baseUrl/api/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .apply {
                sort?.let { addQueryParameter("sort", it) }
                if (query.isNotBlank()) addQueryParameter("search", query)
                filters.firstInstanceOrNull<StatusFilter>()?.value?.let { addQueryParameter("status", it) }
                filters.firstInstanceOrNull<GenreFilter>()?.value?.let { addQueryParameter("genre", it) }
            }
            .build()
        return GET(url, headers)
    }

    override fun popularMangaRequest(page: Int) = listRequest(page, sort = "views")

    override fun popularMangaParse(response: Response) = listParse(response)

    // Without `sort` the API already orders by newest chapter; `sort=updated_at` is
    // bumped by every view-count write, so it does not reflect new releases.
    override fun latestUpdatesRequest(page: Int) = listRequest(page)

    override fun latestUpdatesParse(response: Response) = listParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = listRequest(page, query, filters = filters)

    override fun searchMangaParse(response: Response) = listParse(response)

    private fun listParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")!!.toInt()
        val result = response.parseAs<ComicListDto>()
        return MangasPage(result.comics().map { it.toSManga(baseUrl) }, page < result.totalPages)
    }

    override fun getFilterList() = FilterList(StatusFilter(), GenreFilter())

    override fun getMangaUrl(manga: SManga) = "$baseUrl/comic/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga) = GET("$baseUrl/api/comics/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response) = response.parseAs<ComicDto>().toSManga(baseUrl)

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> = response.parseAs<ComicDto>().lastChapters.map { it.toSChapter() }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/read/${chapter.url}"

    override fun pageListRequest(chapter: SChapter) = GET("$baseUrl/api/chapters/${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> = response.parseAs<ChapterDto>().images.mapIndexed { index, path ->
        Page(index, imageUrl = baseUrl + path)
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    private class StatusFilter :
        SelectFilter(
            "Status",
            arrayOf(
                "Todos" to null,
                "Em andamento" to "ongoing",
                "Completo" to "completed",
                "Hiato" to "hiatus",
                "Abandonado" to "dropped",
            ),
        )

    private class GenreFilter :
        SelectFilter(
            "Gênero",
            arrayOf<Pair<String, String?>>("Todos" to null) + GENRES.map { it to it },
        )

    private open class SelectFilter(name: String, private val options: Array<Pair<String, String?>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val value get() = options[state].second
    }

    companion object {
        private const val PAGE_SIZE = 24
        private val GENRES = listOf(
            "Ação", "Adulto", "Artes Marciais", "Aventura", "BL", "Comédia", "Drama", "Fantasia",
            "Histórico", "Horror", "Isekai", "Josei", "Magia", "Mistério", "Psicológico",
            "Reencarnação", "Romance", "Shoujo", "Shounen", "Slice of Life", "Smut",
            "Sobrenatural", "Vida Escolar", "Webtoon", "Yaoi", "Yuri",
        )
    }
}
