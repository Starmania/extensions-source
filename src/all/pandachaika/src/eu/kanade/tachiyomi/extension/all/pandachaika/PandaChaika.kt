package eu.kanade.tachiyomi.extension.all.pandachaika

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
import keiyoushi.utils.toJsonString
import keiyoushi.zip.coroutines.zipDirectory
import keiyoushi.zip.dataRange
import keiyoushi.zip.range
import keiyoushi.zip.readEntry
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import java.lang.String.CASE_INSENSITIVE_ORDER

@Source
abstract class PandaChaika : KeiSource() {

    private val searchLang: String
        get() = when (lang) {
            "en" -> "english"
            "zh" -> "chinese"
            "ko" -> "korean"
            "es" -> "spanish"
            "ru" -> "russian"
            "pt" -> "portuguese"
            "fr" -> "french"
            "th" -> "thai"
            "vi" -> "vietnamese"
            "ja" -> "japanese"
            "id" -> "indonesian"
            "ar" -> "arabic"
            "uk" -> "ukrainian"
            "tr" -> "turkish"
            "cs" -> "czech"
            "tl" -> "tagalog"
            "fi" -> "finnish"
            "jv" -> "javanese"
            "el" -> "greek"
            else -> ""
        }

    private val baseSearchUrl = "$baseUrl/search"

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(::intercept)
    }

    private val fakkuRegex = Regex("""(?:https?://)?(?:www\.)?fakku\.net/hentai/""")
    private val ehentaiRegex = Regex("""(?:https?://)?e-hentai\.org/g/""")

    // Popular
    override suspend fun getPopularManga(page: Int): MangasPage = searchMangaParse(client.get("$baseSearchUrl/?tags=$searchLang&sort=rating&apply=&json=&page=$page"))

    // Latest
    override suspend fun getLatestUpdates(page: Int): MangasPage = searchMangaParse(client.get("$baseSearchUrl/?tags=$searchLang&sort=public_date&apply=&json=&page=$page"))

    private fun parsePageRange(query: String, minPages: Int = 1, maxPages: Int = 9999): Pair<Int, Int> {
        val num = query.filter(Char::isDigit).toIntOrNull() ?: -1
        fun limitedNum(number: Int = num): Int = number.coerceIn(minPages, maxPages)

        if (num < 0) return minPages to maxPages
        return when (query.firstOrNull()) {
            '<' -> 1 to if (query[1] == '=') limitedNum() else limitedNum(num + 1)

            '>' -> limitedNum(if (query[1] == '=') num else num + 1) to maxPages

            '=' -> when (query[1]) {
                '>' -> limitedNum() to maxPages
                '<' -> 1 to limitedNum(maxPages)
                else -> limitedNum() to limitedNum()
            }

            else -> limitedNum() to limitedNum()
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "archive") return null
        val id = url.pathSegments.getOrNull(1)?.toIntOrNull() ?: return null
        return getMangaById(id)
    }

    private suspend fun getMangaById(id: Int): SManga {
        val title = client.get("$baseUrl/api?archive=$id").parseAs<Archive>().title
        return quickSearch(title).find { it.id == id }?.toSManga() ?: throw Exception("Invalid ID")
    }

    private suspend fun quickSearch(query: String): List<LongArchive> {
        val url = baseSearchUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("qsearch", query)
            addQueryParameter("json", "")
        }.build()
        return client.get(url).parseAs<ArchiveResponse>().archives
    }

    private suspend fun quickSearchFirst(query: String): MangasPage {
        val archive = quickSearch(query).getOrNull(0)?.toSManga() ?: throw Exception("Not Found")
        return MangasPage(listOf(archive), false)
    }

    private fun searchMangaParse(response: Response): MangasPage {
        val library = response.parseAs<ArchiveResponse>()

        val mangas = library.archives.map(LongArchive::toSManga)

        val hasNextPage = library.hasNext

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        when {
            query.startsWith(PREFIX_ID_SEARCH) ->
                return MangasPage(listOf(getMangaById(query.removePrefix(PREFIX_ID_SEARCH).toInt())), false)

            query.startsWith(PREFIX_EHEN_ID_SEARCH) ->
                return quickSearchFirst("https://e-hentai.org/g/" + query.removePrefix(PREFIX_EHEN_ID_SEARCH).replace(ehentaiRegex, ""))

            query.startsWith(PREFIX_FAK_ID_SEARCH) ->
                return quickSearchFirst("https://www.fakku.net/hentai/" + query.removePrefix(PREFIX_FAK_ID_SEARCH).replace(fakkuRegex, ""))

            query.startsWith(PREFIX_SOURCE_SEARCH) ->
                return quickSearchFirst(query.removePrefix(PREFIX_SOURCE_SEARCH))
        }

        val url = baseSearchUrl.toHttpUrl().newBuilder().apply {
            val tags = mutableListOf<String>()
            var reason = ""
            var uploader = ""
            var pagesMin = 1
            var pagesMax = 9999

            tags.add(searchLang)

            filters.forEach {
                when (it) {
                    is SortFilter -> {
                        addQueryParameter("sort", it.getValue())
                        addQueryParameter("asc_desc", if (it.state!!.ascending) "asc" else "desc")
                    }

                    is SelectFilter -> {
                        addQueryParameter("category", it.vals[it.state].replace("All", ""))
                    }

                    is PageFilter -> {
                        if (it.state.isNotBlank()) {
                            val (min, max) = parsePageRange(it.state)
                            pagesMin = min
                            pagesMax = max
                        }
                    }

                    is TextFilter -> {
                        if (it.state.isNotEmpty()) {
                            when (it.type) {
                                "reason" -> reason = it.state

                                "uploader" -> uploader = it.state

                                else -> {
                                    it.state.split(",").filter(String::isNotBlank).map { tag ->
                                        val trimmed = tag.trim()
                                        tags.add(
                                            buildString {
                                                if (trimmed.startsWith('-')) append("-")
                                                append(it.type)
                                                if (it.type.isNotBlank()) append(":")
                                                append(trimmed.lowercase().removePrefix("-"))
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    else -> {}
                }
            }

            addQueryParameter("title", query)
            addQueryParameter("tags", tags.joinToString())
            addQueryParameter("filecount_from", pagesMin.toString())
            addQueryParameter("filecount_to", pagesMax.toString())
            addQueryParameter("reason", reason)
            addQueryParameter("uploader", uploader)
            addQueryParameter("page", page.toString())
            addQueryParameter("apply", "")
            addQueryParameter("json", "")
        }.build()

        return searchMangaParse(client.get(url))
    }

    override fun getFilterList(data: JsonElement?) = getFilters()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details come complete from the listing; the API only adds the download link and date.
        if (!fetchChapters) return SMangaUpdate(manga, chapters)

        val archive = client.get("$baseUrl/api?archive=${manga.url}").parseAs<Archive>()
        val chapter = SChapter.create().apply {
            name = "Chapter"
            url = archive.download.substringBefore("/download/")
            date_upload = archive.posted * 1000
        }
        return SMangaUpdate(manga, listOf(chapter))
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/archive/${manga.url}"

    // Pages
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = "$baseUrl${chapter.url}/download/"
        val dir = client.zipDirectory(url)
        return dir.entries.sortedWith(compareBy(CASE_INSENSITIVE_ORDER) { it.name }).mapIndexed { index, entry ->
            val data = ImageRequest(
                url,
                entry.name,
                entry.localHeaderOffset,
                entry.compressedSize,
                entry.method,
            ).toJsonString()
            Page(index, imageUrl = "https://127.0.0.1/#$data")
        }
    }

    private fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.toString().startsWith("https://127.0.0.1/#")) {
            return chain.proceed(request)
        }

        val data = request.url.fragment?.parseAs<ImageRequest>() ?: return chain.proceed(request)
        val range = dataRange(data.offset, data.compressedSize)
        val rangeRequest = request.newBuilder()
            .url(data.url)
            .range(range)
            .build()

        val response = chain.proceed(rangeRequest)
        if (!response.isSuccessful) return response
        val image = readEntry(response.body.source(), data.compressedSize, data.method).buffer()
        var type = data.name.substringAfterLast('.').lowercase()
        type = if (type == "jpg") "jpeg" else type

        return response.newBuilder()
            .removeHeader("Content-Range")
            .removeHeader("Content-Length")
            .code(200)
            .message("OK")
            .protocol(Protocol.HTTP_1_1)
            .body(image.asResponseBody("image/$type".toMediaType()))
            .build()
    }

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        const val PREFIX_FAK_ID_SEARCH = "fakku:"
        const val PREFIX_EHEN_ID_SEARCH = "ehentai:"
        const val PREFIX_SOURCE_SEARCH = "source:"
    }
}
