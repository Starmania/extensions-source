package eu.kanade.tachiyomi.multisrc.spicytheme

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

abstract class SpicyTheme : KeiSource() {

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("rsc", "1")

    // The site has no listing API: /comics embeds the whole catalog (a few dozen series) in
    // one payload and filters/sorts client-side, so everything below does the same.
    private suspend fun catalog(): List<MangaDto> = client.get("$baseUrl/comics").extractNextJs<List<MangaDto>>()
        ?: throw Exception("No se pudo leer el catálogo")

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(
        catalog().sortedByDescending { it.views }.map { it.toSManga() },
        false,
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(
        catalog().sortedByDescending { it.lastUpdate }.map { it.toSManga() },
        false,
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val term = query.trim().lowercase()
        return MangasPage(
            catalog()
                .filter { term in it.name.lowercase() || term in it.alternativeName.orEmpty().lowercase() }
                .map { it.toSManga() },
            false,
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "ver") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return series(slug).toSMangaDetails()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val series = series(manga.url)
        return SMangaUpdate(
            series.toSMangaDetails(),
            series.chapters.orEmpty().map { it.toSChapter(series.slug) },
        )
    }

    private suspend fun series(slug: String): MangaDto = client.get("$baseUrl/ver/$slug")
        .extractNextJs<MangaDto> { it is JsonObject && "lastChapters" in it }
        ?: throw Exception("No se pudo leer la serie")

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pages = client.get("$baseUrl/ver/${chapter.url}").extractNextJs<PagesDto>()
            ?: throw Exception("No se pudo leer el capítulo")
        return pages.pages.rawImages.parseAs<List<String>>().mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/ver/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/ver/${chapter.url}"
}
