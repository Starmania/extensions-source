package eu.kanade.tachiyomi.extension.ar.yonabar

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.Page
import keiyoushi.annotation.Source
import org.jsoup.nodes.Document

@Source
abstract class YonaBar : Madara() {
    override val mangaSubString = "yaoi"

    override val pageListParseSelector = ".reading-content img:not(#image-0\\.0)"

    // The hosts in the markup serve a placeholder GIF; an inline script swaps them for the
    // real ones, and the target host rotates (medium1xf -> medium1xr), so read it from there.
    override fun parsePages(document: Document): List<Page> {
        val hosts = hostSwapRegex.findAll(document.select("script:not([src])").html())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        return super.parsePages(document).onEach { page ->
            page.imageUrl = hosts.fold(page.imageUrl!!) { url, (from, to) -> url.replaceFirst(from, to) }
        }
    }

    private val hostSwapRegex = Regex("""src\.replace\("([^"]+)",\s*"([^"]+)"\)""")
}
