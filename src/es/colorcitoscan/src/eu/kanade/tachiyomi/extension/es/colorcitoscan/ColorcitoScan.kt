package eu.kanade.tachiyomi.extension.es.colorcitoscan

import eu.kanade.tachiyomi.multisrc.spicytheme.SpicyTheme
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.OkHttpClient

@Source
abstract class ColorcitoScan : SpicyTheme() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)
}
