package eu.kanade.tachiyomi.extension.ar.orcamanga

import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistManga
import eu.kanade.tachiyomi.multisrc.zeistmanga.ZeistMangaEntryContentDto
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

@Source
abstract class Orcamanga : ZeistManga() {
    // The blog template no longer renders post bodies: chapter images only remain in the
    // Blogger feed, which is addressed by post id rather than by the chapter's path.
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val html = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).body.string()
        val postId = postIdRegex.find(html)!!.groupValues[1]
        val post = client.get("$baseUrl/feeds/posts/default/$postId?alt=json", Fetch.XHR).parseAs<PostDto>()

        return Jsoup.parseBodyFragment(post.entry.content.t, baseUrl).select("img[src]").mapIndexed { i, img ->
            Page(i, imageUrl = img.absUrl("src"))
        }
    }

    private val postIdRegex = """'postId': '(\d+)'""".toRegex()
}

@Serializable
private class PostDto(val entry: PostEntryDto)

@Serializable
private class PostEntryDto(val content: ZeistMangaEntryContentDto)
