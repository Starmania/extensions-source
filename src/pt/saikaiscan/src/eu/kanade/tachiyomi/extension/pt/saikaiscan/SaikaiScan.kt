package eu.kanade.tachiyomi.extension.pt.saikaiscan

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

@Source
abstract class SaikaiScan : KeiSource() {
    private val apiUrlHost by lazy { apiUrl.toHttpUrl().host }
    private val storageUrlHost by lazy { storageUrl.toHttpUrl().host }

    private val apiUrl = "https://api.${baseUrl.substringAfterLast("/")}"

    private val storageUrl = "https://s3-beta.${baseUrl.substringAfterLast("/")}"

    private val apiHeaders by lazy {
        headersBuilder()
            .add("Accept", ACCEPT_JSON)
            .build()
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(1, 2.seconds) { it.host == apiUrlHost }
        .rateLimit(1, 1.seconds) { it.host == storageUrlHost }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val apiEndpointUrl = "$apiUrl/api/stories".toHttpUrl().newBuilder()
            .addQueryParameter("format", COMIC_FORMAT_ID)
            .addQueryParameter("sortProperty", "pageviews")
            .addQueryParameter("sortDirection", "desc")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PER_PAGE)
            .addQueryParameter("relationships", "language,type,format")
            .build()

        return client.get(apiEndpointUrl, apiHeaders).toMangasPage()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val apiEndpointUrl = "$apiUrl/api/lancamentos".toHttpUrl().newBuilder()
            .addQueryParameter("format", COMIC_FORMAT_ID)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PER_PAGE)
            .addQueryParameter("relationships", "language,type,format,latestReleases.separator")
            .build()

        return client.get(apiEndpointUrl, apiHeaders).toMangasPage()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val apiEndpointUrl = "$apiUrl/api/stories".toHttpUrl().newBuilder()
            .addQueryParameter("format", COMIC_FORMAT_ID)
            .addQueryParameter("q", query)
            .addQueryParameter("sortProperty", "pageViews")
            .addQueryParameter("sortDirection", "desc")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PER_PAGE)
            .addQueryParameter("relationships", "language,type,format")

        filters.filterIsInstance<UrlQueryFilter>()
            .forEach { it.addQueryParameter(apiEndpointUrl) }

        return client.get(apiEndpointUrl.build(), apiHeaders).toMangasPage()
    }

    private fun Response.toMangasPage(): MangasPage {
        val result = parseAs<SaikaiScanPaginatedStoriesDto>()

        val mangaList = result.data!!.map { it.toSManga(storageUrl) }

        return MangasPage(mangaList, result.hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        val index = segments.indexOf("comics")
        val storySlug = segments.getOrNull(index + 1)?.takeIf { index != -1 && it.isNotEmpty() }
            ?: return null

        return fetchStory(storySlug, "language,type,format,artists,status").toSManga(storageUrl)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val storySlug = manga.url.substringAfterLast("/")

        val updatedManga = async {
            if (fetchDetails) {
                fetchStory(storySlug, "language,type,format,artists,status").toSManga(storageUrl)
            } else {
                manga
            }
        }
        val updatedChapters = async {
            if (fetchChapters) {
                val story = fetchStory(storySlug, "releases")

                story.releases
                    .filter { it.isActive == 1 }
                    .map { it.toSChapter(story.slug) }
                    .sortedByDescending(SChapter::chapter_number)
            } else {
                chapters
            }
        }

        SMangaUpdate(updatedManga.await(), updatedChapters.await())
    }

    private suspend fun fetchStory(storySlug: String, relationships: String): SaikaiScanStoryDto {
        val apiEndpointUrl = "$apiUrl/api/stories".toHttpUrl().newBuilder()
            .addQueryParameter("format", COMIC_FORMAT_ID)
            .addQueryParameter("slug", storySlug)
            .addQueryParameter("per_page", "1")
            .addQueryParameter("relationships", relationships)
            .build()

        return client.get(apiEndpointUrl, apiHeaders)
            .parseAs<SaikaiScanPaginatedStoriesDto>().data!![0]
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val releaseId = chapter.url
            .substringBeforeLast("/")
            .substringAfterLast("/")

        val apiEndpointUrl = "$apiUrl/api/releases/$releaseId".toHttpUrl().newBuilder()
            .addQueryParameter("relationships", "releaseImages")
            .build()

        val result = client.get(apiEndpointUrl, apiHeaders).parseAs<SaikaiScanReleaseResultDto>()

        return result.data?.releaseImages.orEmpty().mapIndexed { i, obj ->
            Page(i, imageUrl = "$storageUrl/${obj.image}")
        }
    }

    override fun imageRequest(page: Page): Request = super.imageRequest(page).newBuilder()
        .header("Accept", ACCEPT_IMAGE)
        .build()

    // fetch('https://api.saikai.com.br/api/genres')
    //     .then(res => res.json())
    //     .then(res => console.log(res.data.map(g => `Genre("${g.name}", ${g.id})`).join(',\n')))
    private fun getGenreList(): List<Genre> = listOf(
        Genre("Ação", 1),
        Genre("Adulto", 23),
        Genre("Artes Marciais", 84),
        Genre("Aventura", 2),
        Genre("Comédia", 15),
        Genre("Drama", 14),
        Genre("Ecchi", 19),
        Genre("Esportes", 42),
        Genre("eSports", 25),
        Genre("Fantasia", 3),
        Genre("Ficção Cientifica", 16),
        Genre("Histórico", 37),
        Genre("Horror", 27),
        Genre("Isekai", 52),
        Genre("Josei", 40),
        Genre("Luta", 68),
        Genre("Magia", 11),
        Genre("Militar", 76),
        Genre("Mistério", 57),
        Genre("MMORPG", 80),
        Genre("Música", 82),
        Genre("One-shot", 51),
        Genre("Psicológico", 34),
        Genre("Realidade Vitual", 18),
        Genre("Reencarnação", 43),
        Genre("Romance", 9),
        Genre("RPG", 61),
        Genre("Sci-fi", 58),
        Genre("Seinen", 21),
        Genre("Shoujo", 35),
        Genre("Shounen", 26),
        Genre("Slice of Life", 38),
        Genre("Sobrenatural", 74),
        Genre("Suspense", 63),
        Genre("Tragédia", 22),
        Genre("VRMMO", 17),
        Genre("Wuxia", 6),
        Genre("Xianxia", 7),
        Genre("Xuanhuan", 48),
        Genre("Yaoi", 41),
        Genre("Yuri", 83),
    )

    // fetch('https://api.saikai.com.br/api/countries?hasStories=1')
    //     .then(res => res.json())
    //     .then(res => console.log(res.data.map(g => `Country("${g.name}", ${g.id})`).join(',\n')))
    private fun getCountryList(): List<Country> = listOf(
        Country("Todas", 0),
        Country("Brasil", 32),
        Country("China", 45),
        Country("Coréia do Sul", 115),
        Country("Espanha", 199),
        Country("Estados Unidos da América", 1),
        Country("Japão", 109),
        Country("Portugal", 173),
    )

    // fetch('https://api.saikai.com.br/api/countries?hasStories=1')
    //     .then(res => res.json())
    //     .then(res => console.log(res.data.map(g => `Country("${g.name}", ${g.id})`).join(',\n')))
    private fun getStatusList(): List<Status> = listOf(
        Status("Todos", 0),
        Status("Cancelado", 5),
        Status("Concluído", 1),
        Status("Dropado", 6),
        Status("Em Andamento", 2),
        Status("Hiato", 4),
        Status("Pausado", 3),
    )

    private fun getSortProperties(): List<SortProperty> = listOf(
        SortProperty("Título", "title"),
        SortProperty("Quantidade de capítulos", "releases_count"),
        SortProperty("Visualizações", "pageviews"),
        SortProperty("Data de criação", "created_at"),
    )

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        CountryFilter(getCountryList()),
        StatusFilter(getStatusList()),
        SortByFilter(getSortProperties()),
        GenreFilter(getGenreList()),
    )

    companion object {
        private const val ACCEPT_IMAGE = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
        private const val ACCEPT_JSON = "application/json, text/plain, */*"
        private const val COMIC_FORMAT_ID = "2"
        private const val PER_PAGE = "12"
    }
}
