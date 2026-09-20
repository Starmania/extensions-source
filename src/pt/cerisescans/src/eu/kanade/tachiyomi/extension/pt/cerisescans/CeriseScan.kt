package eu.kanade.tachiyomi.extension.pt.cerisescans

import eu.kanade.tachiyomi.source.model.Filter
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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

@Source
abstract class CeriseScan : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3, 2.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage = getList(page, sort = "views")

    // Without `sort` the API already orders by newest chapter; `sort=updated_at` is
    // bumped by every view-count write, so it does not reflect new releases.
    override suspend fun getLatestUpdates(page: Int): MangasPage = getList(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = getList(page, query, filters = filters)

    private suspend fun getList(page: Int, query: String = "", sort: String? = null, filters: FilterList = FilterList()): MangasPage {
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
        val result = client.get(url).parseAs<ComicListDto>()
        return MangasPage(result.comics().map { it.toSManga(baseUrl) }, page < result.totalPages)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(StatusFilter(), GenreFilter())

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "comic") {
            return null
        }
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return client.get("$baseUrl/api/comics/$slug").parseAs<ComicDto>().toSManga(baseUrl)
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/comic/${manga.url}"

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/read/${chapter.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val comic = client.get("$baseUrl/api/comics/${manga.url}").parseAs<ComicDto>()
        return SMangaUpdate(comic.toSManga(baseUrl), comic.lastChapters.map { it.toSChapter() })
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get("$baseUrl/api/chapters/${chapter.url}").parseAs<ChapterDto>().images.mapIndexed { index, path ->
        Page(index, imageUrl = baseUrl + path)
    }

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
