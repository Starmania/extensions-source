package eu.kanade.tachiyomi.extension.all.leagueoflegends

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
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import kotlin.time.Instant

@Source
abstract class LOLUniverse : KeiSource() {

    private val siteLang: String
        get() = baseUrl.substringAfter("leagueoflegends.com/").substringBefore("/comic/")

    override val supportsLatest get() = false

    override fun Headers.Builder.configureHeaders() = set("Origin", UNIVERSE_URL).set("Referer", "$UNIVERSE_URL/")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val mangas = client.get("$MEEPS_URL/$siteLang/comics/index.json").parseAs<LOLHub>().mapNotNull {
            SManga.create().apply {
                title = it.title ?: return@mapNotNull null
                url = it.toString()
                description = it.description!!.clean()
                thumbnail_url = it.background.toString()
                genre = it.subtitle ?: it.champions?.joinToString()
            }
        }
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int) = throw UnsupportedOperationException()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val popular = getPopularManga(page)
        return popular.copy(
            popular.mangas.filter {
                it.title.contains(query, true) || it.genre?.contains(query, true) ?: false
            },
        )
    }

    // There is no details endpoint: everything comes from the index, and the chapter date
    // is only available in each chapter's page list.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        if (!fetchChapters) return@coroutineScope SMangaUpdate(manga, chapters)

        val chapterList = if ('/' in manga.url) {
            listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = "One Shot"
                    chapter_number = 0f
                },
            )
        } else {
            client.get("$MEEPS_URL/$siteLang/comics/${manga.url}/index.json").parseAs<LOLIssues>().map {
                SChapter.create().apply {
                    name = it.title!!
                    url = it.toString()
                    chapter_number = it.index ?: -1f
                }
            }
        }

        chapterList.map { chapter ->
            async { chapter.date_upload = Instant.tryParse(fetchPages(chapter).date) }
        }.awaitAll()

        SMangaUpdate(manga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter) = fetchPages(chapter).mapIndexed { idx, img ->
        Page(idx, imageUrl = img.toString())
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    private suspend fun fetchPages(chapter: SChapter) = client.get("$COMICS_URL/$siteLang/${chapter.url}/index.json").parseAs<LOLPages>()

    private fun String.clean() = replace("</p> ", "</p>").replace("</p>", "\n").replace("<p>", "")

    companion object {
        private const val UNIVERSE_URL = "https://universe.leagueoflegends.com"

        private const val MEEPS_URL = "https://universe-meeps.leagueoflegends.com/v1"

        private const val COMICS_URL = "https://universe-comics.leagueoflegends.com/comics"
    }
}
