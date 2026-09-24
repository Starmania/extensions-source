package eu.kanade.tachiyomi.extension.th.niceoppai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer

// Some reader pages are served tile-shuffled and reassembled client-side; the tile map
// travels in the URL fragment (see pageListParse).
class ImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fragment = request.url.fragment ?: return chain.proceed(request)
        val data = fragment.parseAs<ScrambledImage>()

        val response = chain.proceed(request)
        if (!response.isSuccessful) return response

        val scrambled = response.use { BitmapFactory.decodeStream(it.body.byteStream()) }
        val result = scrambled.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        for ((dx, dy, sx, sy) in data.map) {
            canvas.drawBitmap(
                scrambled,
                Rect(sx, sy, sx + data.px, sy + data.py),
                Rect(dx, dy, dx + data.px, dy + data.py),
                null,
            )
        }
        scrambled.recycle()

        val output = Buffer()
        result.compress(Bitmap.CompressFormat.JPEG, 90, output.outputStream())
        result.recycle()

        return response.newBuilder()
            .body(output.asResponseBody(JPEG_MEDIA_TYPE, output.size))
            .build()
    }

    companion object {
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
    }
}

@Serializable
class ScrambledImage(
    val u: String,
    val px: Int,
    val py: Int,
    // [dx, dy, sx, sy] for each tile
    val map: List<List<Int>>,
)
