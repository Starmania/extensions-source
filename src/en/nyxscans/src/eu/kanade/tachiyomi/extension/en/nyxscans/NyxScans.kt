package eu.kanade.tachiyomi.extension.en.nyxscans

import eu.kanade.tachiyomi.multisrc.iken.Iken
import eu.kanade.tachiyomi.source.model.MangasPage
import keiyoushi.annotation.Source
import okhttp3.Response

@Source
abstract class NyxScans : Iken() {

    // The search API has no isNovel field and some novels have seriesType MANHWA,
    // so the "[Novel]" title tag is the only thing left to tell them apart.
    override suspend fun parseSearchMangaList(response: Response): MangasPage {
        val mangasPage = super.parseSearchMangaList(response)
        val mangas = mangasPage.mangas.filterNot { novelTag.containsMatchIn(it.title) }
        return MangasPage(mangas, mangasPage.hasNextPage)
    }

    private val novelTag = Regex("""[\[(]novel[\])]""", RegexOption.IGNORE_CASE)
}
