package eu.kanade.tachiyomi.extension.zh.yidan

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.firstInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Yidan : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = addInterceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val requestUrl = request.url.toString()
        if (requestUrl.contains("images/mhtp/yidan")) {
            // remove first two bytes for image response
            val ext = requestUrl.substringAfterLast(".", "png")
            response.newBuilder().body(
                response.body.source().apply { skip(2) }
                    .asResponseBody("image/$ext".toMediaType()),
            ).build()
        } else {
            response
        }
    }

    override suspend fun getPopularManga(page: Int) = getComicByRow("29", page)

    override suspend fun getLatestUpdates(page: Int) = getComicByRow("34", page)

    private suspend fun getComicByRow(column: String, page: Int): MangasPage {
        val records = client.post(
            "$baseUrl/api/getByComicByRow",
            ComicFetchRequest(column, page, PAGE_SIZE).toJsonRequestBody(),
        ).parseAs<CommonResponse<RecordResult>>().result.records
        return createMangasPage(records)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val id = url.queryParameter("id") ?: return null
        return getComicInfo(id).comic.toSManga()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotEmpty()) {
            val records = client.post(
                "$baseUrl/api/searchNovel",
                KeywordSearchRequest(query).toJsonRequestBody(),
            ).parseAs<CommonResponse<List<Record>>>().result
            return createMangasPage(records, paginated = false)
        }
        val records = client.post(
            "$baseUrl/api/getByComicCategoryId",
            FilterRequest(
                page = page,
                limit = PAGE_SIZE,
                categoryId = filters.firstInstance<CategoryFilter>().selected,
                orderType = filters.firstInstance<SortFilter>().selected,
                overType = filters.firstInstance<StatusFilter>().selected,
            ).toJsonRequestBody(),
        ).parseAs<CommonResponse<FilterResult>>().result.list
        return createMangasPage(records)
    }

    private fun createMangasPage(records: List<Record>, paginated: Boolean = true): MangasPage = MangasPage(
        records.map {
            SManga.create().apply {
                url = "${it.id}"
                title = it.novelTitle
                thumbnail_url = it.imgUrl
            }
        },
        paginated && records.size >= PAGE_SIZE,
    )

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/pages/comic/info".toHttpUrl().newBuilder()
        .addQueryParameter("id", manga.url)
        .toString()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val result = getComicInfo(manga.url)
        val chapterList = result.chapterList.mapIndexed { index, chapter ->
            SChapter.create().apply {
                url = "${chapter.id}"
                name = chapter.chapterName
                date_upload = DateTimeFormatter.ISO_LOCAL_DATE.tryParseDate(chapter.createTime)
                // used to get the real chapter url
                chapter_number = index.toFloat()
            }
        }.reversed()
        return SMangaUpdate(result.comic.toSManga(), chapterList)
    }

    private suspend fun getComicInfo(comicId: String) = client.post(
        "$baseUrl/api/getComicInfo",
        ComicDetailRequest(comicId, getUserId()).toJsonRequestBody(),
    ).parseAs<CommonResponse<ComicInfoResult>>().result

    private fun Comic.toSManga() = SManga.create().apply {
        url = "$id"
        title = novelTitle
        thumbnail_url = bigImgUrl
        genre = tags
        author = this@toSManga.author
        description = introduction
        status = when (overType) {
            1 -> SManga.ONGOING
            2 -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/pages/comic/content".toHttpUrl().newBuilder()
        .addQueryParameter("f", "1")
        .addQueryParameter("s", chapter.chapter_number.toInt().toString())
        .toString()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val contentList = client.post(
            "$baseUrl/api/getComicChapter",
            ChapterContentRequest(chapter.url, getUserId()).toJsonRequestBody(),
        ).parseAs<CommonResponse<ChapterContentResult>>().result.content
        return contentList.mapIndexed { index, content ->
            Page(index, imageUrl = content.url)
        }
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        StatusFilter(),
        CategoryFilter(),
    )

    private var userId: String? = null

    // The site registers an anonymous user on first load and keeps its id in localStorage
    private suspend fun getUserId(): String {
        userId?.let { return it }
        return try {
            runWebView<String>(timeout = 20.seconds) {
                blockImages = true
                poll {
                    evaluateJs("localStorage.getItem('uc')") { value ->
                        value.parseAs<String?>()?.takeIf { it.isNotEmpty() }?.let(::resolve)
                    }
                }
                loadUrl(baseUrl)
            }
        } catch (_: WebViewTimeoutException) {
            throw Exception("无法自动获取UserId，请先尝试通过内置WebView进入网站")
        }.also { userId = it }
    }

    companion object {
        private const val PAGE_SIZE = 16
    }
}
