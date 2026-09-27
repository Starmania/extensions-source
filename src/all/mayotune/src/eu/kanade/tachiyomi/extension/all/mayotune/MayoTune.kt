package eu.kanade.tachiyomi.extension.all.mayotune

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
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response

@Source
abstract class MayoTune : KeiSource() {

    private val chapterEndpoint: String get() = if (lang == "ja") "raw" else ""

    private val names = mapOf(
        "en" to "Tune In to the Midnight Heart",
        "ja" to "真夜中ハートチューン",
        "all" to "Mayonaka Heart Tune",
    )

    private val source = SManga.create().apply {
        title = names[lang] ?: names["all"]!!
        url = "/"
        thumbnail_url = "$baseUrl/img/cover.jpg"
        author = "Masakuni Igarashi"
    }

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(listOf(source), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(listOf(source), false)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val mangas = mutableListOf<SManga>()

        if (names.any { it.value.lowercase().contains(query.lowercase()) } ||
            source.author?.lowercase()?.contains(query.lowercase()) == true
        ) {
            mangas.add(source)
        }

        return MangasPage(mangas, false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = source.takeIf { url.host == baseUrl.toHttpUrl().host }

    override fun getChapterUrl(chapter: SChapter): String {
        val id = (baseUrl + chapter.url).toHttpUrl().queryParameter("id")
        return "$baseUrl/$chapterEndpoint/chapter/$id"
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val details = if (fetchDetails) async { mangaDetailsParse(client.get(getMangaUrl(manga))) } else null
        val chapterList = if (fetchChapters) async { chapterListParse(client.get("$baseUrl/api/$chapterEndpoint/chapters")) } else null
        SMangaUpdate(details?.await() ?: manga, chapterList?.await() ?: chapters)
    }

    private fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        val statusText =
            document.selectFirst("div.text-center:contains(Status)")?.text()
                ?.substringBefore("Status")
                ?.trim()

        url = source.url
        title = source.title
        artist = source.artist
        author = source.author
        description = document.selectFirst(".text-lg")?.text()
        genre = document.selectFirst("span.text-sm:nth-child(2)")?.text()?.replace("•", ",")
        status = when (statusText) {
            "Ongoing" -> SManga.ONGOING
            "Completed" -> SManga.COMPLETED
            "Cancelled" -> SManga.CANCELLED
            "Hiatus" -> SManga.ON_HIATUS
            "Finished" -> SManga.PUBLISHING_FINISHED
            else -> SManga.UNKNOWN
        }
        thumbnail_url = document.selectFirst("img.object-contain")?.absUrl("src")
            ?.ifEmpty { source.thumbnail_url }
    }

    private fun chapterListParse(response: Response): List<SChapter> {
        val chapters = response.parseAs<List<ChapterDto>>()
        return chapters.sortedByDescending { it.number }.map { chapter ->
            SChapter.create().apply {
                url = chapter.getChapterURL(chapterEndpoint)
                name = chapter.getChapterTitle()
                chapter_number = chapter.number
                date_upload = chapter.getDateTimestamp()
            }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val dto = client.get(baseUrl + chapter.url).parseAs<ChapterDto>()
        return List(dto.pageCount) { index ->
            Page(index, imageUrl = "$baseUrl/api/manga/${dto.id}/${index + 1}")
        }
    }
}
