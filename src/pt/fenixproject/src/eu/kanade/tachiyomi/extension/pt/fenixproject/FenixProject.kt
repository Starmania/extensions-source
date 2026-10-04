package eu.kanade.tachiyomi.extension.pt.fenixproject

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.addCookie
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Source
abstract class FenixProject : KeiSource() {

    // Without it the site hides every +18 title from listings and search.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addCookie("fenix_adult" to "1")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get(baseUrl).asJsoup()
        val mangas = document.select("main ol > li > a").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                with(element.selectFirst("img")!!) {
                    title = attr("alt")
                    thumbnail_url = absUrl("src")
                }
            }
        }
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/manhwas?pagina=$page").asJsoup()
        return parseWorksGrid(document)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
        if (query.isNotBlank()) {
            url.addPathSegment("pesquisar")
                .addQueryParameter("q", query)
        } else {
            url.addPathSegment("manhwas")
            filters.firstInstanceOrNull<GenreFilter>()?.selected?.let {
                url.addQueryParameter("genero", it)
            }
        }
        url.addQueryParameter("pagina", page.toString())

        val document = client.get(url.build()).asJsoup()
        return parseWorksGrid(document)
    }

    private fun parseWorksGrid(document: Document): MangasPage {
        val mangas = document.select("#works-grid > div").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
                title = element.selectFirst("h3")!!.text()
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
            }
        }
        val hasNextPage = document.selectFirst("#works-pagination a[rel=next]") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "manga") return null
        val slug = url.pathSegments.getOrNull(1)?.ifEmpty { null } ?: return null
        val path = "/manga/$slug"
        return parseDetails(client.get(baseUrl + path).asJsoup()).apply { this.url = path }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(parseDetails(document), fetchChapterList(document))
    }

    private fun parseDetails(document: Document) = SManga.create().apply {
        title = document.selectFirst("#work-banner h1")!!.text()
        thumbnail_url = document.selectFirst("#work-banner img")?.absUrl("src")
        author = document.infoValue("Autor")
        artist = document.infoValue("Artista")
        status = when (document.infoValue("Status")?.lowercase()) {
            "em andamento" -> SManga.ONGOING
            "concluído" -> SManga.COMPLETED
            "hiato" -> SManga.ON_HIATUS
            "cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        genre = document.select("main a[href*=genero=]").joinToString { it.text() }
        description = document.selectFirst("h2:containsOwn(Sinopse) + p")?.wholeText()?.trim()
    }

    private fun Document.infoValue(label: String): String? = selectFirst("dl dt:matchesOwn(^$label$) + dd")?.text()?.takeUnless { it == "—" }

    private suspend fun fetchChapterList(document: Document): List<SChapter> {
        val latest = document.select("div[id^=chapter-card-]").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
                name = element.selectFirst("span[title]")!!.text()
                date_upload = dateFormat.tryParseDate(
                    element.selectFirst("span:matchesOwn(^\\d{2}/\\d{2}/\\d{4}$)")?.text(),
                    zoneId,
                )
            }
        }
        if (document.selectFirst("button[phx-click=paginate_chapters]:not([disabled])") == null) return latest

        // The details page only pages through chapters over the LiveView socket; the reader's
        // chapter selector lists all of them, without dates.
        val dates = latest.associate { it.url to it.date_upload }
        return client.get(baseUrl + latest.first().url).asJsoup()
            .select("#chapter-select-top [data-select-list] a").map { element ->
                SChapter.create().apply {
                    setUrlWithoutDomain(element.absUrl("href"))
                    name = element.text()
                    date_upload = dates[url] ?: 0L
                }
            }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return document.select("#reader-pages img").mapIndexed { index, img ->
            Page(index, imageUrl = img.absUrl("src"))
        }
    }

    override fun getFilterList(data: JsonElement?) = FilterList(GenreFilter())

    companion object {
        private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private val zoneId = ZoneId.of("America/Sao_Paulo")
    }
}
