package eu.kanade.tachiyomi.extension.all.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.lib.cronet.cronet
import keiyoushi.lib.cronet.isCronet
import keiyoushi.network.Fetch
import keiyoushi.network.fetch
import keiyoushi.source.KeiSource
import keiyoushi.utils.runWebViewBlocking
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Debug : KeiSource() {

    override val supportsLatest = false

    // ==============================
    // Interceptor for Rendering Text & Preventing 404s
    // ==============================

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor { chain ->
        val request = chain.request()
        val httpUrl = request.url

        // 1. Prevent 404s on dummy routes: Intercept all example.com requests
        if (httpUrl.host == "example.com") {
            return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200) // Force a success code so Mihon doesn't abort
                .message("OK").body("Dummy Body".toResponseBody("text/plain".toMediaTypeOrNull())).build()
        }

        // 2. Intercept the passive analysis to draw the response as an image
        if (httpUrl.encodedFragment == "render_text") {
            Log.d(LOG_TAG, "Intercepting render request: ${request.url}")

            val response = chain.proceed(request)

            val textResponse = response.body.string()
            Log.d(LOG_TAG, "Original text response:\n$textResponse")

            val fetch = httpUrl.queryParameter("fetch")?.let { "Fetch: $it ${httpUrl.queryParameter("from").orEmpty()}\n" }
            val bitmap = textToBitmap("${fetch.orEmpty()}Cronet: ${response.isCronet}\n\n$textResponse")

            return@addInterceptor response.newBuilder().code(200)
                .body(bitmap.toPng().toResponseBody("image/png".toMediaTypeOrNull())).build()
        } else if (httpUrl.encodedFragment == "render_gif") {
            Log.d(LOG_TAG, "Intercepting render request: ${request.url}")

            val response = chain.proceed(request)

            val textResponse = response.body.string()

            val gifBase = byteArrayOf(
                0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00,
                0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0xff.toByte(),
                0xff.toByte(), 0xff.toByte(), 0x21, 0xf9.toByte(), 0x04, 0x01,
                0x00, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, 0x00, 0x01,
                0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3b,
            )
            val bytes = gifBase + ("\n" + textResponse + "\n").toByteArray()

            return@addInterceptor response.newBuilder().code(200)
                .body(bytes.toResponseBody("image/gif".toMediaTypeOrNull())).build()
        } else if (httpUrl.encodedFragment == "in_webview") {
            Log.d(LOG_TAG, "Intercepting webview request: ${request.url}")

            val bitmap = textToBitmap(getResponseFromWebview(chain.call(), request.url.toString()))
            return@addInterceptor pngResponse(request, bitmap)
        } else if (httpUrl.encodedFragment == "fingerprint_qr") {
            Log.d(LOG_TAG, "Intercepting fingerprint request: ${request.url}")

            val reportUrl = waitForFingerprintReport(chain.call(), httpUrl.newBuilder().fragment(null).build().toString())
            Log.d(LOG_TAG, "Fingerprint report: $reportUrl")
            return@addInterceptor pngResponse(request, qrToBitmap(reportUrl))
        }

        // Proceed normally for any other requests (like the Active Analysis image endpoints)
        chain.proceed(request)
    }.apply { if (name.endsWith("(Cronet)")) cronet() }

    // Pages with a "fetch" parameter go out as that Fetch kind, coming from the "from" page if set.
    override fun getImageRequest(page: Page): Request {
        val url = page.imageUrl!!.toHttpUrl()
        val kind = url.queryParameter("fetch")?.let(FETCH_KINDS::getValue) ?: return super.getImageRequest(page)
        val fetch = url.queryParameter("from")?.let { kind.from(it) } ?: kind
        return GET(url, headers).newBuilder().fetch(fetch).build()
    }

    private fun pngResponse(request: Request, bitmap: Bitmap) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK")
        .body(bitmap.toPng().toResponseBody("image/png".toMediaTypeOrNull())).build()

    private fun Bitmap.toPng(): ByteArray = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun textToBitmap(text: String): Bitmap {
        val staticLayout = textLayout(text)

        // Add extra height to ensure the bottom text isn't cut off
        val bitmap = Bitmap.createBitmap(1040, staticLayout.height + 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(Color.WHITE)
        canvas.translate(20f, 20f) // Add slight padding
        staticLayout.draw(canvas)

        return bitmap
    }

    /** Draws [text] as a QR code, with [text] written under it. */
    private fun qrToBitmap(text: String): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4))
        val scale = 1040 / matrix.width
        val size = matrix.width * scale
        val caption = textLayout(text)

        val bitmap = Bitmap.createBitmap(1040, size + caption.height + 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val pixels = IntArray(size * size) { i ->
            if (matrix[i % size / scale, i / size / scale]) Color.BLACK else Color.WHITE
        }
        bitmap.setPixels(pixels, 0, size, (1040 - size) / 2, 0, size, size)

        canvas.translate(20f, size + 20f)
        caption.draw(canvas)

        return bitmap
    }

    private fun textLayout(text: String): StaticLayout {
        val textPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 28f
            isAntiAlias = true
        }

        @Suppress("DEPRECATION")
        return StaticLayout(text, textPaint, 1000, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
    }

    fun getResponseFromWebview(call: Call, url: String): String {
        val httpUrl = url.toHttpUrl()
        val script = httpUrl.queryParameter("script") ?: DUMP_DOM_SCRIPT
        // Drop the private "script" param and the "#in_webview" marker before navigating.
        val pageUrl = httpUrl.newBuilder().removeAllQueryParameters("script").fragment(null).build().toString()

        return runWebViewBlocking(call, timeout = 30.seconds) {
            userAgent = headers["User-Agent"]!!
            webMessageBridge("kei") { resolve(it) }
            onPageFinished {
                evaluateJs(script)
            }
            loadUrl(pageUrl)
        }
    }

    // The site runs its tests, then posts the results in a top-level form that lands on the report page.
    private fun waitForFingerprintReport(call: Call, url: String): String = runWebViewBlocking(call, timeout = 90.seconds) {
        userAgent = headers["User-Agent"]!!
        val onNavigation = { pageUrl: String -> REPORT_URL.find(pageUrl)?.let { resolve(it.value) } }
        onPageStarted { onNavigation(it) }
        onPageFinished { onNavigation(it) }
        loadUrl(url)
    }

    /**
     * Builds a page url handled by the "in_webview" interceptor branch. With no [script] the
     * WebView dumps the loaded document instead.
     */
    private fun webviewPage(index: Int, pageUrl: String, script: String? = null) = Page(
        index,
        "",
        pageUrl.toHttpUrl().newBuilder()
            .apply { if (script != null) addQueryParameter("script", script) }
            .fragment("in_webview")
            .build()
            .toString(),
    )

    /** Builds a page url rendering what tls.peet.ws received for a request sent as [fetch], from [from] if set. */
    private fun fetchPage(index: Int, baseInt: Long, fetch: String, from: String? = null) = Page(
        index,
        "",
        "https://tls.peet.ws/api/all".toHttpUrl().newBuilder()
            .addQueryParameter("baseInt", baseInt.toString())
            .addQueryParameter("fetch", fetch)
            .apply { if (from != null) addQueryParameter("from", from) }
            .fragment("render_text")
            .build()
            .toString(),
    )

    // ==============================
    // Static Extension Data Routing
    // ==============================

    // Popular Updates Route
    override suspend fun getPopularManga(page: Int): MangasPage {
        val mangas = listOf(
            SManga.create().apply {
                title = "Passive Analysis"
                url = "/passive"
                description = "Renders TLS/Bot analysis directly into a viewable image."
            },
            SManga.create().apply {
                title = "Active Analysis"
                url = "/active"
                description = "Triggers endpoints natively. Returns broken images."
            },
            SManga.create().apply {
                title = "Internal Webview"
                url = "/webview"
                description = "Opens a webview with runWebView() to test the internal webview."
            },
        )
        return MangasPage(mangas, false)
    }

    override suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        val baseInt = System.currentTimeMillis()
        val chapters = mutableListOf<SChapter>()

        if (manga.url.contains("/passive")) {
            chapters.add(
                SChapter.create().apply {
                    name = "1. Tls.peet.ws (Rendered Output)"
                    url = "/passive/1/$baseInt"
                },
            )
            chapters.add(
                SChapter.create().apply {
                    name = "2. Fetch kinds (Rendered Output)"
                    url = "/passive/2/$baseInt"
                },
            )
        } else if (manga.url.contains("/active")) {
            chapters.add(
                SChapter.create().apply {
                    name = "1. Tls.peet.ws"
                    url = "/active/1/$baseInt"
                },
            )
            chapters.add(
                SChapter.create().apply {
                    name = "2. Device & Browser Info"
                    url = "/active/2/$baseInt"
                },
            )
            chapters.add(
                SChapter.create().apply {
                    name = "3. Web Browser"
                    url = "/active/3/$baseInt"
                },
            )
        } else if (manga.url.contains("/webview")) {
            chapters.add(
                SChapter.create().apply {
                    name = "1. Tls.peet.ws (Rendered Output)"
                    url = "/webview/1/$baseInt"
                },
            )
            chapters.add(
                SChapter.create().apply {
                    name = "2. High Entropy Values"
                    url = "/webview/2/$baseInt"
                },
            )
            chapters.add(
                SChapter.create().apply {
                    name = "3. Browser Fingerprinting Report (QR)"
                    url = "/webview/3/$baseInt"
                },
            )
        }
        return SMangaUpdate(manga, chapters.reversed())
    }

    // Page List Route
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val request = GET(baseUrl + chapter.url, headers)
        val url = request.url.toString()
        val baseInt = url.substringAfterLast("/").toLongOrNull() ?: System.currentTimeMillis()
        return when {
            url.contains("/passive/1") -> listOf(
                Page(0, "", "https://tls.peet.ws/api/all?baseInt=$baseInt#render_text"),
                Page(1, "", "https://tls.peet.ws/api/all?baseInt=$baseInt#render_gif"),
            )
            url.contains("/passive/2") -> listOf(
                fetchPage(0, baseInt, "DOCUMENT"),
                fetchPage(1, baseInt, "XHR"),
                fetchPage(2, baseInt, "XHR", "https://tls.peet.ws/"),
                fetchPage(3, baseInt, "SCRIPT"),
                fetchPage(4, baseInt, "IMAGE"),
                fetchPage(5, baseInt, "IMAGE", "https://example.org/"),
                fetchPage(6, baseInt, "NONE"),
            )

            url.contains("/active/1") -> listOf(Page(0, "", "https://tls.peet.ws/api/all?"))
            url.contains("/active/2") -> listOf(
                Page(0, "", "https://deviceandbrowserinfo.com/are_you_a_bot"),
                Page(1, "", "https://fingerprint-scan.com/fpscanner/demo"),
            )
            url.contains("/active/3") -> listOf(Page(0, "", "https://google.com"))

            url.contains("/webview/1") -> listOf(webviewPage(0, "https://tls.peet.ws/api/all?baseInt=$baseInt"))
            url.contains("/webview/2") -> listOf(webviewPage(0, "https://tls.peet.ws/api/all?baseInt=$baseInt", HIGH_ENTROPY_SCRIPT))
            url.contains("/webview/3") -> listOf(Page(0, "", "https://browser-fingerprinting.doesdev.fr/?auto&baseInt=$baseInt#fingerprint_qr"))

            else -> emptyList()
        }
    }

    // ==============================
    // Unused Required Overrides
    // ==============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = MangasPage(emptyList(), false)

    companion object {
        private const val LOG_TAG = "DebugExtension"

        private val FETCH_KINDS = mapOf(
            "DOCUMENT" to Fetch.DOCUMENT,
            "XHR" to Fetch.XHR,
            "SCRIPT" to Fetch.SCRIPT,
            "IMAGE" to Fetch.IMAGE,
            "NONE" to Fetch.NONE,
        )

        private val REPORT_URL = Regex("""^https://browser-fingerprinting\.doesdev\.fr/report/[a-f0-9]+""")

        private const val DUMP_DOM_SCRIPT = "(()=>{window.kei.postMessage(document.documentElement.outerHTML)})()"

        private const val HIGH_ENTROPY_SCRIPT =
            "(async()=>{window.kei.postMessage(JSON.stringify(await navigator.userAgentData.getHighEntropyValues(" +
                "['architecture','bitness','formFactors','fullVersionList','model','platformVersion'," +
                "'uaFullVersion','wow64']),null,2))})()"
    }
}
