package eu.kanade.tachiyomi.extension.pt.blackoutcomics

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class BlackoutComics : KeiSource() {

    private val baseHttpUrl by lazy { baseUrl.toHttpUrl() }

    override fun OkHttpClient.Builder.configureClient() = addInterceptor(::ageGateInterceptor)

    override fun Headers.Builder.configureHeaders() = add("DNT", "1")
        .add("Sec-GPC", "1")
        .add("Upgrade-Insecure-Requests", "1")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "pt-BR,pt;q=0.8,en-US;q=0.5,en;q=0.3")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")

    // ============================== Popular ===============================
    override suspend fun getPopularManga(page: Int): MangasPage {
        val doc = client.get("$baseUrl/ranking").asJsoup()
        return MangasPage(doc.parseCards(".ranking-grid a.webtoon-card"), false)
    }

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val doc = client.get("$baseUrl/atualizados-recente?page=$page").asJsoup()
        val hasNext = doc.select(".pagerx__link[rel=next]").isNotEmpty()
        return MangasPage(doc.parseCards(".webtoon-grid a.webtoon-card"), hasNext)
    }

    // =============================== Search ===============================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/comics".toHttpUrl().newBuilder()
        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart()
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart()

        if (query.isNotBlank()) url.addQueryParameter("src", query)
        if (!status.isNullOrEmpty()) url.addQueryParameter("status", status)
        if (!genre.isNullOrEmpty()) url.addQueryParameter("gen", genre)

        val doc = client.get(url.build()).asJsoup()
        return MangasPage(doc.parseCards(".webtoon-grid a.webtoon-card"), false)
    }

    // The site has no URL search; without this a pasted link would throw instead of finding nothing.
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = null

    // ====================== Manga Details & Chapters ======================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get(getMangaUrl(manga)).asJsoup()

        val details = SManga.create().apply {
            title = doc.select(".project-title").text()
            thumbnail_url = doc.select(".project-cover").attr("abs:src")
            author = doc.select(".quick-info-item:has(.fa-pen-nib) strong").text()
            artist = doc.select(".quick-info-item:has(.fa-palette) strong").text()
            description = doc.select(".project-description").text()
            genre = doc.select(".project-genres .genre-tag").joinToString { it.text() }

            val statusText = doc.select(".status-pill").text().lowercase()
            status = when {
                statusText.contains("lançamento") -> SManga.ONGOING
                statusText.contains("completo") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }

        val chapterList = doc.select("#tab-capitulos-list .normal_ep").map { el ->
            SChapter.create().apply {
                val linkElement = el.selectFirst("a[href]")
                val num = el.select(".num").text()

                if (linkElement != null) {
                    setUrlWithoutDomain(linkElement.attr("abs:href"))
                } else {
                    url = "${manga.url}/ler/capitulo-$num"
                }

                var chapterName = "Capítulo $num"
                val title = el.select(".cell-title strong.line-3").text()
                if (title.isNotEmpty()) {
                    chapterName += " - $title"
                }
                name = chapterName

                date_upload = dateFormat.tryParseDate(el.select(".cell-num .text-muted").text())
            }
        }

        return SMangaUpdate(details, chapterList)
    }

    // =============================== Pages ================================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(getChapterUrl(chapter))
        val doc = response.asJsoup()

        for (script in doc.select("script:not([src])")) {
            val match = PAGE_LIST_REGEX.find(script.html()) ?: continue
            val urls = match.groupValues[1].parseAs<List<String>>()
            return urls.mapIndexed { i, url ->
                Page(i, imageUrl = if (url.startsWith("http")) url else "$baseUrl$url")
            }
        }

        // Anonymous visitors are redirected from the reader to /login, which bounces on to the home page.
        if ("/ler/" !in response.request.url.encodedPath || doc.html().contains("showLoginModal()")) {
            throw Exception(
                "Necessário fazer login. Abra o site no WebView (ícone de navegador " +
                    "no canto superior direito), faça login com sua conta e tente novamente.",
            )
        }
        throw Exception("Nenhuma página encontrada ou estrutura do site foi alterada.")
    }

    override fun imageRequest(page: Page): Request = super.imageRequest(page).newBuilder()
        .removeHeader("Referer")
        .removeHeader("Origin")
        .removeHeader("Upgrade-Insecure-Requests")
        .removeHeader("Sec-Fetch-Dest")
        .removeHeader("Sec-Fetch-Mode")
        .removeHeader("Sec-Fetch-Site")
        .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        .header("Sec-Fetch-Dest", "image")
        .header("Sec-Fetch-Mode", "no-cors")
        .header("Sec-Fetch-Site", "same-origin")
        .build()

    // ============================== Filters ===============================
    override fun getFilterList(data: JsonElement?) = FilterList(
        StatusFilter(),
        GenreFilter(),
    )

    // ============================== Utilities =============================
    private fun Document.parseCards(selector: String) = select(selector).map { el ->
        SManga.create().apply {
            setUrlWithoutDomain(el.attr("abs:href"))
            title = el.select(".card-title span").text()
            thumbnail_url = el.select(".card-thumb img").attr("abs:src")
        }
    }

    private fun ageGateInterceptor(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url

        if (url.host == baseHttpUrl.host) {
            val cookies = client.cookieJar.loadForRequest(url)
            if (cookies.none { it.name == "age_gate_consent" }) {
                val ageCookie = Cookie.Builder()
                    .name("age_gate_consent")
                    .value("{\"consentAt\":1777661090431,\"expiresAt\":1778265890431}")
                    .domain(url.host)
                    .path("/")
                    .build()

                val popCookie = Cookie.Builder()
                    .name("_popprepop")
                    .value("1")
                    .domain(url.host)
                    .path("/")
                    .build()

                client.cookieJar.saveFromResponse(url, listOf(ageCookie, popCookie))
            }
        }
        return chain.proceed(original)
    }

    companion object {
        private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yy", Locale.ROOT)

        private val PAGE_LIST_REGEX = Regex("""S\s*=\s*(\[[\s\S]*?])""")
    }
}
