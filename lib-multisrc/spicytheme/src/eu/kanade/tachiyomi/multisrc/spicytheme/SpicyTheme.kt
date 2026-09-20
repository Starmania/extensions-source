package eu.kanade.tachiyomi.multisrc.spicytheme

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

abstract class SpicyTheme : HttpSource() {

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("rsc", "1")

    // The site has no listing API: /comics embeds the whole catalog (a few dozen series) in
    // one payload and filters/sorts client-side, so everything below does the same.
    private fun catalogRequest(): Request = GET("$baseUrl/comics", headers)

    private fun catalog(response: Response): List<MangaDto> = response.extractNextJs<List<MangaDto>>()
        ?: throw Exception("No se pudo leer el catálogo")

    override fun popularMangaRequest(page: Int): Request = catalogRequest()

    override fun popularMangaParse(response: Response): MangasPage = MangasPage(
        catalog(response).sortedByDescending { it.views }.map { it.toSManga() },
        false,
    )

    override fun latestUpdatesRequest(page: Int): Request = catalogRequest()

    override fun latestUpdatesParse(response: Response): MangasPage = MangasPage(
        catalog(response).sortedByDescending { it.lastUpdate }.map { it.toSManga() },
        false,
    )

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = catalogRequest().newBuilder()
        .tag(String::class.java, query.trim().lowercase())
        .build()

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.tag(String::class.java).orEmpty()
        return MangasPage(
            catalog(response)
                .filter { query in it.name.lowercase() || query in it.alternativeName.orEmpty().lowercase() }
                .map { it.toSManga() },
            false,
        )
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/ver/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga = seriesFrom(response).toSMangaDetails()

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val series = seriesFrom(response)
        return series.chapters.orEmpty().map { it.toSChapter(series.slug) }
    }

    private fun seriesFrom(response: Response): MangaDto = response.extractNextJs<MangaDto> { it is JsonObject && "lastChapters" in it }
        ?: throw Exception("No se pudo leer la serie")

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/ver/${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val chapter = response.extractNextJs<PagesDto>()
            ?: throw Exception("No se pudo leer el capítulo")
        return chapter.pages.rawImages.parseAs<List<String>>().mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/ver/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/ver/${chapter.url}"

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
