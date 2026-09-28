package eu.kanade.tachiyomi.extension.en.sacachispa

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
class ResponseDto<T>(
    val data: T,
)

@Serializable
class PaginatedDto<T>(
    val data: List<T>,
    val pagination: PaginationDto,
)

@Serializable
class PaginationDto(
    val page: Int,
    val pages: Int,
)

@Serializable
class MangaListDto(
    private val title: String,
    private val slug: String,
    private val cover: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        title = this@MangaListDto.title
        url = slug
        thumbnail_url = cover?.let { "$CDN_URL/$it" }
    }
}

@Serializable
class MangaDto(
    val id: String,
    private val title: String,
    private val slug: String,
    private val status: String? = null,
    private val covers: List<CoverDto> = emptyList(),
    private val authors: List<NameDto> = emptyList(),
    private val artists: List<NameDto> = emptyList(),
    private val genres: List<NameDto> = emptyList(),
    private val synopses: List<SynopsisDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        title = this@MangaDto.title
        url = slug
        thumbnail_url = covers.firstOrNull()?.image?.let { "$CDN_URL/$it" }
        author = authors.joinToString { it.name }
        artist = artists.joinToString { it.name }
        genre = genres.joinToString { it.name }
        description = (synopses.firstOrNull { it.language == "en" } ?: synopses.firstOrNull())?.synopsis
        status = when (this@MangaDto.status) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "HIATUS" -> SManga.ON_HIATUS
            "CANCELLED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }
}

@Serializable
class CoverDto(
    val image: String,
)

@Serializable
class NameDto(
    val name: String,
)

@Serializable
class SynopsisDto(
    val language: String? = null,
    val synopsis: String? = null,
)

private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

@Serializable
class ReleaseDto(
    private val id: String,
    private val publishedAt: String? = null,
    val chapter: ReleaseChapterDto,
    private val group: NameDto? = null,
) {
    fun toSChapter() = SChapter.create().apply {
        url = id
        val number = chapter.chapter
        name = buildString {
            append("Chapter $number")
            chapter.title?.takeIf { it.isNotBlank() }?.let { append(" - $it") }
        }
        chapter_number = number.toFloatOrNull() ?: -1f
        scanlator = group?.name
        date_upload = dateFormat.tryParse(publishedAt)
    }
}

@Serializable
class ReleaseChapterDto(
    val chapter: String,
    val title: String? = null,
    val patreonOnly: Boolean = false,
)

@Serializable
class PagesDto(
    private val items: List<PageDto>,
) {
    fun toPageList() = items.sortedBy { it.page }.mapIndexed { index, page -> Page(index, imageUrl = page.url) }
}

@Serializable
class PageDto(
    val page: Int,
    val url: String,
)
