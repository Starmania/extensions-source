package eu.kanade.tachiyomi.extension.all.holonometria

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
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Holonometria : KeiSource() {

    private val langPath: String get() = if (lang == "ja") "" else "$lang/"

    override val supportsLatest get() = false

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(fetchMangaList(), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    // The site has no search; the whole catalogue is a single page, so filter it locally.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val search = query.trim()
        return MangasPage(fetchMangaList().filter { it.title.contains(search, true) }, false)
    }

    private suspend fun fetchMangaList(): List<SManga> {
        val document = client.get("$baseUrl/${langPath}alt/holonometria/manga/").asJsoup()

        return document.select(".manga__item").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
                title = element.select(".manga__title").text()
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null

        val path = url.encodedPath
        val prefix = "/${langPath}alt/holonometria/manga/"
        if (!path.startsWith(prefix)) return null
        val slug = path.removePrefix(prefix).substringBefore("/").ifEmpty { return null }

        val manga = SManga.create().apply { this.url = "$prefix$slug/ep0/" }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    // Details and chapters come from the same page, so both are always returned.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val updatedManga = manga.apply {
            title = document.select(".alt-nav__met-sub-link.is-current").text()
            thumbnail_url = document.select(".manga-detail__thumb img").attr("abs:src")
            description = document.select(".manga-detail__caption").text()

            val info = document.select(".manga-detail__person").html().split("<br>")

            author = info.firstOrNull { desc -> MANGA.any { desc.contains(it, true) } }
                ?.substringAfter("：")
                ?.substringAfter(":")
                ?.trim()
                ?.replace("&amp;", "&")

            artist = info.firstOrNull { desc -> SCRIPT.any { desc.contains(it, true) } }
                ?.substringAfter("：")
                ?.substringAfter(":")
                ?.trim()
                ?.replace("&amp;", "&")
        }

        val updatedChapters = document.select(".manga-detail__list .manga-detail__list-item").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
                name = element.select(".manga-detail__list-title").text()
                date_upload = dateFormat.tryParse(element.selectFirst(".manga-detail__list-date")?.text())
            }
        }.reversed()

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        return document.select(".manga-detail__swiper-wrapper img").mapIndexed { idx, img ->
            Page(idx, imageUrl = img.attr("abs:src"))
        }.reversed()
    }

    companion object {
        private val MANGA = listOf("manga", "gambar", "漫画")
        private val SCRIPT = listOf("script", "naskah", "脚本")

        private val dateFormat by lazy {
            SimpleDateFormat("yyyy.MM.dd", Locale.ENGLISH)
        }
    }
}
