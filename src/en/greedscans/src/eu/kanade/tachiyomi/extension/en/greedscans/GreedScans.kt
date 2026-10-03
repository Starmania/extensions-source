package eu.kanade.tachiyomi.extension.en.greedscans

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
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class GreedScans : KeiSource() {

    private val apiUrl = "https://api.gojoscans.com/api"

    // ==================== POPULAR ====================

    override suspend fun getPopularManga(page: Int) = getSearchMangaList(page, "", SortFilter.popular)

    // ==================== LATEST ====================

    override suspend fun getLatestUpdates(page: Int) = getSearchMangaList(page, "", SortFilter.latest)

    // ==================== SEARCH ====================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", "24")
            addQueryParameter("sort_order", "desc")
            if (query.isNotEmpty()) addQueryParameter("search", query)
            filters.filterIsInstance<UrlFilter>().forEach { it.addToUrl(this) }
        }.build()

        val data = client.get(url).parseAs<SeriesListResponse>().data

        val mangas = data.data.map { series ->
            SManga.create().apply {
                this.url = "/series/${series.slug}"
                title = series.title
                thumbnail_url = series.coverImage
                status = series.status.toStatus()
            }
        }

        return MangasPage(mangas, data.hasNextPage())
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        MinChaptersFilter(),
        GenreFilter(),
    )

    // ==================== MANGA DETAILS & CHAPTER LIST ====================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val compatibleUrl = manga.url.replace("/manga/", "/series/")
        val data = client.get("$apiUrl$compatibleUrl").parseAs<SeriesDetailResponse>().data

        manga.apply {
            url = "/series/${data.slug}"
            title = data.title
            author = data.author
            artist = data.studio
            thumbnail_url = data.coverImage
            status = data.status.toStatus()
            genre = data.genres.joinToString(", ")
            description = buildString {
                data.synopsis?.let { append(it) }
                if (data.alternativeTitles.isNotEmpty()) {
                    append("\n\nAlternative Titles:\n")
                    append(data.alternativeTitles.joinToString("\n"))
                }
            }
        }

        val chapterList = data.chapters.sortedByDescending { it.chapterNumber }.map { chapter ->
            SChapter.create().apply {
                url = "/series/${data.slug}/chapters/${chapter.slug}"
                name = chapter.title
                date_upload = (chapter.publishedAt ?: chapter.createdAt)?.let {
                    DATE_FORMAT.tryParse(it)
                } ?: 0L
            }
        }

        return SMangaUpdate(manga, chapterList)
    }

    companion object {
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ENGLISH)
    }

    // ==================== PAGE LIST ====================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val images = client.get("$apiUrl${chapter.url}").parseAs<ChapterDetailResponse>().data.chapter.images

        return images.mapIndexed { i, img -> Page(i, imageUrl = img.imageUrl) }
    }

    // ==================== HELPERS ====================

    private fun String?.toStatus() = when (this?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }
}
