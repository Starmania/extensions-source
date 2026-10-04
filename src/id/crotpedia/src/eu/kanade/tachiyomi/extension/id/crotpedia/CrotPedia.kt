package eu.kanade.tachiyomi.extension.id.crotpedia

import eu.kanade.tachiyomi.multisrc.zmanga.ZManga
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.asJsoup
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class CrotPedia : ZManga() {
    override val dateFormatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale("id"))

    override val hasProjectPage = false

    override val typeFilterValues = arrayOf(
        Pair("All", ""),
        Pair("Manga", "Manga"),
        Pair("Image-set", "Image-set"),
        Pair("Manhwa", "Manhwa"),
        Pair("One-shot", "One-shot"),
        Pair("Doujinshi", "Doujinshi"),
    )

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(baseUrl + chapter.url)
        // Some chapters are members-only and redirect anonymous readers to the login page.
        if (response.request.url.encodedPath.startsWith("/login")) {
            response.close()
            throw Exception("Log in via WebView to read this chapter")
        }
        return pageListParse(response.asJsoup())
    }
}
