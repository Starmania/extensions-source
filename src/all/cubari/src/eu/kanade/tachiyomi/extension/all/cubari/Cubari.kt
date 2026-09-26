package eu.kanade.tachiyomi.extension.all.cubari

import android.os.Build
import android.util.Base64
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.Interceptor

@Source
abstract class Cubari : KeiSource() {

    private val cubariHeaders by lazy {
        headers.newBuilder()
            .set(
                "User-Agent",
                "(Android ${Build.VERSION.RELEASE}; " +
                    "${Build.MANUFACTURER} ${Build.MODEL}) " +
                    "Tachiyomi/${AppInfo.getVersionName()} ${Build.ID} " +
                    "Keiyoushi",
            ).build()
    }

    // The WebView interceptors replace the response body, so they must sit outside
    // KeiSource's CompressionInterceptor or the payload would be decoded as compressed.
    private fun clientWith(interceptor: Interceptor) = client.newBuilder()
        .apply { interceptors().add(0, interceptor) }
        .build()

    // Popular, latest and search all read the reading history cubari.moe keeps in the
    // WebView's local storage.
    private suspend fun getHistory(): JsonArray = clientWith(RemoteStorageUtils.HomeInterceptor())
        .get("$baseUrl/", cubariHeaders)
        .parseAs<JsonArray>()

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList(getHistory(), SortType.PINNED)

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList(getHistory(), SortType.UNPINNED)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        // legacy cubari:source/slug format
        if (query.startsWith("cubari:")) {
            val (source, slug) = query.substringAfter("cubari:").split("/", limit = 2)
            return MangasPage(listOf(getTaggedManga(source, slug)), false)
        }

        val filtered = getHistory().filter { it.jsonObject["title"].toString().contains(query.trim(), true) }
        val mangasPage = parseMangaList(JsonArray(filtered), SortType.ALL)
        require(mangasPage.mangas.isNotEmpty()) { SEARCH_FALLBACK_MSG }
        return mangasPage
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val (source, slug) = deepLinkHandler(url) ?: return null
        return getTaggedManga(source, slug)
    }

    // Only tag for recently read on search
    private suspend fun getTaggedManga(source: String, slug: String): SManga {
        val result = clientWith(RemoteStorageUtils.TagInterceptor())
            .get("$baseUrl/read/api/$source/series/$slug/", cubariHeaders)
            .parseAs<JsonObject>()
        val manga = SManga.create().apply {
            url = "/read/$source/$slug"
        }
        return parseManga(result, manga)
    }

    private fun deepLinkHandler(url: HttpUrl): Pair<String, String>? {
        val host = url.host
        val pathSegments = url.pathSegments

        return if (
            host.endsWith("imgur.com") &&
            pathSegments.size >= 2 &&
            pathSegments[0] in listOf("a", "gallery")
        ) {
            "imgur" to pathSegments[1]
        } else if (
            host.endsWith("reddit.com") &&
            pathSegments.size >= 2 &&
            pathSegments[0] == "gallery"
        ) {
            "reddit" to pathSegments[1]
        } else if (
            host == "imgchest.com" &&
            pathSegments.size >= 2 &&
            pathSegments[0] == "p"
        ) {
            "imgchest" to pathSegments[1]
        } else if (
            host.endsWith("catbox.moe") &&
            pathSegments.size >= 2 &&
            pathSegments[0] == "c"
        ) {
            "catbox" to pathSegments[1]
        } else if (
            host.endsWith("cubari.moe") &&
            pathSegments.size >= 3
        ) {
            pathSegments[1] to pathSegments[2]
        } else if (
            host.endsWith(".githubusercontent.com")
        ) {
            val src = host.substringBefore(".")
            val path = url.encodedPath

            "gist" to Base64.encodeToString("$src$path".toByteArray(), Base64.NO_PADDING)
        } else {
            null
        }
    }

    private suspend fun getSeries(url: String): JsonObject {
        val urlComponents = url.split("/")
        val source = urlComponents[2]
        val slug = urlComponents[3]

        return client.get("$baseUrl/read/api/$source/series/$slug/", cubariHeaders).parseAs<JsonObject>()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val result = getSeries(manga.url)
        return SMangaUpdate(parseManga(result, manga), parseChapterList(result, manga))
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (chapter.url.contains("/chapter/")) {
            val pages = client.get("$baseUrl${chapter.url}", cubariHeaders).parseAs<JsonArray>()
            return parsePages(pages)
        }

        return seriesJsonPageListParse(getSeries(chapter.url), chapter)
    }

    private fun parsePages(pages: JsonArray): List<Page> = pages.mapIndexed { i, jsonEl ->
        val page = if (jsonEl is JsonObject) {
            jsonEl.jsonObject["src"]!!.jsonPrimitive.content
        } else {
            jsonEl.jsonPrimitive.content
        }

        Page(i, "", page)
    }

    private fun seriesJsonPageListParse(jsonObj: JsonObject, chapter: SChapter): List<Page> {
        val groups = jsonObj["groups"]!!.jsonObject
        val groupMap = groups.entries.associateBy({ it.value.jsonPrimitive.content.ifEmpty { "default" } }, { it.key })
        val chapterScanlator = chapter.scanlator ?: "default" // workaround for "" as group causing NullPointerException (#13772)

        // prevent NullPointerException when chapters.key is 084 and chapter.chapter_number is 84
        val chapters = jsonObj["chapters"]!!.jsonObject.mapKeys {
            it.key.replace(Regex("^0+(?!$)"), "")
        }

        val pages = if (chapters[chapter.chapter_number.toString()] != null) {
            chapters[chapter.chapter_number.toString()]!!
                .jsonObject["groups"]!!
                .jsonObject[groupMap[chapterScanlator]]!!
                .jsonArray
        } else {
            chapters[chapter.chapter_number.toInt().toString()]!!
                .jsonObject["groups"]!!
                .jsonObject[groupMap[chapterScanlator]]!!
                .jsonArray
        }

        return parsePages(pages)
    }

    // ------------- Helpers and whatnot ---------------

    private val volumeNotSpecifiedTerms = setOf("Uncategorized", "null", "")

    private fun parseChapterList(jsonObj: JsonObject, manga: SManga): List<SChapter> {
        val groups = jsonObj["groups"]!!.jsonObject
        val chapters = jsonObj["chapters"]!!.jsonObject

        val chapterList = chapters.entries.flatMap { chapterEntry ->
            val chapterNum = chapterEntry.key
            val chapterObj = chapterEntry.value.jsonObject
            val chapterGroups = chapterObj["groups"]!!.jsonObject
            val volume = chapterObj["volume"]!!.jsonPrimitive.content.let {
                if (volumeNotSpecifiedTerms.contains(it)) null else it
            }
            val title = chapterObj["title"]!!.jsonPrimitive.content

            chapterGroups.entries.map { groupEntry ->
                val groupNum = groupEntry.key
                val releaseDate = chapterObj["release_date"]?.jsonObject?.get(groupNum)

                SChapter.create().apply {
                    scanlator = groups[groupNum]!!.jsonPrimitive.content
                    chapter_number = chapterNum.toFloatOrNull() ?: -1f

                    date_upload = if (releaseDate != null) {
                        releaseDate.jsonPrimitive.double.toLong() * 1000
                    } else {
                        0L
                    }

                    name = buildString {
                        if (!volume.isNullOrBlank()) append("Vol.$volume ")
                        append("Ch.$chapterNum")
                        if (title.isNotBlank()) append(" - $title")
                    }

                    url = if (chapterGroups[groupNum] is JsonArray) {
                        "${manga.url}/$chapterNum/$groupNum"
                    } else {
                        chapterGroups[groupNum]!!.jsonPrimitive.content
                    }
                }
            }
        }

        return chapterList.sortedByDescending { it.chapter_number }
    }

    private fun parseMangaList(payload: JsonArray, sortType: SortType): MangasPage {
        val mangaList = payload.mapNotNull { jsonEl ->
            val jsonObj = jsonEl.jsonObject
            val pinned = jsonObj["pinned"]!!.jsonPrimitive.boolean

            if (sortType == SortType.PINNED && pinned) {
                parseManga(jsonObj)
            } else if (sortType == SortType.UNPINNED && !pinned) {
                parseManga(jsonObj)
            } else if (sortType == SortType.ALL) {
                parseManga(jsonObj)
            } else {
                null
            }
        }

        return MangasPage(mangaList, false)
    }

    private fun parseManga(jsonObj: JsonObject, mangaReference: SManga? = null): SManga = SManga.create().apply {
        title = jsonObj["title"]!!.jsonPrimitive.content
        artist = jsonObj["artist"]?.jsonPrimitive?.content ?: ARTIST_FALLBACK
        author = jsonObj["author"]?.jsonPrimitive?.content ?: AUTHOR_FALLBACK

        val descriptionFull = jsonObj["description"]?.jsonPrimitive?.content
        description = descriptionFull?.substringBefore("Tags: ") ?: DESCRIPTION_FALLBACK
        genre = descriptionFull?.let {
            if (it.contains("Tags: ")) {
                it.substringAfter("Tags: ")
            } else {
                ""
            }
        } ?: ""

        url = mangaReference?.url ?: jsonObj["url"]!!.jsonPrimitive.content
        thumbnail_url = jsonObj["coverUrl"]?.jsonPrimitive?.content
            ?: jsonObj["cover"]?.jsonPrimitive?.content ?: ""
    }

    companion object {
        const val AUTHOR_FALLBACK = "Unknown"
        const val ARTIST_FALLBACK = "Unknown"
        const val DESCRIPTION_FALLBACK = "No description."
        const val SEARCH_FALLBACK_MSG = "Please enter a valid Cubari URL"

        enum class SortType {
            PINNED,
            UNPINNED,
            ALL,
        }
    }
}
