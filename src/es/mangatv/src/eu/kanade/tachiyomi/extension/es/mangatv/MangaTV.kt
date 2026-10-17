package eu.kanade.tachiyomi.extension.es.mangatv

import android.util.Base64
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.Page
import keiyoushi.annotation.Source
import keiyoushi.lib.unpacker.Unpacker
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Source
abstract class MangaTV : MangaThemesia() {
    override val mangaUrlDirectory = "/lista"
    override val datePattern = "yyyy-MM-dd"

    override val seriesDescriptionSelector = "b:contains(Sinopsis) + span"
    override fun chapterListSelector() = "#chapterlist ul.clstyle li:has(.dt a)"

    override fun pageListParse(document: Document): List<Page> {
        val unpackedScript = document.selectFirst("script:containsData(eval)")!!.data()
            .let(Unpacker::unpack)

        val blob = READER_BLOB_REGEX.find(unpackedScript)!!.value.parseAs<EncryptedBlob>()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(Base64.decode(blob.k, Base64.DEFAULT), "AES"),
                GCMParameterSpec(128, Base64.decode(blob.iv, Base64.DEFAULT)),
            )
        }
        val images = cipher.doFinal(Base64.decode(blob.c, Base64.DEFAULT))
            .decodeToString()
            .parseAs<DecryptedImages>()

        return images.p.mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    override fun searchMangaUrl(page: Int, query: String) = baseUrl.toHttpUrl().newBuilder()
        .addPathSegment(mangaUrlDirectory.substring(1))
        .addQueryParameter("s", query)
        .addQueryParameter("page", page.toString())

    @Serializable
    class EncryptedBlob(val k: String, val iv: String, val c: String)

    @Serializable
    class DecryptedImages(val p: List<String>)

    companion object {
        private val READER_BLOB_REGEX = """\{"k":.*?\}""".toRegex()
    }
}
