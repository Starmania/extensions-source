package eu.kanade.tachiyomi.extension.en.likemanga

import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import kotlin.time.Duration.Companion.minutes

@Source
abstract class LikeManga : MadaraNoAjax() {
    override val supportsPostId = false
    override val chapterMode = ChapterMode.MangaAjax

    override fun OkHttpClient.Builder.configureClient() = rateLimit(permits = 60, period = 1.minutes) { it.encodedPath.startsWith("/api/") }

    // Lists mix in an advert card that links off-site
    override fun archiveSelector() = "div.page-item-detail:not(.custom-item-ad), .c-tabs-item__content"

    override fun nextPageSelector() = "a[aria-label=Next]"

    // Search lives under /home: /?s= only returns the landing page
    override fun searchUrlBuilder(page: Int, query: String): HttpUrl.Builder = baseUrl.toHttpUrl().newBuilder().apply {
        addPathSegment("home")
        if (page > 1) addPathSegments("page/$page")
        addQueryParameter(searchQueryParameter, query)
        addQueryParameter("post_type", "wp-manga")
    }

    override suspend fun fetchChapters(mangaPath: String, id: String, mangaPage: Document?): List<SChapter> {
        val slug = baseUrl.toHttpUrl().resolve(mangaPath)!!.pathSegments[1]
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/comics")
            .addPathSegment(slug)
            .addPathSegment("chapters")
            .addQueryParameter("per_page", "100")
            .addQueryParameter("order", "desc")
            .build()

        suspend fun fetchPage(page: Int) = client.get(url.newBuilder().addQueryParameter("page", page.toString()).build(), Fetch.XHR).parseAs<ChapterListResponse>().data

        val first = fetchPage(1)
        val rest = coroutineScope {
            (2..first.lastPage).map { async { fetchPage(it).chapters } }.awaitAll()
        }
        return (first.chapters + rest.flatten()).map { it.toSChapter(mangaPath) }.distinctBy(SChapter::url)
    }

    // The image CDN only accepts the site root as Referer, not the chapter page
    override fun getImageRequest(page: Page) = super.getImageRequest(page).newBuilder()
        .header("Referer", "$baseUrl/")
        .build()
}
