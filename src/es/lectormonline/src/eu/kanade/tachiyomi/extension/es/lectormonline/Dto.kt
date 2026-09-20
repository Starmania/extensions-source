package eu.kanade.tachiyomi.extension.es.lectormonline

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
class SvelteNode(
    val data: JsonObject? = null,
)

@Serializable
class ResultsDto(
    val comics: List<ComicDto> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
)

@Serializable
class ComicDto(
    private val title: String,
    private val comicPath: String,
    private val coverImage: String? = null,
    private val status: String? = null,
    private val genres: List<String>? = null,
    private val description: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        title = this@ComicDto.title
        url = comicPath
        thumbnail_url = coverImage?.let(::proxiedImage)
        description = this@ComicDto.description
        genre = genres?.joinToString()
        status = parseStatus(this@ComicDto.status)
    }
}

@Serializable
class ComicDetailsDto(
    private val title: String,
    private val description: String? = null,
    private val coverImage: String? = null,
    private val status: String? = null,
    private val genres: List<GenreDto>? = null,
    val comicScans: List<ComicScanDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        title = this@ComicDetailsDto.title
        description = this@ComicDetailsDto.description
        thumbnail_url = coverImage?.let(::proxiedImage)
        genre = genres?.joinToString { it.name }
        status = parseStatus(this@ComicDetailsDto.status)
    }
}

@Serializable
class GenreDto(
    val name: String,
)

@Serializable
class ComicScanDto(
    private val scanGroup: ScanGroupDto? = null,
    val chapters: List<ChapterDto> = emptyList(),
) {
    val groupName: String? get() = scanGroup?.name
}

@Serializable
class ScanGroupDto(
    val name: String? = null,
)

@Serializable
class ChapterDto(
    private val chapterNumber: Float,
    private val chapterPath: String,
    val releaseDate: String? = null,
) {
    fun toSChapter(groupName: String?) = SChapter.create().apply {
        // The API's chapter title is the series title, so it can't tell chapters apart.
        name = "Capítulo ${chapterNumber.toString().removeSuffix(".0")}"
        url = chapterPath
        scanlator = groupName
        chapter_number = chapterNumber
    }
}

@Serializable
class ChapterPagesDto(
    @SerialName("url_pages") val urlPages: List<String> = emptyList(),
)

// The image CDN blocks direct requests; the site itself loads every image through this proxy.
internal fun proxiedImage(url: String): String = "https://mango-proxy-image.zincbaq.workers.dev/".toHttpUrl().newBuilder()
    .addQueryParameter("url", url)
    .build()
    .toString()

internal fun parseStatus(state: String?): Int = when (state?.uppercase()) {
    "ONGOING" -> SManga.ONGOING
    "COMPLETED" -> SManga.COMPLETED
    "HIATUS" -> SManga.ON_HIATUS
    "CANCELLED" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}
