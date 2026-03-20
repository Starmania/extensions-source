package keiyoushi.lib.chunkedhttplogging

import android.util.Log
import keiyoushi.lib.chunkedhttplogging.ChunkedHttpLogger.Companion.DEFAULT_LOG_WRITER
import keiyoushi.lib.logcatchunker.LogWriter
import keiyoushi.lib.logcatchunker.LogcatChunker
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Creates an [HttpLoggingInterceptor.Logger] that automatically chunks long messages to avoid
 * Android logcat truncation.
 *
 * Usage:
 * ```gradle
 * // Under the "apply from: ..."
 *
 * dependencies {
 *    implementation(project(":lib:chunkedhttplogging"))
 * }
 * ```
 *
 * Then, when building your OkHttpClient:
 * ```kotlin
 *
 * val client = OkHttpClient.Builder()
 *     .addNetworkInterceptor(getChunkedHttpLogger("MyHttpLogger"))
 *     .build()
 * ```
 *
 * @param tag The log tag to use for all log messages.
 * @param chunkSize Maximum size of each log chunk (default: [LogcatChunker.DEFAULT_CHUNK_SIZE]).
 * @param logWriter Custom log writer (default: uses Android's Log.v).
 */
class ChunkedHttpLogger(
    private val tag: String,
    private val chunkSize: Int = LogcatChunker.DEFAULT_CHUNK_SIZE,
    private val logWriter: LogWriter = DEFAULT_LOG_WRITER,
) : HttpLoggingInterceptor.Logger {

    override fun log(message: String) {
        LogcatChunker.logChunked(
            message = message,
            tag = tag,
            chunkSize = chunkSize,
            logWriter = logWriter,
        )
    }

    companion object {
        /**
         * Default log writer that uses Android's Log.v.
         */
        val DEFAULT_LOG_WRITER = LogWriter { tag, msg -> Log.v(tag, msg) }
    }
}

fun getChunkedHttpLogger(
    tag: String,
    chunkSize: Int = LogcatChunker.DEFAULT_CHUNK_SIZE,
    logWriter: LogWriter = DEFAULT_LOG_WRITER,
): Interceptor = HttpLoggingInterceptor(
    ChunkedHttpLogger(tag, chunkSize, logWriter),
).apply {
    level = HttpLoggingInterceptor.Level.BODY
}
