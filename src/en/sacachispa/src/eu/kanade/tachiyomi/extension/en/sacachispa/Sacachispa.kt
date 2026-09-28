package eu.kanade.tachiyomi.extension.en.sacachispa

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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

const val API_URL = "https://api.sacachispa.site/api"
const val CDN_URL = "https://cdn.sacachispa.site"

@Source
abstract class Sacachispa : KeiSource() {

    override val supportsLatest = false

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$API_URL/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            .apply { if (query.isNotBlank()) addQueryParameter("title", query) }
            .build()
        val dto = client.get(url).parseAs<PaginatedDto<MangaListDto>>()
        return MangasPage(dto.data.map { it.toSManga() }, dto.pagination.page < dto.pagination.pages)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val mangaId = url.pathSegments.takeIf { it.firstOrNull() == "manga" }?.getOrNull(1) ?: return null
        return fetchManga(mangaId).toSManga()
    }

    // The API resolves a slug as well as a UUID, so library entries saved by the old site keep working.
    private suspend fun fetchManga(slugOrId: String): MangaDto = client.get("$API_URL/manga/$slugOrId").parseAs<ResponseDto<MangaDto>>().data

    // Releases can only be filtered by manga UUID, which the slug-based url does not carry,
    // so details are fetched even when only chapters are asked for.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = fetchManga(manga.url)
        if (!fetchChapters) return SMangaUpdate(details.toSManga(), chapters)

        val url = "$API_URL/releases".toHttpUrl().newBuilder()
            .addQueryParameter("mangaId", details.id)
            .addQueryParameter("limit", "500")
            .build()
        val updatedChapters = client.get(url).parseAs<PaginatedDto<ReleaseDto>>().data
            .filterNot { it.chapter.patreonOnly }
            .map { it.toSChapter() }
            .sortedByDescending { it.chapter_number }
        return SMangaUpdate(details.toSManga(), updatedChapters)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get("$API_URL/releases/${chapter.url}/pages").parseAs<ResponseDto<PagesDto>>().data.toPageList()
}
