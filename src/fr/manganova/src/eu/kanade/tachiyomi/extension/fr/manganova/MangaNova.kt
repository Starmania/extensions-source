package eu.kanade.tachiyomi.extension.fr.manganova

import android.webkit.CookieManager
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
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI

@Source
abstract class MangaNova : KeiSource() {

    val api = "https://api.manga-nova.com"

    private val webViewCookieManager: CookieManager by lazy { CookieManager.getInstance() }

    // Default static token, shouldn't change
    private val defaultToken = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJtZW1icmVfaWQiOjAsIm1lbWJyZV91c2VybmFtZSI6bnVsbCwiaWF0IjoxNzA1NTc5MDQ1fQ.51qivLd2l3OKbDaYYzlntZJNnreRSBWO7p5Nsa2mAsA"

    override fun Headers.Builder.configureHeaders(): Headers.Builder {
        val cookies = webViewCookieManager.getCookie(baseUrl)
        var token = defaultToken
        if (cookies != null && cookies.isNotEmpty()) {
            val cookieHeaders = cookies.split("; ").toList()
            val tokenCookie = cookieHeaders.firstOrNull { it.startsWith("token=") }
            if (tokenCookie != null) {
                token = tokenCookie.replace("token=", "")
            }
        }
        return add("Authorization", "Bearer $token")
    }

    private suspend fun getCatalogue(): Catalogue = client.get("$api/catalogue/").parseAs<Catalogue>()

    // Popular
    override suspend fun getPopularManga(page: Int): MangasPage {
        val mangaList = getCatalogue().series.map { it.toDetailedSManga() }
        return MangasPage(mangaList, false)
    }

    // Latest
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val mangaList = getCatalogue().newSeries.map { it.toDetailedSManga() }
        return MangasPage(mangaList, false)
    }

    // Search
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.getOrNull(1) ?: return null
        return getCatalogue().series.find { it.slug == slug }?.toDetailedSManga()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val mangaList = getCatalogue().series
            .filter {
                query.isBlank() ||
                    it.title.contains(query, ignoreCase = true) ||
                    it.titleJap.contains(query, ignoreCase = true)
            }
            .map { it.toDetailedSManga() }
        return MangasPage(mangaList, false)
    }

    // Details and chapters
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val slug = URI(manga.url).path.split("/")[2]

        val updatedManga = async {
            if (fetchDetails) getMangaDetails(slug) else manga
        }
        val updatedChapters = async {
            if (fetchChapters) getChapterList(slug) else chapters
        }
        SMangaUpdate(updatedManga.await(), updatedChapters.await())
    }

    private suspend fun getMangaDetails(slug: String): SManga {
        val serie = getCatalogue().series.find { it.slug == slug }
            ?: throw UnsupportedOperationException("Bad SLUG")
        return serie.toDetailedSManga()
    }

    private suspend fun getChapterList(slug: String): List<SChapter> {
        val serie = client.get("$api/mangas/$slug").parseAs<DetailedSerieContainer>().serie
        val categories = serie.chapitres
        val chapterList = mutableListOf<SChapter>()

        val currentEpoch = System.currentTimeMillis()
        for (category in categories) {
            for (chapter in category.chapitres) {
                if (chapter.amount != 0) continue

                val chapter = SChapter.create().apply {
                    name = category.title + " - " + chapter.title + " - " + chapter.subTitle
                    setUrlWithoutDomain("$baseUrl/lecture-en-ligne/${serie.slug}/chapitre/${chapter.number}")
                    chapter_number = chapter.number
                    date_upload = currentEpoch + (chapter.availableTime * 1000L)
                }
                chapterList.add(chapter)
            }
        }

        return chapterList.sortedByDescending { it.chapter_number }
    }

    // Pages
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val splitedPath = URI(chapter.url).path.split("/")
        val slug = splitedPath[2]
        val chapterNumber = splitedPath[4]
        val images = client.get("$api/mangas/$slug/chapitres/$chapterNumber").parseAs<ChapterDetails>().images
        return images.map { pageData ->
            Page(pageData.pageNumber, imageUrl = pageData.image)
        }
    }
}
