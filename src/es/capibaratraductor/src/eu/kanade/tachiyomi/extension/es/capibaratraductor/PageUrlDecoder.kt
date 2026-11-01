package eu.kanade.tachiyomi.extension.es.capibaratraductor

import android.util.Base64

// Port of the site's callApi script: page urls are encrypted with a key split between
// the `ls` field of the response and a variable injected into the site's html
fun parsePageKey(ls: String, html: String): Int {
    val bytes = decodeAlphabet(ls)
    check(bytes.size == 14) { "Unknown page key format" }
    val first = bytes[0]
    val parts = KEY_POSITIONS[first and 7].mapIndexed { i, position ->
        (bytes[position] xor KEY_MASK[(i + first) and 7] xor first) and 0xFF
    }
    val slot = (parts[0] shl 16) or (parts[1] shl 8) or parts[2]
    val keyPart = (parts[3] shl 24) or (parts[4] shl 16) or (parts[5] shl 8) or parts[6]

    val variables = HTML_KEY_REGEX.findAll(html).associate { it.groupValues[1] to it.groupValues[2] }
    for (candidate in slot - 1..slot + 1) {
        val name = "_" + mix(candidate xor HTML_KEY_NAME_SEED).toUInt().toString(36)
        val value = variables[name] ?: continue
        val offset = slot - candidate + 1
        val htmlPart = value.substring(offset * 8, offset * 8 + 8).toLong(16).toInt()
        return keyPart xor htmlPart xor mix((candidate * 3 + offset) xor HTML_KEY_SEED)
    }
    error("Page key not found")
}

fun decodePageUrl(token: String, key: Int, index: Int, id: Long): String {
    val bytes = decodeAlphabet(unscramble(token, index))
    val random = sfc32(key, index, fnv1a(id.toString().encodeToByteArray().map { it.toInt() and 0xFF }))

    var word = 0
    val decoded = List(bytes.size) { i ->
        if (i % 4 == 0) word = random()
        (bytes[i] xor (word ushr (i % 4 * 8))) and 0xFF
    }
    val url = decoded.drop(3)
    check(decoded[0] == 4 && fnv1a(url) and 0xFFFF == (decoded[1] shl 8) or decoded[2]) { "Invalid page url" }

    return ByteArray(url.size) { url[it].toByte() }.decodeToString()
}

// `lh` lists the promo pages the site's reader drops from the chapter
fun parseHiddenPages(lh: String): Set<Int> {
    val bytes = Base64.decode(lh, Base64.DEFAULT).map { it.toInt() and 0xFF }
    val seed = HIDDEN_SEED_POSITIONS.fold(0) { acc, position -> (acc shl 8) or bytes[position] }
    val rest = bytes.filterIndexed { i, _ -> i !in HIDDEN_SEED_POSITIONS }

    val order = rest.indices.toMutableList()
    val shuffle = Mulberry32(seed xor HIDDEN_SHUFFLE_KEY)
    for (i in order.lastIndex downTo 1) {
        val j = ((shuffle.next().toLong() and 0xFFFFFFFFL) * (i + 1) ushr 32).toInt()
        order[i] = order[j].also { order[j] = order[i] }
    }
    val mask = Mulberry32(seed xor HIDDEN_MASK_KEY)
    val values = order.map { (rest[it] xor (mask.next() ushr 24)) and 0xFF }

    val checksum = values.dropLast(1).foldIndexed(0) { i, acc, value -> (acc + value * (i + 1)) and 0xFF }
    check(checksum xor 165 == values.last()) { "Invalid hidden page list" }

    return List(values[0]) { (values[1 + it * 2] shl 8) or values[2 + it * 2] }.toSet()
}

private fun decodeAlphabet(input: String): List<Int> {
    val bytes = ArrayList<Int>(input.length * 6 / 8)
    var buffer = 0
    var bits = 0
    for (char in input) {
        val value = BYTE_ALPHABET.indexOf(char)
        check(value >= 0) { "Invalid page token" }
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            bytes += (buffer ushr bits) and 0xFF
        }
        buffer = buffer and ((1 shl bits) - 1)
    }
    return bytes
}

private fun unscramble(token: String, index: Int): String {
    val substituted = token.mapIndexed { i, char ->
        val value = SCRAMBLE_ALPHABET.indexOf(char)
        check(value >= 0) { "Invalid page token" }
        SCRAMBLE_ALPHABET[(value - i * 7 - 13 - index) and 63]
    }.joinToString("")

    val rotation = (substituted.length * 5 + index * 3 + 1) % substituted.length
    val rotated = substituted.takeLast(rotation) + substituted.dropLast(rotation)

    val half = (rotated.length + 1) / 2
    val head = rotated.take(half)
    val tail = rotated.drop(half).reversed()
    return rotated.indices.map { if (it % 2 == 0) head[it / 2] else tail[it / 2] }.joinToString("")
}

private fun mix(value: Int): Int {
    var e = value
    e = (e xor (e ushr 16)) * 2146121005
    e = (e xor (e ushr 15)) * 2221713035L.toInt()
    return e xor (e ushr 16)
}

private fun fnv1a(bytes: List<Int>): Int = bytes.fold(2166136261L.toInt()) { acc, byte -> (acc xor byte) * 16777619 }

private fun sfc32(key: Int, index: Int, idHash: Int): () -> Int {
    var a = mix(key xor 1821285621)
    var b = mix(a xor ((index + 1) * 2654435761L.toInt()))
    var c = mix(b xor (2246822519L.toInt()) xor idHash)
    var counter = mix(c xor 374761393)
    val next = {
        val result = a + b + counter
        counter++
        a = b xor (b ushr 9)
        b = c + (c shl 3)
        c = (c shl 21) or (c ushr 11)
        c += result
        result
    }
    repeat(16) { next() }
    return next
}

private class Mulberry32(private var state: Int) {
    fun next(): Int {
        state += 0x6D2B79F5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return t xor (t ushr 14)
    }
}

private val HTML_KEY_REGEX = Regex("""(_[0-9a-z]+)="([0-9a-f]{24})"""")
private const val BYTE_ALPHABET = "CDoIL78Qqwj9zKM6mOFdf0niV_AByGg4aNhbEetPsHr1x-TpZJYRk5lWu32vUcSX"
private const val SCRAMBLE_ALPHABET = "tgEib4lqMSha5TU13QX28deN-xuk6KywP0m_roGYvBWAnzFfjcJIVROp7CsDZ9HL"
private val KEY_POSITIONS = listOf(
    intArrayOf(12, 2, 13, 7, 1, 9, 8),
    intArrayOf(11, 4, 5, 6, 3, 9, 12),
    intArrayOf(9, 8, 2, 3, 1, 6, 13),
    intArrayOf(8, 7, 2, 13, 1, 3, 6),
    intArrayOf(13, 2, 11, 6, 4, 3, 8),
    intArrayOf(6, 1, 3, 4, 7, 5, 11),
    intArrayOf(4, 8, 7, 9, 1, 10, 13),
    intArrayOf(8, 3, 6, 13, 12, 10, 11),
)
private val KEY_MASK = intArrayOf(234, 55, 192, 73, 249, 251, 2, 58)
private const val HTML_KEY_NAME_SEED = 1000283751
private const val HTML_KEY_SEED = 739982445
private val HIDDEN_SEED_POSITIONS = listOf(2, 7, 11, 16)
private const val HIDDEN_SHUFFLE_KEY = 1374496675
private const val HIDDEN_MASK_KEY = 795581489
