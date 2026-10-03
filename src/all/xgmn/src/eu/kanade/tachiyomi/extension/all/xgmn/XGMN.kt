package eu.kanade.tachiyomi.extension.all.xgmn

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class XGMN : KeiSource() {

    // baseUrl only redirects to the mirror currently serving the site
    private var redirectUrl: String? = null

    private val currentBaseUrl: String
        get() = redirectUrl ?: baseUrl

    private suspend fun getDocument(url: String): Document = client.get(url).asJsoup().also { doc ->
        redirectUrl = redirectUrl ?: doc.location().toHttpUrl().let { "${it.scheme}://${it.host}" }
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList(getDocument("$currentBaseUrl/top.html"))

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList(getDocument("$currentBaseUrl/new.html"))

    private fun parseMangaList(doc: Document): MangasPage {
        val cur = doc.selectFirst(".current")?.text()?.toInt()
        return MangasPage(
            doc.select(".related_box").map {
                SManga.create().apply {
                    thumbnail_url = it.selectFirst("img")?.absUrl("src")
                    it.selectFirst("a")!!.let {
                        title = it.attr("title")
                        setUrlWithoutDomain(it.absUrl("href"))
                    }
                }
            },
            cur != null && cur < doc.selectFirst(".pagination strong")!!.text().toInt(),
        )
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val builder = currentBaseUrl.toHttpUrl().newBuilder()
        if (query.isBlank()) {
            builder.addPathSegments(filters.first().toString())
            if (page > 1) builder.addPathSegment("page_$page.html")
            return parseMangaList(getDocument(builder.toString()))
        }

        builder.addPathSegments("plus/search/index.asp")
            .addQueryParameter("keyword", query)
            .addQueryParameter("p", page.toString())
        val doc = getDocument(builder.toString())
        val current = doc.selectFirst(".current")!!.text().toInt()
        return MangasPage(
            doc.select(".node > p > a").map {
                SManga.create().apply {
                    title = it.text()
                    setUrlWithoutDomain(it.absUrl("href"))
                    thumbnail_url = "$currentBaseUrl/uploadfile/pic/${ID_REGEX.find(url)?.value}.jpg"
                }
            },
            current < doc.select(".list .pagination a").size,
        )
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = getDocument(getMangaUrl(manga))
        val details = SManga.create().apply {
            url = manga.url
            title = manga.title
            author = doc.selectFirst(".item-2")?.text()?.substringAfter("模特：")
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            status = SManga.COMPLETED
        }
        val chapter = SChapter.create().apply {
            setUrlWithoutDomain(doc.selectFirst(".current")!!.absUrl("href"))
            name = doc.selectFirst(".article-title")!!.text()
            chapter_number = 1F
            date_upload = DATE_FORMAT.tryParse(
                doc.selectFirst(".item-1")?.text()?.substringAfter("更新："),
            )
        }
        return SMangaUpdate(details, listOf(chapter))
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = client.get(getChapterUrl(chapter)).asJsoup()
        val prefix = doc.selectFirst(".current")!!.absUrl("href").substringBeforeLast(".html")
        val total = PAGE_SIZE_REGEX.find(doc.selectFirst(".article-title")!!.text())!!.value
        val size = doc.select(".article-content p > img").size
        return List(total.toInt()) {
            Page(
                it,
                prefix + (it / size).let { v -> if (v == 0) "" else "_$v" } + ".html#${it % size + 1}",
            )
        }
    }

    override suspend fun getImageUrl(page: Page): String {
        val seq = page.url.substringAfterLast('#')
        val url = client.get(page.url).asJsoup()
            .selectXpath("//*[contains(@class,'article-content')]/p[@*[contains(.,'center')]]/img[position()=$seq]")
            .first() ?: throw Exception("没找到图片")
        return "$currentBaseUrl/${getUrlWithoutDomain(url.absUrl("src"))}"
    }

    override fun getFilterList(data: JsonElement?) = buildFilterList()

    private fun getUrlWithoutDomain(url: String): String {
        val prefix = listOf("http://", "https://").firstOrNull(url::startsWith)
        return url.substringAfter(prefix ?: "").substringAfter('/')
    }

    companion object {
        val ID_REGEX = Regex("\\d+(?=\\.html)")
        val PAGE_SIZE_REGEX = Regex("\\d+(?=P)")
        val DATE_FORMAT = SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
    }
}
