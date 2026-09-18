package eu.kanade.tachiyomi.extension.en.mangadrama

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Source
abstract class MangaDrama : HttpSource() {

    override val supportsLatest = true

    private val dateFormat: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("MMMM d, yyyy h:mm a")
        .toFormatter(Locale.ENGLISH)

    private val siteZone = ZoneId.of("Asia/Manila")

    override fun popularMangaRequest(page: Int) = browseRequest(page, sort = "views")

    override fun popularMangaParse(response: Response) = browseParse(response)

    override fun latestUpdatesRequest(page: Int) = browseRequest(page, sort = "updated")

    override fun latestUpdatesParse(response: Response) = browseParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Title search is a plain WordPress search that returns every match on one page.
        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addQueryParameter("s", query)
                .addQueryParameter("post_type", "manga")
                .build()
            return GET(url, headers)
        }

        return browseRequest(
            page,
            sort = filters.firstInstance<SortFilter>().selected,
            type = filters.firstInstance<TypeFilter>().selected,
            status = filters.firstInstance<StatusFilter>().selected,
        )
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.queryParameter("s") == null) return browseParse(response)

        val document = response.asJsoup()
        return MangasPage(document.select("article").map(::mangaFromElement), false)
    }

    private fun browseRequest(page: Int, sort: String, type: String = "", status: String = ""): Request {
        val url = "$baseUrl/advanced-filter/".toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegments("page/$page/")
            addQueryParameter("sort", sort)
            if (type.isNotEmpty()) addQueryParameter("type", type)
            if (status.isNotEmpty()) addQueryParameter("status", status)
        }.build()
        return GET(url, headers)
    }

    private fun browseParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.manga-item-details").map(::mangaFromElement)
        val hasNextPage = document.selectFirst("ul.uk-pagination li:not(.uk-disabled) a[aria-label=\"Next page\"]") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element) = SManga.create().apply {
        val link = element.selectFirst("h2 a")!!
        title = link.text()
        setUrlWithoutDomain(link.absUrl("href"))
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun getFilterList() = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
    )

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1#manga-title")!!.text()
            description = document.selectFirst("#manga-description")?.text()
            genre = document.select("#genre-tags a").joinToString { it.text() }
            author = document.infoValue("Illustrator")
            artist = document.infoValue("Designer")
            status = when (document.selectFirst("#manga-status")?.text()?.lowercase(Locale.ENGLISH)) {
                "ongoing", "caught up" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus", "source hiatus", "season end" -> SManga.ON_HIATUS
                "dropped" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        }
    }

    // Each row is `Label: <value><br>` inside a single element.
    private fun Document.infoValue(label: String): String? = selectFirst("div.manga-info-details")
        ?.html()
        ?.split("<br>")
        ?.map { Jsoup.parse(it).text() }
        ?.firstOrNull { it.startsWith("$label:") }
        ?.substringAfter(":")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    // Coin-locked chapters have no page data for anonymous users and are marked with a lock icon.
    override fun chapterListParse(response: Response): List<SChapter> = response.asJsoup().select("div.chapter-list a[href*=/chapter-]:not(:has([uk-icon*=\"lock\"]))").map { element ->
        SChapter.create().apply {
            setUrlWithoutDomain(element.absUrl("href"))
            name = element.selectFirst("div.uk-flex-none")!!.text()
            date_upload = dateFormat.tryParseDateTime(
                element.selectFirst("[uk-tooltip^=\"title:\"]")?.attr("uk-tooltip")?.substringAfter("title: ")?.substringBefore(";"),
                siteZone,
            )
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()

        val encrypted = ENCRYPTED_CHAPTER_REGEX.find(html)?.groupValues?.get(1)?.parseAs<EncryptedChapter>()
            ?: error("Chapter is not available")
        val key = DECRYPTION_KEY_REGEX.find(html)?.groupValues?.get(1)
            ?: error("Decryption key not found")

        return Jsoup.parseBodyFragment(encrypted.decrypt(key), baseUrl)
            .select("img")
            .mapIndexed { i, img -> Page(i, imageUrl = img.absUrl("src")) }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    @Serializable
    private class EncryptedChapter(
        private val ciphertext: String,
        private val iv: String,
        private val salt: String,
    ) {
        fun decrypt(base64Key: String): String {
            val passphrase = String(Base64.decode(base64Key, Base64.DEFAULT), Charsets.UTF_8)
            val keySpec = PBEKeySpec(passphrase.toCharArray(), salt.hexToBytes(), 999, 256)
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(keySpec).encoded

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv.hexToBytes()))
            return String(cipher.doFinal(Base64.decode(ciphertext, Base64.DEFAULT)), Charsets.UTF_8)
        }

        private fun String.hexToBytes() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private class SortFilter :
        Filter.Select<String>("Sort by", SORT_NAMES),
        HasValue {
        override val selected get() = SORT_VALUES[state]
    }

    private class TypeFilter :
        Filter.Select<String>("Type", TYPE_NAMES),
        HasValue {
        override val selected get() = TYPE_VALUES[state]
    }

    private class StatusFilter :
        Filter.Select<String>("Status", STATUS_NAMES),
        HasValue {
        override val selected get() = STATUS_VALUES[state]
    }

    private interface HasValue {
        val selected: String
    }

    companion object {
        private val ENCRYPTED_CHAPTER_REGEX = Regex("""var\s+InitMangaEncryptedChapter\s*=\s*(\{.*?\});""")
        private val DECRYPTION_KEY_REGEX = Regex(""""decryption_key":"([^"]+)"""")

        private val SORT_NAMES = arrayOf("Latest Updated", "Newest", "Oldest", "Most Views", "Daily Views", "Weekly Views", "Monthly Views", "Highest Rating", "Most Power Stone", "Most Followers")
        private val SORT_VALUES = arrayOf("updated", "new", "old", "views", "views_day", "views_week", "views_month", "rating", "power", "follow")

        private val TYPE_NAMES = arrayOf("All Types", "Comic", "Novel", "Oneshot")
        private val TYPE_VALUES = arrayOf("", "comic", "novel", "oneshot")

        private val STATUS_NAMES = arrayOf("All Status", "Ongoing", "Season End", "Completed", "Source Hiatus", "Caught Up", "Dropped")
        private val STATUS_VALUES = arrayOf("", "ongoing", "season_end", "completed", "source_hiatus", "caught_up", "dropped")
    }
}
