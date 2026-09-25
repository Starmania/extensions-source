package eu.kanade.tachiyomi.extension.tr.strayfansub

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.OkHttpClient

@Source
abstract class StrayFansub : MangaThemesia() {
    override val datePattern = "d MMMM yyyy"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)
}
