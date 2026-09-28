package eu.kanade.tachiyomi.extension.all.pixiv

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

@Source
abstract class Pixiv :
    KeiSource(),
    ConfigurableSource {
    private val preferences: SharedPreferences by getPreferencesLazy()

    private open inner class HttpCall(href: String?) {
        val url: HttpUrl.Builder = baseUrl.toHttpUrl()
            .run { href?.let { newBuilder(it)!! } ?: newBuilder() }

        val request: Request.Builder = Request.Builder()
            .headers(headers)

        fun execute(): Response = client.newCall(request.url(url.build()).build()).execute()
    }

    class PixivApiException(message: String? = null) : Exception(message, null)

    private inner class ApiCall(href: String?) : HttpCall(href) {
        init {
            url.addEncodedQueryParameter("lang", lang)
            request.addHeader("Accept", "application/json")
        }

        /**
         * Sends the previously constructed API call to the Pixiv API.
         * If the server reports an error, A [PixivApiException] will be
         * returned as a [Result.failure].
         */
        inline fun <reified T> executeApi(): Result<T> {
            val resp = execute().parseAs<PixivApiResponse>()
            if (resp.error) {
                return Result.failure(PixivApiException(resp.message))
            }
            return Result.success(resp.body!!.parseAs<T>())
        }
    }

    private var popularMangaNextPage = 1
    private lateinit var popularMangaIterator: Iterator<SManga>

    // Pages are cut from one lazily-fetched iterator, so the app must request them in order.
    // The iterator runs blocking calls because sequence builders cannot suspend.
    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page == 1) {
            popularMangaIterator = sequence {
                val rankingCall = ApiCall("/touch/ajax/ranking/illust?mode=daily&type=manga")

                for (p in countUp(start = 1)) {
                    rankingCall.url.setEncodedQueryParameter("page", p.toString())

                    val entries = rankingCall.executeApi<PixivRankings>().getOrThrow().ranking!!
                    if (entries.isEmpty()) break

                    val detailsCall = ApiCall("/touch/ajax/illust/details/many")
                    entries.forEach { detailsCall.url.addEncodedQueryParameter("illust_ids[]", it.illustId!!) }

                    detailsCall.executeApi<PixivIllustsDetails>().getOrThrow().illust_details!!.forEach { yield(it) }
                }
            }
                .toSManga()
                .iterator()

            popularMangaNextPage = 2
        } else {
            require(page == popularMangaNextPage++)
        }

        val mangas = popularMangaIterator.truncateToList(50)
        return MangasPage(mangas, hasNextPage = mangas.isNotEmpty())
    }

    private var searchNextPage = 1
    private var searchHash: Int? = null
    private lateinit var searchIterator: Iterator<PixivIllust>
    private lateinit var searchPredicates: List<(PixivIllust) -> Boolean>

    private var userSearchNextPage = 1
    private var userSearchHash: Int? = null
    private lateinit var userSearchIterator: Iterator<SManga>

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = PixivTarget.fromUri(url)?.let(::getTargetManga)

    private fun getTargetManga(target: PixivTarget): SManga? = when (target) {
        is PixivTarget.Illustration -> getIllustCached(target.illustId)?.toSManga()

        is PixivTarget.Series -> {
            // TODO: caching!
            val series = ApiCall("/touch/ajax/illust/series/${target.seriesId}")
                .executeApi<PixivSeriesDetails>().getOrNull()?.series
            series?.toSManga()
        }

        is PixivTarget.User -> {
            val user = getUserCached(target.userId)
            SManga.create().apply {
                url = "/users/${target.userId}"
                title = user?.name ?: "User ${target.userId}"
                thumbnail_url = user?.imageBig
            }
        }
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        // aid:/sid:/user: queries select one specific object
        PixivTarget.fromSearchQuery(query)?.let { target ->
            return MangasPage(listOfNotNull(getTargetManga(target)), hasNextPage = false)
        }

        val filters = filters.list as PixivFilters

        if (filters.users.isNotBlank()) {
            val hash = filters.users.hashCode()
            if (hash != userSearchHash || page == 1) {
                userSearchHash = hash
                userSearchIterator = makeUserSearchSequence(nick = filters.users).iterator()
                userSearchNextPage = 2
            } else {
                require(page == userSearchNextPage++)
            }

            val mangas = userSearchIterator.truncateToList(TARGET_RESULTS)
            return MangasPage(mangas, hasNextPage = mangas.isNotEmpty())
        }

        val hash = Pair(query, filters.toList()).hashCode()

        if (hash != searchHash || page == 1) {
            searchHash = hash

            lateinit var searchSequence: Sequence<PixivIllust>
            // clear predicates
            searchPredicates = emptyList()

            if (query.isNotBlank()) {
                searchSequence = makeIllustSearchSequence(
                    word = query,
                    order = filters.order,
                    mode = filters.rating,
                    sMode = "s_tc",
                    type = filters.type,
                    dateBefore = filters.dateBefore.ifBlank { null },
                    dateAfter = filters.dateAfter.ifBlank { null },
                )

                searchPredicates = buildList {
                    filters.makeTagsPredicate()?.let(::add)
                    filters.makeUsersPredicate()?.let(::add)
                }
            } else {
                searchSequence = makeIllustSearchSequence(
                    word = filters.tags.ifBlank { "漫画" },
                    order = filters.order,
                    mode = filters.rating,
                    sMode = filters.searchMode,
                    type = filters.type,
                    dateBefore = filters.dateBefore.ifBlank { null },
                    dateAfter = filters.dateAfter.ifBlank { null },
                )
            }

            searchIterator = searchSequence.iterator()
            searchNextPage = 2
        } else {
            require(page == searchNextPage++)
        }

        val filteredIllusts = if (searchPredicates.isEmpty()) {
            searchIterator.truncateToList(TARGET_RESULTS)
        } else {
            // if we have a filter let's be a little smarter about how to get enough results
            fetchWithAdaptiveWindow(searchIterator, searchPredicates)
        }

        val mangas = filteredIllusts.toSManga()
        return MangasPage(mangas, hasNextPage = mangas.isNotEmpty())
    }

    // fetch with variable window size - if filter is strong and we're not getting a lot of
    // results, cast a bigger net.
    //
    // this filters post-truncate to avoid the case where a strong filter will cause the search
    // to spin forever and futilely fetch page after page trying to get enough results to return
    private fun fetchWithAdaptiveWindow(
        iterator: Iterator<PixivIllust>,
        predicates: List<(PixivIllust) -> Boolean>,
    ): List<PixivIllust> {
        val sampleIllusts = iterator.truncateToList(RESULTS_PER_PAGE)
        val sampleFiltered = sampleIllusts.filter { illust -> predicates.all { p -> p(illust) } }

        val hitRate = if (sampleIllusts.isNotEmpty()) {
            sampleFiltered.size.toDouble() / sampleIllusts.size
        } else {
            0.0
        }
        val estimatedWindow = if (hitRate > 0) {
            (TARGET_RESULTS / hitRate).toInt().coerceIn(RESULTS_PER_PAGE, MAX_WINDOW_SIZE)
        } else {
            MAX_WINDOW_SIZE
        }

        // get estimated rest of unfiltered items needed to hit target results
        val remainingNeeded = (estimatedWindow - RESULTS_PER_PAGE).coerceAtLeast(0)
        val additionalIllusts = if (remainingNeeded > 0) {
            iterator.truncateToList(remainingNeeded)
        } else {
            emptyList()
        }

        val allIllusts = sampleIllusts + additionalIllusts
        return allIllusts.filter { illust -> predicates.all { p -> p(illust) } }
    }

    private fun makeIllustSearchSequence(
        word: String,
        sMode: String,
        order: String?,
        mode: String?,
        type: String?,
        dateBefore: String?,
        dateAfter: String?,
    ) = sequence<PixivIllust> {
        val call = ApiCall("/touch/ajax/search/illusts")

        call.url.addQueryParameter("word", word)
        call.url.addEncodedQueryParameter("s_mode", sMode)
        type?.let { call.url.addEncodedQueryParameter("type", it) }
        order?.let { call.url.addEncodedQueryParameter("order", it) }
        mode?.let { call.url.addEncodedQueryParameter("mode", it) }
        dateBefore?.let { call.url.addEncodedQueryParameter("ecd", it) }
        dateAfter?.let { call.url.addEncodedQueryParameter("scd", it) }

        for (p in countUp(start = 1)) {
            call.url.setEncodedQueryParameter("p", p.toString())

            val illusts = call.executeApi<PixivResults>().getOrThrow().illusts!!
            if (illusts.isEmpty()) break

            for (illust in illusts) {
                if (illust.is_ad_container == 1) continue
                if (illust.type == "2") continue

                yield(illust)
            }
        }
    }

    // search by username
    private fun makeUserSearchSequence(nick: String) = sequence<SManga> {
        val searchUsers = HttpCall("/search/users")
            .apply {
                url.addQueryParameter("s_mode", "s_usr")
                url.addQueryParameter("nick", nick)
                url.addQueryParameter("i", "1")
                url.addQueryParameter("comment", "")
                // have to use desktop User-Agent to get __NEXT_DATA__ (mobile version is SPA without embedded data)
                request.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            }

        for (p in countUp(start = 1)) {
            searchUsers.url.setEncodedQueryParameter("p", p.toString())

            val response = searchUsers.execute()
            val htmlBody = response.body.string()

            val doc = org.jsoup.Jsoup.parse(htmlBody)
            val nextDataScript = doc.select("script#__NEXT_DATA__").first()?.data() ?: break

            val nextData = nextDataScript.parseAs<PixivNextData>()
            val pageProps = nextData.props.pageProps
            val userIds = pageProps.userIds

            if (userIds.isEmpty()) break

            val users = pageProps.userData?.users
            for (userId in userIds) {
                val user = users?.get(userId.toString())
                yield(
                    SManga.create().apply {
                        url = "/users/$userId"
                        title = user?.name ?: "User $userId"
                        thumbnail_url = user?.imageBig
                    },
                )
            }
        }
    }

    // lookup directly by user id
    private fun makeUserIdIllustSearchSequence(id: String, type: String?) = sequence<PixivIllust> {
        val fetchUserIllusts = ApiCall("/touch/ajax/user/illusts")
            .apply {
                type?.let { url.setEncodedQueryParameter("type", it) }
                url.setEncodedQueryParameter("id", id)
            }

        for (p in countUp(start = 1)) {
            fetchUserIllusts.url.setEncodedQueryParameter("p", p.toString())

            val illusts = fetchUserIllusts.executeApi<PixivResults>().getOrThrow().illusts!!
            if (illusts.isEmpty()) break

            yieldAll(illusts)
        }
    }

    override fun getFilterList(data: JsonElement?) = FilterList(PixivFilters())

    private fun List<PixivIllust>.toSManga() = asSequence().toSManga().toList()
    private fun Sequence<PixivIllust>.toSManga() = sequence {
        val seriesIdsSeen = mutableSetOf<String>()

        forEach { illust ->
            val manga = illust.toSManga()
            if (seriesIdsSeen.add(manga.url)) {
                yield(manga)
            }
        }
    }

    private fun PixivSeries.toSearchResult() = PixivSearchResultSeries(
        id = id,
        title = title,
        userId = userId,
        coverImage = coverImage?.let { if (it.isString) it.content else null },
    )
    private fun PixivIllust.toSManga(): SManga {
        if (series == null) {
            val manga = SManga.create()
            manga.setUrlWithoutDomain("/artworks/${id!!}")
            manga.title = title ?: "(null)"
            manga.thumbnail_url = url
            return manga
        } else {
            val series = series.copy(userId = series.userId ?: author_details?.user_id)
            val manga = series.toSManga().apply {
                thumbnail_url = thumbnail_url ?: this@toSManga.url
            }
            return manga
        }
    }
    private fun PixivSeries.toSManga() = toSearchResult().toSManga()
    private fun PixivSearchResultSeries.toSManga(): SManga {
        val manga = SManga.create()
        manga.setUrlWithoutDomain("/user/${userId!!}/series/$id")
        manga.title = title ?: "(null)"
        manga.thumbnail_url = coverImage
        return manga
    }

    private var latestMangaNextPage = 1
    private lateinit var latestMangaIterator: Iterator<SManga>

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page == 1) {
            latestMangaIterator = sequence {
                val call = ApiCall("/touch/ajax/latest?type=manga")

                for (p in countUp(start = 1)) {
                    call.url.setEncodedQueryParameter("p", p.toString())

                    val illusts = call.executeApi<PixivResults>().getOrThrow().illusts!!
                    if (illusts.isEmpty()) break

                    for (illust in illusts) {
                        if (illust.is_ad_container == 1) continue
                        yield(illust)
                    }
                }
            }
                .toSManga()
                .iterator()

            latestMangaNextPage = 2
        } else {
            require(page == latestMangaNextPage++)
        }

        val mangas = latestMangaIterator.truncateToList(50).toList()
        return MangasPage(mangas, hasNextPage = mangas.isNotEmpty())
    }

    private val getIllustCached by lazy {
        lruCached<String, PixivIllust?>(25) { illustId ->
            val call = ApiCall("/touch/ajax/illust/details?illust_id=$illustId")
            return@lruCached call.executeApi<PixivIllustDetails>().getOrNull()?.illust_details
        }
    }

    private val getUserCached by lazy {
        lruCached<String, PixivUserInfo?>(25) { userId ->
            val call = ApiCall("/ajax/user/$userId?full=1")
            return@lruCached call.executeApi<PixivUserInfo>().getOrNull()
        }
    }

    private val getSeriesIllustsCached by lazy {
        lruCached<String, List<PixivIllust>?>(25) { seriesId ->
            val call = ApiCall("/touch/ajax/illust/series_content/$seriesId")
            var lastOrder = 0

            return@lruCached buildList {
                while (true) {
                    call.url.setEncodedQueryParameter("last_order", lastOrder.toString())

                    val illusts = call.executeApi<PixivSeriesContents>()
                        .getOrElse { return@lruCached null }.series_contents!!
                    if (illusts.isEmpty()) break

                    addAll(illusts)
                    lastOrder += illusts.size
                }
            }
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val target = PixivTarget.fromUri(baseUrl + manga.url) ?: return SMangaUpdate(manga, chapters)

        // Series details and chapters share getSeriesIllustsCached, so fetching them one
        // after the other costs the series_contents walk only once.
        if (fetchDetails) updateDetails(manga, target)
        return SMangaUpdate(manga, if (fetchChapters) getChapterList(target) else chapters)
    }

    private fun updateDetails(manga: SManga, target: PixivTarget) {
        when (target) {
            is PixivTarget.User -> {
                val response = getUserCached(target.userId)

                response?.name?.let {
                    manga.title = it
                    manga.author = it
                    manga.artist = it
                }
                response?.comment?.let { manga.description = it }
                response?.imageBig?.let { manga.thumbnail_url = it }
            }
            is PixivTarget.Series -> {
                val series = ApiCall("/touch/ajax/illust/series/${target.seriesId}")
                    .executeApi<PixivSeriesDetails>().getOrThrow().series!!

                val illusts = getSeriesIllustsCached(target.seriesId)!!

                series.title?.let { manga.title = it }
                series.caption?.let { manga.description = it }

                illusts.firstOrNull()?.author_details?.user_name?.let {
                    manga.artist = it
                    manga.author = it
                }

                val tags = illusts.flatMap { it.tags ?: emptyList() }.toSet()
                if (tags.isNotEmpty()) manga.genre = tags.joinToString()

                val coverImage = series.coverImage?.let { if (it.isString) it.content else null }
                (coverImage ?: illusts.firstOrNull()?.url)?.let { manga.thumbnail_url = it }
            }
            is PixivTarget.Illustration -> {
                val illust = getIllustCached(target.illustId)!!

                illust.title?.let { manga.title = it }

                illust.author_details?.user_name?.let {
                    manga.artist = it
                    manga.author = it
                }

                illust.comment?.let { manga.description = it }
                illust.tags?.let { manga.genre = it.joinToString() }
                illust.url?.let { manga.thumbnail_url = it }
            }
        }
    }

    private fun getChapterList(target: PixivTarget): List<SChapter> {
        val illusts = when (target) {
            is PixivTarget.User -> makeUserIdIllustSearchSequence(target.userId, type = null).toList()
            is PixivTarget.Series -> getSeriesIllustsCached(target.seriesId)!!
            is PixivTarget.Illustration -> listOf(getIllustCached(target.illustId)!!)
        }

        return illusts.mapIndexed { i, illust ->
            SChapter.create().apply {
                setUrlWithoutDomain("/artworks/${illust.id!!}")
                name = illust.title ?: "(null)"
                date_upload = (illust.upload_timestamp ?: 0) * 1000
                chapter_number = (illusts.size - i).toFloat()
            }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val illustId = chapter.url.substringAfterLast('/')

        return ApiCall("/ajax/illust/$illustId/pages")
            .executeApi<List<PixivIllustPage>>().getOrThrow()
            .mapIndexed { i, page -> Page(i, imageUrl = getImageUrl(page.urls!!)) }
    }

    private fun getImageUrl(urls: PixivIllustPageUrls): String {
        val quality = preferences.getString(PREF_IMAGE_QUALITY, "original")!!

        val sizeOrder = listOf("thumb_mini", "small", "regular", "original")
        val startIndex = sizeOrder.indexOf(quality).takeIf { it >= 0 } ?: sizeOrder.lastIndex

        return sizeOrder.drop(startIndex).firstNotNullOf { size ->
            when (size) {
                "thumb_mini" -> urls.thumb_mini
                "small" -> urls.small
                "regular" -> urls.regular
                "original" -> urls.original
                else -> null
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_IMAGE_QUALITY
            title = "Image quality"
            entries = arrayOf("Thumb Mini", "Small", "Regular", "Original")
            entryValues = arrayOf("thumb_mini", "small", "regular", "original")
            setDefaultValue("original")
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_IMAGE_QUALITY = "pref_image_quality"

        // constants for fetchWithAdaptiveWindow
        private const val TARGET_RESULTS = 50
        private const val RESULTS_PER_PAGE = 36
        private const val MAX_WINDOW_SIZE = 1000 // roughly 25 pages
    }
}
