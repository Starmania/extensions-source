package eu.kanade.tachiyomi.extension.all.luscious

import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
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
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import kotlin.math.ceil

@Source
abstract class Luscious :
    KeiSource(),
    ConfigurableSource {

    val lusLang: String get() = toLusLang(lang)

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val apiBaseUrl: String get() = "$baseUrl/graphql/nobatch/"
    private val cdnHost: String = "ah-img.luscious.net"

    override fun OkHttpClient.Builder.configureClient() = addNetworkInterceptor(rewriteOctetStream)

    private val rewriteOctetStream: Interceptor = Interceptor { chain ->
        val originalResponse: Response = chain.proceed(chain.request())
        if (originalResponse.headers("Content-Type").contains("application/octet-stream") && originalResponse.request.url.toString()
                .contains(".webp")
        ) {
            val orgBody = originalResponse.body.source()
            val newBody = orgBody.asResponseBody("image/webp".toMediaType())
            originalResponse.newBuilder()
                .body(newBody)
                .build()
        } else {
            originalResponse
        }
    }

    // Common
    private fun buildAlbumListRequestInput(page: Int, filters: FilterList, query: String = ""): Variables {
        val sortByFilter = filters.findInstance<SortBySelectFilter>()!!
        val albumTypeFilter = filters.findInstance<AlbumTypeSelectFilter>()!!
        val selectionFilter = filters.findInstance<SelectionSelectFilter>()!!
        val interestsFilter = filters.findInstance<InterestGroupFilter>()!!
        val languagesFilter = filters.findInstance<LanguageGroupFilter>()!!
        val tagsFilter = filters.findInstance<TagTextFilters>()!!
        val creatorFilter = filters.findInstance<CreatorTextFilters>()!!
        val favoriteFilter = filters.findInstance<FavoriteTextFilters>()!!
        val genreFilter = filters.findInstance<GenreGroupFilter>()!!
        val contentTypeFilter = filters.findInstance<ContentTypeSelectFilter>()!!
        val albumSizeFilter = filters.findInstance<AlbumSizeSelectFilter>()!!
        val restrictGenresFilter = filters.findInstance<RestrictGenresSelectFilter>()!!
        return Variables(
            Input(
                display = sortByFilter.selected,
                page = page,
                itemsPerPage = 50,
                filters = mutableListOf<Filter>().apply {
                    if (contentTypeFilter.selected != FILTER_VALUE_IGNORE) {
                        add(contentTypeFilter.toJsonObject("content_id"))
                    }

                    if (albumTypeFilter.selected != FILTER_VALUE_IGNORE) {
                        add(albumTypeFilter.toJsonObject("album_type"))
                    }

                    if (selectionFilter.selected != FILTER_VALUE_IGNORE) {
                        add(selectionFilter.toJsonObject("selection"))
                    }

                    if (albumSizeFilter.selected != FILTER_VALUE_IGNORE) {
                        add(albumSizeFilter.toJsonObject("picture_count_rank"))
                    }

                    if (restrictGenresFilter.selected != FILTER_VALUE_IGNORE) {
                        add(restrictGenresFilter.toJsonObject("restrict_genres"))
                    }

                    with(interestsFilter) {
                        if (this.selected.isEmpty()) {
                            throw Exception("Please select an Interest")
                        }
                        add(this.toJsonObject("audience_ids"))
                    }

                    if (lusLang != FILTER_VALUE_IGNORE) {
                        add(
                            Filter(name = "language_ids", value = "+" + languagesFilter.selected.joinToString("+")),
                        )
                    }

                    if (tagsFilter.state.isNotEmpty()) {
                        val tags = "+${tagsFilter.state.lowercase()}".replace(" ", "_")
                            .replace("_,", "+").replace(",_", "+").replace(",", "+")
                            .replace("+-", "-").replace("-_", "-").trim()
                        add(
                            Filter(
                                name = "tagged",
                                value = tags,
                            ),
                        )
                    }

                    if (creatorFilter.state.isNotEmpty()) {
                        add(
                            Filter(
                                name = "created_by_id",
                                value = creatorFilter.state,
                            ),
                        )
                    }

                    if (favoriteFilter.state.isNotEmpty()) {
                        add(
                            Filter(
                                name = "favorite_by_user_id",
                                value = favoriteFilter.state,
                            ),
                        )
                    }

                    if (genreFilter.anyNotIgnored()) {
                        add(genreFilter.toJsonObject("genre_ids"))
                    }

                    if (query != "") {
                        add(
                            Filter(
                                name = "search_query",
                                value = query,
                            ),
                        )
                    }
                },
            ),
        )
    }

    private fun gqlUrl(operationName: String, query: String, variables: String): HttpUrl = apiBaseUrl.toHttpUrl().newBuilder()
        .addQueryParameter("operationName", operationName)
        .addQueryParameter("query", query)
        .addQueryParameter("variables", variables)
        .build()

    private suspend fun getAlbumList(page: Int, filters: FilterList, query: String = ""): MangasPage {
        val input = buildAlbumListRequestInput(page, filters, query)
        val data = client.get(gqlUrl("AlbumList", ALBUM_LIST_REQUEST_GQL, input.toJsonString()))
            .parseAs<AlbumListResponse>()
        with(data.data.album.list) {
            return MangasPage(
                this.items.map {
                    SManga.create().apply {
                        url = it.url
                        title = it.title
                        thumbnail_url = it.cover.url
                    }
                },
                this.info.hasNextPage,
            )
        }
    }

    private suspend fun getAlbum(id: String): FullAlbum = client.get(gqlUrl("AlbumGet", albumInfoQuery, SingleIdVariable(id = id).toJsonString()))
        .parseAs<AlbumGetResponse>().data.album.get

    private fun albumId(url: String) = url.substringBefore("?").substringAfterLast("_").removeSuffix("/")

    // Popular

    override suspend fun getPopularManga(page: Int): MangasPage = getAlbumList(page, getSortFilters(POPULAR_DEFAULT_SORT_STATE, lusLang))

    // Latest

    override suspend fun getLatestUpdates(page: Int): MangasPage = getAlbumList(page, getSortFilters(LATEST_DEFAULT_SORT_STATE, lusLang))

    // Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.startsWith("ID:")) {
            return MangasPage(listOf(getAlbum(query.substringAfterLast("ID:")).toSManga()), false)
        }
        return getAlbumList(
            page,
            filters.ifEmpty { getSortFilters(SEARCH_DEFAULT_SORT_STATE, lusLang) },
            query,
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "albums") return null
        return getAlbum(url.pathSegments[1].split("_").last()).toSManga()
    }

    override fun getFilterList(data: JsonElement?): FilterList = getSortFilters(POPULAR_DEFAULT_SORT_STATE, lusLang)

    // Details & chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // chapter list needs the album's picture count, so this request is made either way
        val album = getAlbum(albumId(manga.url))
        return SMangaUpdate(
            if (fetchDetails) album.toSManga() else manga,
            if (fetchChapters) getChapterList(manga, album) else chapters,
        )
    }

    private fun FullAlbum.toSManga(): SManga {
        val manga = SManga.create()
        manga.url = url
        manga.title = title
        manga.thumbnail_url = cover.url
        manga.status = 0
        manga.description = "$description\n\nPictures: $numberOfPictures\nAnimated Pictures: $numberOfAnimatedPictures"
        val genreList = mutableListOf(language?.title)
        genreList += labels
        genreList += genres.map { it.title }
        genreList += audiences.map { it.title }
        genreList += tags.map { it.text }
        val artist = tags.find { it.text.contains("Artist:") }
        if (artist != null) {
            manga.artist = artist.text.substringAfter(":").trim()
            manga.author = manga.artist
        }
        genreList += content.title
        manga.genre = genreList.joinToString(", ")

        return manga
    }

    private suspend fun getChapterList(manga: SManga, album: FullAlbum): List<SChapter> {
        val id = albumId(manga.url)
        val totalPictures = album.numberOfPictures

        val chapters = mutableListOf<SChapter>()
        if (getMergeChapterPref()) {
            val chunkCount = ceil(totalPictures / 1000.0).toInt().coerceAtLeast(1)

            for (i in 1..chunkCount) {
                val chapter = SChapter.create()
                chapter.url = "${manga.url}?chunk=$i"
                chapter.name = if (chunkCount == 1) "Merged Chapter" else "Merged Chapter (Part $i)"
                chapter.chapter_number = i.toFloat()
                chapter.date_upload = (album.created?.toLong() ?: 0L) * 1000L
                chapters.add(chapter)
            }
        } else {
            var page = 1
            var hasMore = true

            while (hasMore) {
                val data = getAlbumPictures(id, page)
                val pictureItems = parsePictures(data)

                if (pictureItems.isEmpty()) {
                    hasMore = false
                } else {
                    pictureItems.forEach {
                        val chapter = SChapter.create().apply {
                            chapter_number = it.index.toFloat()
                            name = "${it.index} - ${it.title}"
                            date_upload = (it.created ?: 0L) * 1000L
                        }
                        chapter.setUrlWithoutDomain(it.url)
                        chapters.add(chapter)
                    }

                    // API natively caps `total_items` tracking to 1000 so we override that by
                    // directly tracking standard math iteration against the true `numberOfPictures`
                    if (page * 50 >= totalPictures || data.data.picture.list.items.isEmpty()) {
                        hasMore = false
                    } else {
                        page++
                    }
                }
            }
        }
        return chapters.reversed()
    }

    private fun getPictureUrl(picture: Picture) = when {
        getResolutionPref() != "-1" -> {
            picture.thumbnails[getResolutionPref()?.toInt()!!].url
        }

        picture.urlToVideo != null -> {
            picture.urlToVideo.replace(".mp4", ".gif")
        }

        picture.urlToOriginal != null -> {
            picture.urlToOriginal
        }

        else -> {
            picture.thumbnails.maxByOrNull { thumbnail ->
                thumbnail.height * thumbnail.width
            }!!.url
        }
    }

    private fun parsePictures(data: AlbumListOwnPicturesResponse): List<PictureItem> {
        val items = mutableListOf<PictureItem>()

        data.data.picture.list.items.forEach {
            val index = it.position
            val url = getPictureUrl(it)

            items.add(PictureItem(index, if (url.startsWith("//")) "https:$url" else url, it.title, it.created.toLong()))
        }

        return items
    }

    // Pages

    private suspend fun getAlbumPictures(id: String, page: Int): AlbumListOwnPicturesResponse {
        val input = Variables(
            input = Input(
                filters = listOf(
                    Filter(name = "album_id", value = id),
                ),
                display = getSortPref(),
                page = page,
                itemsPerPage = 50,
            ),
        )
        return client.get(gqlUrl("AlbumListOwnPictures", ALBUM_PICTURES_REQUEST_GQL, input.toJsonString()))
            .parseAs<AlbumListOwnPicturesResponse>()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (!chapter.url.startsWith("/albums/")) {
            return listOf(Page(0, imageUrl = "https://$cdnHost${chapter.url}"))
        }

        val chunk = chapter.url.substringAfter("?chunk=", "1").substringBefore("#").toIntOrNull() ?: 1
        val id = albumId(chapter.url)

        val pages = mutableListOf<Page>()
        val startPage = (chunk - 1) * 20 + 1
        val endPage = chunk * 20

        for (page in startPage..endPage) {
            val pictureItems = parsePictures(getAlbumPictures(id, page))

            if (pictureItems.isEmpty()) break

            pictureItems.forEach {
                pages.add(Page(pages.size, imageUrl = it.url.toHttpUrl().newBuilder().host(cdnHost).build().toString()))
            }

            if (pictureItems.size < 50) break
        }
        return pages
    }

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.url.startsWith("/albums/")) {
        "$baseUrl${chapter.url.substringBefore("?")}"
    } else {
        "https://$cdnHost${chapter.url}"
    }

    // Related

    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val variables = SingleIdVariable(id = albumId(manga.url)).toJsonString()
        val data = client.get(gqlUrl("AlbumListRelated", albumListRelatedQuery, variables))
            .parseAs<AlbumRelatedResponse>()
        with(data.data.album.listRelated) {
            return listOfNotNull(
                moreLikeThis,
                itemsLikedLikeThis,
                itemsCreatedByThisUser,
            ).flatMap { relatedItems ->
                relatedItems.map {
                    SManga.create().apply {
                        url = it.url
                        title = it.title
                        thumbnail_url = it.cover.url
                    }
                }
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val resolutionPref = ListPreference(screen.context).apply {
            key = "${RESOLUTION_PREF_KEY}_$lang"
            title = RESOLUTION_PREF_TITLE
            entries = RESOLUTION_PREF_ENTRIES
            entryValues = RESOLUTION_PREF_ENTRY_VALUES
            setDefaultValue(RESOLUTION_PREF_DEFAULT_VALUE)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString("${RESOLUTION_PREF_KEY}_$lang", entry).commit()
            }
        }
        val sortPref = ListPreference(screen.context).apply {
            key = "${SORT_PREF_KEY}_$lang"
            title = SORT_PREF_TITLE
            entries = SORT_PREF_ENTRIES
            entryValues = SORT_PREF_ENTRY_VALUES
            setDefaultValue(SORT_PREF_DEFAULT_VALUE)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString("${SORT_PREF_KEY}_$lang", entry).commit()
            }
        }
        val mergeChapterPref = CheckBoxPreference(screen.context).apply {
            key = "${MERGE_CHAPTER_PREF_KEY}_$lang"
            title = MERGE_CHAPTER_PREF_TITLE
            summary = MERGE_CHAPTER_PREF_SUMMARY
            setDefaultValue(MERGE_CHAPTER_PREF_DEFAULT_VALUE)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit().putBoolean("${MERGE_CHAPTER_PREF_KEY}_$lang", checkValue).commit()
            }
        }
        screen.addPreference(resolutionPref)
        screen.addPreference(sortPref)
        screen.addPreference(mergeChapterPref)
    }

    fun getMergeChapterPref(): Boolean = preferences.getBoolean("${MERGE_CHAPTER_PREF_KEY}_$lang", MERGE_CHAPTER_PREF_DEFAULT_VALUE)
    fun getResolutionPref(): String? = preferences.getString("${RESOLUTION_PREF_KEY}_$lang", RESOLUTION_PREF_DEFAULT_VALUE)
    fun getSortPref(): String? = preferences.getString("${SORT_PREF_KEY}_$lang", SORT_PREF_DEFAULT_VALUE)
}
