package eu.kanade.tachiyomi.extension.pt.noindexscan

import eu.kanade.tachiyomi.multisrc.madara.GenreRoute
import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class HanamiHeaven : MadaraNoAjax() {
    override val chapterDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
    override fun OkHttpClient.Builder.configureClient() = rateLimit(3, 2.seconds)

    override val mangaDetailsSelectorTitle = "h1#mch-work-title"
    override val mangaDetailsSelectorAuthor = "dt:contains(Autor) + dd a"
    override val mangaDetailsSelectorArtist = "dt:contains(Artista) + dd a"
    override val mangaDetailsSelectorStatus = ".mch-work__status"
    override val mangaDetailsSelectorDescription = "#mch-work-synopsis"
    override val mangaDetailsSelectorThumbnail = ".mch-work__cover img"
    override val mangaDetailsSelectorGenre = "dt:contains(Gêneros) + dd a"
    override val mangaDetailsSelectorTag = "dt:contains(Tags) + dd a"
    override val seriesTypeSelector = "dt:contains(Tipo) + dd"
    override val altNameSelector = "dt:contains(Nome alternativo) + dd"

    override val chapterMode = ChapterMode.MangaAjax

    // Genre links are /todas-as-obras/?genero=<slug>, a page without archive cards, while related
    // titles are read from the route in the memo. /manga-genre/<slug>/ still lists them.
    override fun parseDetails(document: Document, id: String, preserveUrl: String?): SManga = super.parseDetails(document, id, preserveUrl).apply {
        val genres = document.select(mangaDetailsSelectorGenre).mapNotNull { element ->
            val slug = element.attr("abs:href").toHttpUrl().queryParameter("genero") ?: return@mapNotNull null
            GenreRoute(element.text(), slug, "/$genreDirectory/$slug/")
        }
        memo = mangaMemo(
            path = document.location().toHttpUrl().encodedPath,
            genres = genres,
            legacyId = id.takeIf { preserveUrl?.all(Char::isDigit) == false },
        )
    }
}
