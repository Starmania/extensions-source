package eu.kanade.tachiyomi.extension.id.tooncubus

import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistMangaDto
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Source
abstract class Tooncubus : ZeistManga() {

    override val pageListSelector = "center"

    override val supportsChapterFeed = false

    override suspend fun getChapterList(feedUrl: String, doc: Document?): List<SChapter> = doc!!.selectFirst("ul.series-chapterlist")!!.select("div.flexch-infoz").map { element ->
        SChapter.create().apply {
            name = element.select("span").text()
            url = element.select("a").attr("href") // The website uses another domain for reading
        }
    }

    override fun getChapterUrl(chapter: SChapter) = chapter.url

    // The reader's custom domain (tooncubus-read.my.id) lost its DNS, but the Blogger blog
    // behind it is still updated, so its posts are read through the feed by path.
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = "https://www.blogger.com/feeds/$READER_BLOG_ID/posts/default".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "json")
            .addQueryParameter("path", chapter.url.toHttpUrl().encodedPath)
            .build()
        val content = client.get(url).parseAs<ZeistMangaDto>().feed!!.entry!!.first().content!!.t
        return pageListParse(Jsoup.parseBodyFragment(content))
    }
}

private const val READER_BLOG_ID = "5112092159451965079"
