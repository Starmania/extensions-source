package eu.kanade.tachiyomi.extension.en.sacachispa

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

const val API_URL = "https://api.sacachispa.site/api"
const val CDN_URL = "https://cdn.sacachispa.site"

@Source
abstract class Sacachispa : HttpSource() {

    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request = searchMangaRequest(page, "", FilterList())

    override fun popularMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<PaginatedDto<MangaListDto>>()
        return MangasPage(dto.data.map { it.toSManga() }, dto.pagination.page < dto.pagination.pages)
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // ============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$API_URL/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            .apply { if (query.isNotBlank()) addQueryParameter("title", query) }
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Details ==============================

    // The API resolves a slug as well as a UUID, so library entries saved by the old site keep working.
    override fun mangaDetailsRequest(manga: SManga): Request = GET("$API_URL/manga/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<ResponseDto<MangaDto>>().data.toSManga()

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}/${manga.url}"

    // ============================= Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    // Releases can only be filtered by manga UUID, which the slug-based url does not carry.
    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaId = response.parseAs<ResponseDto<MangaDto>>().data.id
        val url = "$API_URL/releases".toHttpUrl().newBuilder()
            .addQueryParameter("mangaId", mangaId)
            .addQueryParameter("limit", "500")
            .build()
        return client.newCall(GET(url, headers)).execute()
            .parseAs<PaginatedDto<ReleaseDto>>().data
            .filterNot { it.chapter.patreonOnly }
            .map { it.toSChapter() }
            .sortedByDescending { it.chapter_number }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url}"

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request = GET("$API_URL/releases/${chapter.url}/pages", headers)

    override fun pageListParse(response: Response): List<Page> = response.parseAs<ResponseDto<PagesDto>>().data.toPageList()

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
