package eu.kanade.tachiyomi.extension.all.honeytoon

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.lib.i18n.Intl
import keiyoushi.network.addCookie
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okio.IOException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Honeytoon :
    KeiSource(),
    ConfigurableSource {

    private val langPath: String
        get() = when (lang) {
            "en" -> ""
            "pt-BR" -> "/pt"
            else -> "/$lang"
        }

    private val rankingUrl: String get() = "$baseUrl$langPath/ranking"

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val isAdultContentEnabled: Boolean
        get() = preferences.getBoolean(PREF_ADULT_KEY, false)

    override fun OkHttpClient.Builder.configureClient() = addInterceptor(ScrambledImageInterceptor())
        .addCookie { listOf("eighteen" to if (isAdultContentEnabled) "1" else "0") }
        .rateLimit(3, 1.seconds)

    private val intl = Intl(
        language = lang,
        baseLanguage = "en",
        availableLanguages = setOf("en", "pt-BR"),
        classLoader = this::class.java.classLoader!!,
    )

    override suspend fun getPopularManga(page: Int): MangasPage = mangaParse(client.get(rankingUrl).asJsoup(), ".section.popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage = mangaParse(client.get(rankingUrl).asJsoup(), ".section.new")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val form = FormBody.Builder()
            .add("query", query)
            .build()
        val mangas = client.post("$baseUrl$langPath/api/comic/search", headers, form).parseAs<List<SearchDto>>().map {
            SManga.create().apply {
                title = Jsoup.parseBodyFragment(it.title).selectFirst("body")!!.ownText()
                thumbnail_url = "https://pic.honeytoon.com/${it.image}"
                url = it.link
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null

        val document = client.get(url).asJsoup()
        return SManga.create().apply {
            parseDetails(document)
            // Only set here: the details page cover is very large, so listing covers are kept otherwise.
            thumbnail_url = document.selectFirst(".comic-book-img img")?.absUrl("src")
            setUrlWithoutDomain(document.location())
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(
            manga = manga.apply { parseDetails(document) },
            chapters = chapterListParse(document),
        )
    }

    private fun SManga.parseDetails(document: Document) {
        title = document.selectFirst("h1")!!.text()
        author = document.select(".comic-book__story-art a").joinToString { it.text() }
        description = document.selectFirst(".comic-book__desc")?.text()
        genre = document.select(".comic-book-content a[href*=genre], .comic-tag").joinToString { it.text() }
        status = when {
            document.selectFirst(".comic-book-content .label__item--complete") != null -> SManga.COMPLETED
            document.selectFirst(".comic-book-content .label__item--dayofpublication") != null -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun chapterListParse(document: Document): List<SChapter> = document.select(".comic-list-items > a")
        .mapIndexed { index, element ->
            val isLocked = element.selectFirst(".lock-ico, .token-ico") != null
            SChapter.create().apply {
                name = buildString {
                    append(element.selectFirst(".comic-list__title-desc")!!.text())
                    if (isLocked) {
                        append(" \uD83D\uDD12")
                    }
                }

                date_upload = dateFormat.tryParse(element.selectFirst(".comic-list__title-date")?.text())
                setUrlWithoutDomain(
                    element.absUrl("href").takeIf { !isLocked }
                        ?: (document.location() + "/$index#locked"),
                )
            }
        }.reversed()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (chapter.url.contains("#locked")) {
            throw IOException(intl["chapter_locked_warning"])
        }

        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return document.select(".single__item img, .comic-canvas-scramble").mapIndexed { index, element ->
            when (element.tagName()) {
                "img" -> Page(index, imageUrl = element.imgSrc())
                else -> Page(index, imageUrl = "$baseUrl/api/common/resource/sync?t=${element.attr("data-token")}")
            }
        }
    }

    private fun Element.imgSrc() = when {
        hasAttr("data-src") -> absUrl("data-src")
        else -> absUrl("src")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ADULT_KEY
            title = intl["switch_adult_title"]
            summary = intl["switch_adult_summary"]
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, intl["switch_adult_toast"], Toast.LENGTH_LONG).show()
                true
            }
        }.also(screen::addPreference)
    }

    private fun mangaParse(document: Document, cssSelector: String): MangasPage {
        val mangas = document.select("$cssSelector .preview-card__link").map { element ->
            SManga.create().apply {
                title = element.selectFirst(".preview-card__title")!!.text()
                thumbnail_url = element.selectFirst(".preview-card__image")?.absUrl("src")
                setUrlWithoutDomain(element.absUrl("href"))
            }
        }
        return MangasPage(mangas, hasNextPage = false)
    }

    private val dateFormat: SimpleDateFormat by lazy {
        val locale = when {
            lang.contains("-") -> {
                val (lang, country) = lang.split("-")
                Locale(lang, country)
            }

            else -> Locale(lang)
        }
        SimpleDateFormat("MMMM dd , yyyy", locale)
    }

    @Serializable
    class SearchDto(
        val title: String,
        val image: String,
        val link: String,
    )

    companion object {
        private const val PREF_ADULT_KEY = "prefAdultKey"
    }
}
