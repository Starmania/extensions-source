package eu.kanade.tachiyomi.extension.all.hentaicosplay

import eu.kanade.tachiyomi.source.model.Filter
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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class HentaiCosplay : KeiSource() {

    // Listings are the only place the post date appears; the gallery page does not carry it.
    private val dateCache = mutableMapOf<String, String>()

    override suspend fun getPopularManga(page: Int): MangasPage = getListing("$baseUrl/ranking/page/$page/")

    override suspend fun getLatestUpdates(page: Int): MangasPage = getListing("$baseUrl/search/page/$page/")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotEmpty()) {
            val keyword = query.trim().replace(" ", "+")
            return getListing("$baseUrl/search/keyword/$keyword/page/$page/")
        }

        val tag = filters.firstInstanceOrNull<TagFilter>()?.selected.orEmpty()
        return if (tag.isNotEmpty()) {
            getListing("$baseUrl${tag}page/$page/")
        } else {
            getListing("$baseUrl/search/page/$page/")
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || !url.encodedPath.startsWith("/image/")) return null

        val document = client.get(url).asJsoup()
        return SManga.create().apply {
            setUrlWithoutDomain(url.toString())
            title = document.selectFirst("#title h2")!!.text()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            parseDetails(document)
        }
    }

    private suspend fun getListing(url: String): MangasPage {
        val document = client.get(url).asJsoup()

        return if (document.selectFirst("div.image-list-item") == null) {
            parseMobileListing(document)
        } else {
            parseDesktopListing(document)
        }
    }

    private fun parseMobileListing(document: Document): MangasPage {
        val entries = document.select("#entry_list > li > a[href*=/image/]")
            .map { element ->
                SManga.create().apply {
                    setUrlWithoutDomain(element.absUrl("href"))
                    thumbnail_url = element.selectFirst("img")
                        ?.absUrl("src")
                        ?.replace("http://", "https://")
                    title = element.selectFirst("span:not(.posted)")!!.text()
                    element.selectFirst("span.posted")
                        ?.text()?.also { dateCache[url] = it }
                }
            }
        val hasNextPage = document.selectFirst("a.paginator_page[rel=next]") != null

        return MangasPage(entries, hasNextPage)
    }

    private fun parseDesktopListing(document: Document): MangasPage {
        val entries = document.select("div.image-list-item:has(a[href*=/image/])")
            .map { element ->
                SManga.create().apply {
                    setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
                    thumbnail_url = element.selectFirst("img")
                        ?.absUrl("src")
                        ?.replace("http://", "https://")
                    title = element.select(".image-list-item-title").text()
                    element.selectFirst(".image-list-item-regist-date")
                        ?.text()?.also { dateCache[url] = it }
                }
            }
        val hasNextPage = document.selectFirst("div.wp-pagenavi > a[rel=next]") != null

        return MangasPage(entries, hasNextPage)
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/ranking-tag/").asJsoup()
        return document.select("#tags a").map {
            TagDto(it.text().replace(tagNumRegex, "").trim(), it.attr("href"))
        }.toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val tags = data?.parseAs<List<TagDto>>().orEmpty()
        if (tags.isEmpty()) return FilterList()

        return FilterList(
            Filter.Header("Ignored with text search"),
            Filter.Separator(),
            TagFilter(listOf(TagDto("", "")) + tags),
        )
    }

    @Serializable
    private class TagDto(val name: String, val path: String)

    private class TagFilter(private val tags: List<TagDto>) : Filter.Select<String>("Ranked Tags", tags.map { it.name }.toTypedArray()) {
        val selected get() = tags[state].path
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (fetchDetails) {
            manga.parseDetails(client.get(getMangaUrl(manga)).asJsoup())
        }

        val chapterList = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    name = "Gallery"
                    url = manga.url.replace("/image/", "/story/")
                    date_upload = dateFormat.tryParseDate(dateCache[manga.url])
                },
            )
        } else {
            chapters
        }

        return SMangaUpdate(manga, chapterList)
    }

    private fun SManga.parseDetails(document: Document) {
        genre = document.select("#detail_tag a[href*=/tag/]").eachText().joinToString()
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        status = SManga.COMPLETED
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return document.select("amp-img[src*=upload]:not(.related-thumbnail)")
            .mapIndexed { index, element ->
                Page(index, imageUrl = element.attr("src"))
            }
    }

    companion object {
        private val tagNumRegex = Regex("""(\(\d+\))""")
        private val dateFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ENGLISH)
    }
}
