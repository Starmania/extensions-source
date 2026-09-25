package eu.kanade.tachiyomi.extension.tr.golgebahcesi

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class GolgeBahcesi : KeiSource() {

    private val apiBaseUrl = "https://api.golgebahcesi.com/api"

    override suspend fun getPopularManga(page: Int): MangasPage = getSeriesList("$apiBaseUrl/series?page=$page&limit=24&sort=popular".toHttpUrl())

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSeriesList("$apiBaseUrl/series?page=$page&limit=24&sort=updatedAt".toHttpUrl())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "default"
        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart() ?: ""
        val type = filters.firstInstanceOrNull<TypeFilter>()?.toUriPart() ?: ""
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart() ?: ""
        val minChapters = filters.firstInstanceOrNull<MinChaptersFilter>()?.state?.toString()?.takeIf { it.isNotBlank() } ?: ""

        val url = "$apiBaseUrl/series?page=$page&limit=24&sort=$sort".toHttpUrl().newBuilder()
        if (query.isNotBlank()) url.addQueryParameter("search", query)
        if (status.isNotBlank()) url.addQueryParameter("status", status)
        if (type.isNotBlank()) url.addQueryParameter("type", type)
        if (genre.isNotBlank()) url.addQueryParameter("genre", genre)
        if (minChapters.isNotBlank()) url.addQueryParameter("minChapters", minChapters)

        return getSeriesList(url.build())
    }

    private suspend fun getSeriesList(url: HttpUrl): MangasPage {
        val result = client.get(url).parseAs<SeriesListResponse>()
        val mangas = result.data.map { it.toSManga() }
        val hasNextPage = result.pagination?.let { it.currentPage < it.totalPages } ?: false
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "manga") return null
        return getMangaDetails(segments[1])
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = async { if (fetchDetails) getMangaDetails(manga.url) else manga }
        val chaptersDeferred = async { if (fetchChapters) getChapterList(manga.url) else chapters }
        SMangaUpdate(detailsDeferred.await(), chaptersDeferred.await())
    }

    private suspend fun getMangaDetails(slug: String): SManga = client.get("$apiBaseUrl/series/$slug").parseAs<SeriesDto>().toSManga()

    private suspend fun getChapterList(slug: String): List<SChapter> = client.get("$apiBaseUrl/series/$slug/chapters").parseAs<List<ChapterDto>>().map { chapter ->
        SChapter.create().apply {
            url = "/${chapter.id}/${chapter.seriesSlug}/${chapter.slug}"
            name = chapter.title
            chapter_number = chapter.number
            date_upload = dateFormat.tryParse(chapter.releaseDate ?: chapter.createdAt)
        }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val segments = "$baseUrl${chapter.url}".toHttpUrl().pathSegments
        return "$baseUrl/manga/${segments[1]}/bolum/${segments[2]}"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = "$baseUrl${chapter.url}".toHttpUrl().pathSegments[0]
        val result = client.get("$apiBaseUrl/chapters/$chapterId").parseAs<ChapterDto>()
        // "secure" chapters serve encrypted images whose keys come from a manifest gated by
        // Turnstile, a browser fingerprint and an obfuscated WASM integrity check
        if (result.deliverySystem == "secure") {
            throw Exception("This chapter uses encrypted images, open it in WebView")
        }
        return result.pages?.map { page ->
            Page(page.index, imageUrl = page.url)
        } ?: emptyList()
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
        MinChaptersFilter(),
    )

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
