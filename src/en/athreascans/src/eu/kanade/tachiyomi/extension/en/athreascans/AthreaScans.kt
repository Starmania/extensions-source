package eu.kanade.tachiyomi.extension.en.athreascans

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesiaPaidChapterHelper
import eu.kanade.tachiyomi.source.ConfigurableSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferences
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element

@Source
abstract class AthreaScans :
    MangaThemesia(),
    ConfigurableSource {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(2)

    private val preferences: SharedPreferences = getPreferences()

    private val paidChapterHelper = MangaThemesiaPaidChapterHelper()

    override fun chapterListSelector(): String = paidChapterHelper.getChapterListSelectorBasedOnHidePaidChaptersPref(
        super.chapterListSelector(),
        preferences,
    )

    override fun chapterFromElement(element: Element) = super.chapterFromElement(element).apply {
        // Locked chapters have no href, only the post id on the modal trigger.
        // WordPress redirects ?p=<id> to the chapter permalink.
        if (url.isBlank()) {
            element.selectFirst("a[data-id]")?.attr("data-id")?.let { url = "/?p=$it" }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        paidChapterHelper.addHidePaidChaptersPreferenceToScreen(screen, intl)
    }
}
