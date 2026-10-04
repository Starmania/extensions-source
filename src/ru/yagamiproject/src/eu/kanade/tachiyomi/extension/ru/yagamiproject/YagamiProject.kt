package eu.kanade.tachiyomi.extension.ru.yagamiproject

import eu.kanade.tachiyomi.source.model.Filter
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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class YagamiProject : KeiSource() {
    // Popular
    override suspend fun getPopularManga(page: Int): MangasPage = mangasPageParse(client.get("$baseUrl/list-new/$page").asJsoup())

    private fun mangasPageParse(document: Document): MangasPage {
        val mangas = document.select(".list .group").map { element ->
            SManga.create().apply {
                element.select(".title a").first()!!.let {
                    setUrlWithoutDomain(it.absUrl("href"))
                    val baseTitle = it.attr("title")
                    title = if (baseTitle.isEmpty()) it.text() else baseTitle.split(" / ").min()
                }
                thumbnail_url = element.select(".cover_mini > img").attr("abs:src").replace("thumb_", "")
            }
        }
        val hasNextPage = document.select(".panel_nav .button a").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    // Latest
    override suspend fun getLatestUpdates(page: Int): MangasPage = mangasPageParse(client.get("$baseUrl/latest/$page").asJsoup())

    // Search
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = mangasPageParse(client.get(searchUrl(page, query, filters)).asJsoup())

    private fun searchUrl(page: Int, query: String, filters: FilterList): String {
        if (query.isNotBlank()) {
            return "$baseUrl/reader/search/?s=$query&p=$page"
        }
        val activeFilters = if (filters.isEmpty()) getFilterList() else filters
        activeFilters.firstInstanceOrNull<CategoryList>()?.let { filter ->
            if (filter.state > 0) {
                val catQ = getCategoryList()[filter.state].name
                return "$baseUrl/tags/$catQ"
            }
        }
        activeFilters.firstInstanceOrNull<FormatList>()?.let { filter ->
            if (filter.state > 0) {
                val formN = getFormatList()[filter.state].query
                return "$baseUrl/$formN"
            }
        }
        return "$baseUrl/list-new/$page"
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "series") return null
        return fetchMangaUpdate(
            SManga.create().apply { setUrlWithoutDomain(url.toString()) },
            emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        ).manga
    }

    // Details and chapters share one page
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(mangaDetailsParse(document).apply { url = manga.url }, chapterListParse(document))
    }

    private fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select(".large.comic .info").first()!!
        return SManga.create().apply {
            val titlestr = document.select("title").text()
                .substringBefore(" :: Yagami").split(" :: ").sorted()
            title = titlestr.first().replace(":: ", "")
            thumbnail_url = document.select(".cover img").first()!!.attr("abs:src")
            author = infoElement.select("li:contains(Автор(ы):)").first()?.text()
                ?.substringAfter("Автор(ы): ")?.split(" / ")?.min()?.replace("N/A", "")
            artist = infoElement.select("li:contains(Художник(и):)").first()?.text()
                ?.substringAfter("Художник(и): ")?.split(" / ")?.min()?.replace("N/A", "")
            status = when (infoElement.select("li:contains(Статус перевода:) span").first()?.text()) {
                "онгоинг" -> SManga.ONGOING
                "активный" -> SManga.ONGOING
                "завершён" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            genre = infoElement.select("li:contains(Жанры:)").first()?.text()
                ?.substringAfter("Жанры: ")
            val altSelector = infoElement.select("li:contains(Название:)")
            val altName = if (altSelector.isNotEmpty()) {
                "Альтернативные названия:\n" + altSelector.first().toString()
                    .replace("<li><b>Название</b>: ", "")
                    .replace("<br>", " / ")
                    .substringAfter(" / ")
                    .substringBefore("</li>") + "\n\n"
            } else {
                ""
            }
            val descriptElem = infoElement.select("li:contains(Описание:)").first()?.text()
                ?.substringAfter("Описание: ") ?: ""
            description = titlestr.last().replace(":: ", "") + "\n" + altName + descriptElem
        }
    }

    // Chapters
    private fun chapterListParse(document: Document): List<SChapter> = document.select(".list .element").map { element ->
        SChapter.create().apply {
            val chapter = element.select(".title a").first()!!
            val chapterScanDate = element.select(".meta_r")
            name = if (chapter.attr("title").isBlank()) chapter.text() else chapter.attr("title")
            chapter_number = name.substringBefore(":").substringAfterLast(" ")
                .substringAfterLast("№").substringAfterLast("#").toFloatOrNull()
                ?: chapter.attr("href").substringBeforeLast("/").substringAfterLast("/").toFloatOrNull()
                ?: -1f
            setUrlWithoutDomain(chapter.absUrl("href"))
            date_upload = parseDate(chapterScanDate.text().substringAfter(", "))
            scanlator = chapterScanDate.select("a").takeIf { it.isNotEmpty() }
                ?.joinToString(" / ") { it.text() }
        }
    }

    private fun parseDate(date: String): Long = when (date) {
        "Сегодня" -> System.currentTimeMillis()
        "Вчера" -> System.currentTimeMillis() - 24 * 60 * 60 * 1000
        else -> dateFormat.tryParse(date)
    }

    // Pages
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val webtoonsel = document.select(".web_pictures img.web_img")
        return if (webtoonsel.isEmpty()) {
            document.select(".dropdown li a").map {
                Page(it.text().substringAfter("Стр. ").toInt(), it.absUrl("href"))
            }
        } else {
            webtoonsel.mapIndexed { i, img -> Page(i, imageUrl = img.attr("abs:src")) }
        }
    }

    override suspend fun getImageUrl(page: Page): String {
        val document = client.get(page.url).asJsoup()
        val defaultimg = document.select("#page img").attr("abs:src")
        return if (defaultimg.contains("string(1)")) {
            document.select("#get_download").first()!!.absUrl("href")
        } else {
            defaultimg
        }
    }

    // Filters
    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("ПРИМЕЧАНИЕ: Фильтры исключают другдруга!"),
        CategoryList(getCategoryList().map { it.name }.toTypedArray()),
        FormatList(getFormatList().map { it.name }.toTypedArray()),
    )

    companion object {
        private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.US)
    }
}
