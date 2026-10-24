package eu.kanade.tachiyomi.extension.en.nexcomic

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.extractNextJs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class NexComic : KeiSource() {

    // The site is a vinext (Vite + RSC) app with no JSON API: every page's data is in its React
    // flight payload, served as text/x-component at "<path>.rsc". The home payload carries the
    // whole catalog (24 series), and sorting/searching happen client-side, so they do here too.
    private suspend fun home() = client.get("$baseUrl/.rsc").extractNextJs<HomeDto>()!!

    private suspend fun catalog(path: String) = client.get("$baseUrl$path.rsc").extractNextJs<CatalogPropsDto>()!!.initialCatalog

    // Library entries from the MangaThemesia era are stored as "/manga/<slug>/".
    private val SManga.slug get() = url.trimEnd('/').substringAfterLast('/')

    override fun getMangaUrl(manga: SManga) = "$baseUrl/series/${manga.slug}"

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/series/${chapter.url.replace("/", "/chapter/")}"

    override suspend fun getPopularManga(page: Int): MangasPage {
        val home = home()
        val bySlug = home.initialSeries.associateBy { it.slug }
        val mangas = home.initialPopularity.all.mapNotNull { bySlug[it.slug]?.toSManga(baseUrl) }
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val home = home()
        val lastPublished = home.initialChapters.groupBy { it.seriesSlug }
            .mapValues { (_, chapters) -> chapters.maxOf { it.publishedAt ?: 0L } }
        val mangas = home.initialSeries
            .sortedByDescending { lastPublished[it.slug] ?: 0L }
            .map { it.toSManga(baseUrl) }
        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val needle = query.trim().lowercase()
        val mangas = home().initialSeries
            .filter { series -> (series.alternativeTitles + series.title).any { needle in it.lowercase() } }
            .map { it.toSManga(baseUrl) }
        return MangasPage(mangas, false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "series") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return catalog("/series/$slug").series.firstOrNull { it.slug == slug }?.toDetails(baseUrl)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.slug
        val catalog = catalog("/series/$slug")
        val details = catalog.series.first { it.slug == slug }.toDetails(baseUrl)
        // Coin chapters the viewer has not unlocked come with no pages.
        val chapterList = catalog.chapters
            .filter { it.seriesSlug == slug && !it.locked }
            .map { it.toSChapter() }
        return SMangaUpdate(details, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (slug, chapterSlug) = chapter.url.split('/')
        return catalog("/series/$slug/chapter/$chapterSlug").chapters
            .first { it.id == "$slug:$chapterSlug" }
            .pages.mapIndexed { i, page -> Page(i, imageUrl = baseUrl + page.url) }
    }
}
