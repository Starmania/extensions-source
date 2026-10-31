package eu.kanade.tachiyomi.extension.ja.mechacomic

import keiyoushi.utils.aesDecryptWithPrependedIv
import keiyoushi.utils.decodeHex
import okhttp3.Interceptor
import okhttp3.Response

class ImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val fragment = request.url.fragment

        if (fragment == null || !fragment.startsWith("key=") || !response.isSuccessful) return response

        return response.aesDecryptWithPrependedIv(fragment.substringAfter("key=").decodeHex(), ivSize = 16)
    }
}
