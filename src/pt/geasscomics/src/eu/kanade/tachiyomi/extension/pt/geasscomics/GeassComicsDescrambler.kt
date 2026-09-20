package eu.kanade.tachiyomi.extension.pt.geasscomics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.io.IOException

/**
 * Pages come as a grid of `tile`x`tile` squares shuffled with a seeded PRNG; the descriptor
 * `v1:<tile>:<seed hex>` travels in the image URL fragment. The site
 * redraws them client side, so this mirrors its reader: shuffle the tile indices with
 * mulberry32, and tile `i` of the output is tile `order[i]` of the download.
 */
object GeassComicsDescrambler : Interceptor {

    private val SCRAMBLE_REGEX = Regex("""v1:(\d+):([0-9a-f]+)""", RegexOption.IGNORE_CASE)

    private const val MIN_TILE = 8

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val scramble = chain.request().url.fragment?.let(SCRAMBLE_REGEX::matchEntire)
            ?: return response
        val tile = scramble.groupValues[1].toInt()
        if (tile < MIN_TILE) return response
        val seed = scramble.groupValues[2].toLong(16).toInt()

        val scrambled = response.use { BitmapFactory.decodeStream(it.body.byteStream()) }
            ?: throw IOException("Failed to decode page image")

        val width = scrambled.width
        val height = scrambled.height
        val columns = width / tile
        val rows = height / tile

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        // Keeps the right/bottom remainder that doesn't fill a whole tile, which is never shuffled.
        canvas.drawBitmap(scrambled, 0f, 0f, null)

        if (columns >= 2 && rows >= 2) {
            val order = shuffledIndices(columns * rows, seed)
            val source = Rect()
            val target = Rect()
            for (i in order.indices) {
                val from = order[i]
                source.setTile(from % columns, from / columns, tile)
                target.setTile(i % columns, i / columns, tile)
                canvas.drawBitmap(scrambled, source, target, null)
            }
        }
        scrambled.recycle()

        val buffer = Buffer()
        result.compress(Bitmap.CompressFormat.JPEG, 90, buffer.outputStream())
        result.recycle()

        return response.newBuilder()
            .body(buffer.asResponseBody("image/jpeg".toMediaType()))
            .header("Content-Type", "image/jpeg")
            .build()
    }

    private fun Rect.setTile(column: Int, row: Int, size: Int) = set(column * size, row * size, (column + 1) * size, (row + 1) * size)

    private fun shuffledIndices(count: Int, seed: Int): IntArray {
        val indices = IntArray(count) { it }
        var state = seed

        // mulberry32, using Int wraparound exactly like the site's Math.imul/|0 version.
        fun next(): Double {
            state += 0x6d2b79f5
            var t = (state xor (state ushr 15)) * (state or 1)
            t = (t + (t xor (t ushr 7)) * (t or 61)) xor t
            return (t xor (t ushr 14)).toUInt().toDouble() / 4294967296.0
        }
        for (i in count - 1 downTo 1) {
            val j = (next() * (i + 1)).toInt()
            val swap = indices[i]
            indices[i] = indices[j]
            indices[j] = swap
        }
        return indices
    }
}
