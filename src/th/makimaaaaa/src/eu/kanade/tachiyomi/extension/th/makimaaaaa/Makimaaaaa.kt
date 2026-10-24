package eu.kanade.tachiyomi.extension.th.makimaaaaa

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.asJsoup
import keiyoushi.utils.toJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class Makimaaaaa : MangaThemesia() {
    override val seriesTypeSelector = ".tsinfo .imptdt:contains(ประเภท) a"
    override val seriesStatusSelector = ".tsinfo .imptdt:contains(สถานะ) i"

    // The site's /manga/ rewrite rules are broken: the archive and every /manga/<slug>/ page 404
    // (some 301 into /manga/<slug>/<slug>/). WordPress still serves both through query vars.
    override fun searchMangaUrl(page: Int, query: String) = baseUrl.toHttpUrl().newBuilder().apply {
        addQueryParameter("post_type", "manga")
        if (query.isNotEmpty()) addQueryParameter("title", query)
        addQueryParameter("page", page.toString())
    }

    override suspend fun fetchFilterData() = parseGenres(
        client.get("$baseUrl/?post_type=manga").asJsoup(),
    ).toJsonElement()

    override fun getMangaUrl(manga: SManga) = "$baseUrl/?manga=${manga.url.removeSuffix("/").substringAfterLast('/')}"
}
