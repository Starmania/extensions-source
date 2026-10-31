package keiyoushi.lib.tilescramble

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import keiyoushi.lib.unpacker.Unpacker
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.io.IOException

private val BLOCK_WIDTH_REGEX = Regex("""width:\s*"?\s*\+?\s*(\d+)\s*\+?\s*"?px;""")
private val BLOCK_HEIGHT_REGEX = Regex("""height:\s*"?\s*\+?\s*(\d+)\s*\+?\s*"?px;""")
private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()

@Serializable
private class TileMap(
    val blockWidth: Int,
    val blockHeight: Int,
    // [destX, destY, srcX, srcY] for each tile
    val matrix: List<List<Double>>,
)

/**
 * Reads the Dean Edwards-packed script a reader page puts after `div.displayImage`, which rebuilds
 * a shuffled image from CSS background tiles, and returns the image's URL with the tile map in its
 * fragment, for [TileScrambleInterceptor] to put back together.
 */
fun tileScrambledImageUrl(packedScript: String): String {
    val script = Unpacker.unpack(packedScript)
    val tileMap = TileMap(
        blockWidth = BLOCK_WIDTH_REGEX.find(script)!!.groupValues[1].toInt(),
        blockHeight = BLOCK_HEIGHT_REGEX.find(script)!!.groupValues[1].toInt(),
        matrix = "[${script.substringAfter("[").substringBefore("];")}]".parseAs(),
    )
    val imageUrl = script.substringAfter("url(").substringBefore(");")
    return "$imageUrl#${tileMap.toJsonString()}"
}

/**
 * Descrambles the images whose URL came from [tileScrambledImageUrl], and leaves others alone.
 *
 * Add it with `addInterceptor`, not `addNetworkInterceptor`. A network interceptor runs once per
 * redirect hop and these image hosts do redirect: it would decode the 301's body as an image, and
 * the next hop has lost the fragment.
 */
class TileScrambleInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val tileMap = request.url.fragment?.parseAs<TileMap>() ?: return chain.proceed(request)

        val response = chain.proceed(request)
        if (!response.isSuccessful) return response

        val scrambled = response.use { BitmapFactory.decodeStream(it.body.byteStream()) }
            ?: throw IOException("Could not decode scrambled image ${request.url.redact()}")

        val descrambled = Bitmap.createBitmap(scrambled.width, scrambled.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(descrambled)
        for ((destX, destY, srcX, srcY) in tileMap.matrix.map { it.map(Double::toInt) }) {
            canvas.drawBitmap(
                scrambled,
                Rect(srcX, srcY, srcX + tileMap.blockWidth, srcY + tileMap.blockHeight),
                Rect(destX, destY, destX + tileMap.blockWidth, destY + tileMap.blockHeight),
                null,
            )
        }
        scrambled.recycle()

        val output = Buffer()
        descrambled.compress(Bitmap.CompressFormat.JPEG, 90, output.outputStream())
        descrambled.recycle()

        return response.newBuilder()
            .body(output.asResponseBody(JPEG_MEDIA_TYPE, output.size))
            .build()
    }
}
