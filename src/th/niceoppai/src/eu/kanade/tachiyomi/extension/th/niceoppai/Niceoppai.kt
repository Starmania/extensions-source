package eu.kanade.tachiyomi.extension.th.niceoppai

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.lib.unpacker.Unpacker
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

@Source
abstract class Niceoppai : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = connectTimeout(1.minutes)
        .readTimeout(1.minutes)
        .writeTimeout(1.minutes)
        .addInterceptor(ImageInterceptor())

    override suspend fun getPopularManga(page: Int): MangasPage = getMangaList("$baseUrl/manga_list/all/any/most-popular-monthly/$page")

    override suspend fun getLatestUpdates(page: Int): MangasPage = getMangaList("$baseUrl/manga_list/all/any/last-updated/$page")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val orderByFilter = filters.firstInstanceOrNull<OrderByFilter>()
        val orderByState = orderByFilter?.state ?: 0
        val orderByString = ORDER_BY_FILTER_OPTIONS_VALUES[orderByState]

        return if (orderByState != 0) {
            getMangaList("$baseUrl/manga_list/all/any/$orderByString/$page")
        } else {
            getMangaList("$baseUrl/manga_list/search/$query/$orderByString/$page")
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.host.endsWith("niceoppai.net")) return null
        val slug = url.pathSegments.first().takeIf { it.isNotEmpty() && it != "manga_list" } ?: return null
        val manga = SManga.create().apply { this.url = "/$slug/" }
        return parseMangaDetails(client.get(getMangaUrl(manga)).asJsoup()).apply { this.url = manga.url }
    }

    private suspend fun getMangaList(url: String): MangasPage {
        val document = client.get(url).asJsoup()
        val mangas = document.select("div.feed div.fcard").map { element ->
            SManga.create().apply {
                val link = element.selectFirst("a.fcard__title")!!
                title = link.text()
                setUrlWithoutDomain(link.attr("abs:href"))
                thumbnail_url = element.selectFirst("img.cover__img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.selectFirst("ul.pgg a:containsOwn(Next)") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun getStatus(status: String) = when (status) {
        "ยังไม่จบ" -> SManga.ONGOING
        "จบแล้ว" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(parseMangaDetails(document), getChapterList(document))
    }

    private fun parseMangaDetails(document: Document): SManga {
        val info = document.selectFirst("div.series__info")!!
        val facts = info.select("div.fact").associate { it.selectFirst("span")!!.text() to it.selectFirst("b")!! }

        return SManga.create().apply {
            title = info.selectFirst("h1")!!.text()
            author = facts["ผู้แต่ง"]?.text()
            artist = author
            status = facts["สถานะ"]?.text()?.let { getStatus(it) } ?: SManga.UNKNOWN
            genre = info.select("a.chip--genre").joinToString { it.text() }
            description = info.selectFirst("p.series__syn")?.text()
            thumbnail_url = document.selectFirst("div.series__cover img.cover__img")?.attr("abs:src")
        }
    }

    // The first page of the chapter list is the manga page itself; the rest are at /chapter-list/<n>/
    private suspend fun getChapterList(mangaPage: Document): List<SChapter> {
        var document = mangaPage
        val chapters = parseChapters(document).toMutableList()
        while (true) {
            val next = document.selectFirst("ul.pgg a:containsOwn(Next)")?.attr("abs:href") ?: break
            document = client.get(next).asJsoup()
            chapters += parseChapters(document)
        }
        return chapters
    }

    private fun parseChapters(document: Document): List<SChapter> = document.select("a.chrow").map { row ->
        SChapter.create().apply {
            setUrlWithoutDomain(row.attr("abs:href"))
            val number = row.selectFirst("div.chrow__n")!!.text().removePrefix("#")
            val subtitle = row.selectFirst("div.chrow__t")?.text().orEmpty()
            name = if (subtitle.isEmpty()) "ตอนที่ $number" else "ตอนที่ $number - $subtitle"
            chapter_number = number.toFloatOrNull() ?: -1f
            date_upload = parseChapterDate(row.selectFirst("div.chrow__d")?.text())
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        // Scrambled pages are an empty div followed by a packed script handing the tile map to the reader
        val images = document.select("#image-container img, #image-container div[id] + script").map { element ->
            if (element.tagName() == "img") {
                return@map if (element.hasAttr("data-src")) element.attr("abs:data-src") else element.attr("abs:src")
            }
            val data = Unpacker.unpack(element.data()).replace("\\", "")
                .substringAfter(",").substringBeforeLast(")")
                .parseAs<ScrambledImage>()
            "${data.u}#${data.toJsonString()}"
        }
        return images.mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        OrderByFilter(
            ORDER_BY_FILTER_TITLE,
            ORDER_BY_FILTER_OPTIONS.zip(ORDER_BY_FILTER_OPTIONS_VALUES).toList(),
            0,
        ),
    )

    private fun parseChapterDate(date: String?): Long {
        if (date == null) return 0L

        return when {
            WordSet("yesterday", "يوم واحد").startsWith(date) -> {
                Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, -1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            WordSet("today").startsWith(date) -> {
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            WordSet("يومين").startsWith(date) -> {
                Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, -2)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            WordSet("ago", "atrás", "önce", "قبل").endsWith(date) -> parseRelativeDate(date)
            ordinalRegex.containsMatchIn(date) -> {
                val cleanedDate = date.split(" ").joinToString(" ") {
                    if (ordinalRegex.containsMatchIn(it)) it.replace(ordinalRegex, "") else it
                }
                dateFormat.tryParseDate(cleanedDate)
            }
            else -> dateFormat.tryParseDate(date)
        }
    }

    private fun parseRelativeDate(date: String): Long {
        val number = relativeDateRegex.find(date)?.groupValues?.get(1)?.toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()

        return when {
            WordSet("hari", "gün", "jour", "día", "dia", "day", "วัน", "ngày", "giorni", "أيام").anyWordIn(date) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
            WordSet("jam", "saat", "heure", "hora", "hour", "ชั่วโมง", "giờ", "ore", "ساعة").anyWordIn(date) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
            WordSet("menit", "dakika", "min", "minute", "minuto", "นาที", "دقائق").anyWordIn(date) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
            WordSet("detik", "segundo", "second", "วินาที").anyWordIn(date) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
            WordSet("week").anyWordIn(date) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number * 7) }.timeInMillis
            WordSet("month").anyWordIn(date) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
            WordSet("year").anyWordIn(date) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
            else -> 0L
        }
    }

    companion object {
        private val dateFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US)
        private val relativeDateRegex = Regex("""(\d+)""")
        private val ordinalRegex = Regex("""\d(st|nd|rd|th)""")
    }
}

class WordSet(private vararg val words: String) {
    fun anyWordIn(dateString: String): Boolean = words.any { dateString.contains(it, ignoreCase = true) }
    fun startsWith(dateString: String): Boolean = words.any { dateString.startsWith(it, ignoreCase = true) }
    fun endsWith(dateString: String): Boolean = words.any { dateString.endsWith(it, ignoreCase = true) }
}
