package eu.kanade.tachiyomi.extension.tr.hattorimanga

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter

@Source
abstract class HattoriManga : KeiSource() {

    private var csrfToken: String = ""

    override fun OkHttpClient.Builder.configureClient() = rateLimit(4)

    override suspend fun getPopularManga(page: Int): MangasPage = mangaListParse(client.get("$baseUrl/manga?page=$page").asJsoup())

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val mangas = client.get("$baseUrl/latest-chapters").parseAs<HMLatestUpdateDto>().chapters.map {
            SManga.create().apply {
                val manga = it.manga
                title = manga.title
                thumbnail_url = "$baseUrl/storage/${manga.thumbnail}"
                url = "/manga/${manga.slug}"
            }
        }.distinctBy { manga -> manga.title }
        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val selection = filters.firstInstanceOrNull<GenreList>()?.state.orEmpty().filter { it.state }

        if (query.isBlank() && selection.isNotEmpty()) {
            val url = "$baseUrl/manga-index".toHttpUrl().newBuilder()
            selection.forEach { genre ->
                url.addQueryParameter("genres[]", genre.id)
            }
            url.addQueryParameter("page", "$page")
            return mangaListParse(client.get(url.build()).asJsoup())
        }

        val mangas = search(query).parseAs<List<SearchManga>>().map {
            SManga.create().apply {
                title = it.title
                thumbnail_url = "$baseUrl/storage/${it.thumbnail}"
                url = "/manga/${it.slug}"
            }
        }
        return MangasPage(mangas, false)
    }

    private suspend fun search(query: String): Response {
        if (csrfToken.isEmpty()) {
            fetchCsrfToken()
        }

        val response = searchRequest(query, ensureSuccess = false)
        if (response.code != 419) {
            return response
        }

        // 419: the token expired with the session
        response.close()
        fetchCsrfToken()
        return searchRequest(query, ensureSuccess = true)
    }

    private suspend fun searchRequest(query: String, ensureSuccess: Boolean): Response {
        val searchHeaders = headers.newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
        val body = FormBody.Builder()
            .add("_token", csrfToken)
            .add("query", query)
            .build()

        return client.post("$baseUrl/manga/search", searchHeaders, body, ensureSuccess)
    }

    private suspend fun fetchCsrfToken() {
        // the token is tied to the session cookie, so a cached page would not renew it
        val document = client.get(baseUrl, cacheControl = CacheControl.FORCE_NETWORK).asJsoup()
        csrfToken = document.selectFirst("meta[name=csrf-token]")!!.attr("content")
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.size < 2 || url.pathSegments[0] != "manga") return null
        val manga = SManga.create().apply { this.url = "/manga/${url.pathSegments[1]}" }
        return fetchDetails(manga)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val details = async { if (fetchDetails) fetchDetails(manga) else manga }
        val chapterList = async { if (fetchChapters) fetchChapters(manga) else chapters }

        SMangaUpdate(details.await(), chapterList.await())
    }

    private suspend fun fetchDetails(manga: SManga): SManga {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SManga.create().apply {
            url = manga.url
            title = document.selectFirst("h3")!!.text()
            thumbnail_url = document.selectFirst(".set-bg")?.absUrl("data-setbg")
            description = document.selectFirst(".anime-details-text p")?.text()
            author = document.selectFirst(".anime-details-widget li:has(span:contains(Yazar))")?.ownText()
            artist = document.selectFirst(".anime-details-widget li:has(span:contains(Çizer))")?.ownText()

            document.selectFirst(".anime-details-widget li:has(span:contains(Durum))")?.ownText()?.let {
                status = when (it.lowercase()) {
                    "devam ediyor" -> SManga.ONGOING
                    "tamamlandı" -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }

            genre = document.selectFirst(".anime-details-widget li:has(span:contains(Etiketler))")
                ?.ownText()
                ?.split(",")
                ?.map { it.trim() }
                ?.joinToString()
        }
    }

    private suspend fun fetchChapters(manga: SManga): List<SChapter> {
        val slug = manga.url.substringAfterLast('/')
        val chapters = mutableListOf<SChapter>()
        var page = 1

        do {
            val dto = client.get("$baseUrl/load-more-chapters/$slug?page=$page").parseAs<HMChapterDto>()
            chapters += dto.chapters.map {
                SChapter.create().apply {
                    name = it.title
                    date_upload = dateFormat.tryParseDate(it.date.trim())
                    url = "${manga.url}/${it.chapterSlug}"
                }
            }
            page = dto.nextPage()
        } while (dto.hasNextPage())

        return chapters
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter)).asJsoup()
        .select(".image-wrapper img")
        .mapIndexed { index, element ->
            Page(index, imageUrl = element.absUrl("data-src"))
        }
        .ifEmpty { throw Exception("Oturum açmanız, WebView'ı açmanız ve oturum açmanız gerekir") }

    private fun mangaListParse(document: Document): MangasPage {
        val mangas = document
            .select(".product-card.grow-box")
            .map(::mangaFromElement)

        return MangasPage(
            mangas = mangas,
            hasNextPage = document.selectFirst(".pagination .page-item:last-child:not(.disabled)") != null,
        )
    }

    private fun mangaFromElement(element: Element) = SManga.create().apply {
        title = element.selectFirst("h5")!!.text()
        thumbnail_url = element.selectFirst(".img-con")?.absUrl("data-setbg")
        genre = element.select(".product-card-con ul li").joinToString { it.text() }
        val script = element.attr("onclick")
        setUrlWithoutDomain(REGEX_MANGA_URL.find(script)!!.groups[1]!!.value)
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/manga").asJsoup()
        .select(".tags-blog a")
        .map { it.text() }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?) = FilterList(
        listOfNotNull(
            data?.parseAs<List<String>>()
                ?.takeIf { it.isNotEmpty() }
                ?.let { GenreList("Türler", it) },
        ),
    )

    class GenreList(title: String, genres: List<String>) : Filter.Group<GenreCheckBox>(title, genres.map { GenreCheckBox(it) })

    class GenreCheckBox(name: String, val id: String = name) : Filter.CheckBox(name)

    companion object {
        val REGEX_MANGA_URL = """='([^']+)""".toRegex()
        val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
