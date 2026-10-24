package eu.kanade.tachiyomi.extension.es.mangashiina

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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class MangaMukai : KeiSource() {

    private val apiUrl get() = "$baseUrl/wp-json/mangamukai/v1"

    override suspend fun getPopularManga(page: Int): MangasPage = catalogPage(fetchCatalog().sortedByDescending { it.totalViews })

    override suspend fun getLatestUpdates(page: Int): MangasPage = catalogPage(fetchCatalog().sortedByDescending { it.latestUpdateAt })

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = catalogPage(fetchCatalog().filter { it.titulo.contains(query.trim(), ignoreCase = true) })

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "manga") return null
        val id = url.pathSegments.getOrNull(1)?.takeIf { it.all(Char::isDigit) } ?: return null
        return fetchManga(id)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        // entries saved before the site rebuild hold a MangaThemesia "/manga/<slug>/" url,
        // and the new API only resolves numeric ids
        if (!manga.url.all(Char::isDigit)) throw Exception("Migrate this entry to Manga Mukai again: the site changed its URLs")
        val details = if (fetchDetails) async { fetchManga(manga.url) } else null
        val chapterList = if (fetchChapters) {
            async {
                client.get("$apiUrl/series/${manga.url}/chapters").parseAs<ChapterListDto>()
                    .chapters.map { it.toSChapter() }.reversed()
            }
        } else {
            null
        }
        SMangaUpdate(details?.await() ?: manga, chapterList?.await() ?: chapters)
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // locked chapters answer 401 with a JSON explanation worth showing instead of images
        val content = client.get("$apiUrl/chapters/content?id=${chapter.url}", ensureSuccess = false).parseAs<ChapterContentDto>()
        if (content.images.isEmpty()) throw Exception(content.message ?: "No pages found")
        return content.images.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    // the catalog endpoint ignores paging and always returns every series
    private suspend fun fetchCatalog(): List<CatalogMangaDto> = client.get("$apiUrl/catalog").parseAs<CatalogDto>().mangas

    private fun catalogPage(mangas: List<CatalogMangaDto>) = MangasPage(mangas.map { it.toSManga() }, false)

    private suspend fun fetchManga(id: String): SManga = client.get("$apiUrl/manga/$id").parseAs<MangaDto>().toSManga()
}
