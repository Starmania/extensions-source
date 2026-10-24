package eu.kanade.tachiyomi.extension.es.skymangas

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
class ResponseDto<T>(
    val data: T,
    val meta: MetaDto? = null,
)

@Serializable
class MetaDto(
    private val page: Int,
    private val totalPages: Int,
) {
    fun hasNextPage() = page < totalPages
}

@Serializable
class SearchMangaDto(
    private val title: String,
    private val slug: String,
    @SerialName("cover_thumb_url") private val coverThumbUrl: String? = null,
) {
    fun toSManga(apiUrl: String) = SManga.create().apply {
        url = slug
        title = this@SearchMangaDto.title
        thumbnail_url = coverThumbUrl?.let { apiUrl + it }
    }
}

@Serializable
class MangaDetailsDto(
    val id: String,
    private val title: String,
    private val slug: String,
    private val titleAlt: String? = null,
    private val synopsis: String? = null,
    private val coverUrl: String? = null,
    private val statusId: Int? = null,
    private val author: String? = null,
    private val artist: String? = null,
    private val originationName: String? = null,
    private val genres: List<NameDto> = emptyList(),
    private val tags: List<NameDto> = emptyList(),
) {
    fun toSManga(apiUrl: String) = SManga.create().apply {
        url = slug
        title = this@MangaDetailsDto.title
        thumbnail_url = coverUrl?.let { apiUrl + it }
        author = this@MangaDetailsDto.author
        artist = this@MangaDetailsDto.artist
        description = buildString {
            synopsis?.let { append(it) }
            if (!titleAlt.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Títulos alternativos:\n", titleAlt)
            }
        }
        genre = (listOfNotNull(originationName) + genres.map { it.name } + tags.map { it.name }).joinToString()
        status = when (statusId) {
            1 -> SManga.ONGOING
            2 -> SManga.COMPLETED
            3 -> SManga.ON_HIATUS
            4 -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class NameDto(val name: String)

@Serializable
class ChapterDto(
    private val chapterNumber: Float,
    private val title: String? = null,
    private val publishedAt: String? = null,
) {
    fun toSChapter(mangaSlug: String) = SChapter.create().apply {
        val number = chapterNumber.toString().removeSuffix(".0")
        url = "$mangaSlug/$number"
        name = buildString {
            append("Capítulo ", number)
            if (!title.isNullOrBlank()) append(" - ", title)
        }
        chapter_number = chapterNumber
        date_upload = Instant.tryParse(publishedAt)
    }
}

@Serializable
class ChapterDetailsDto(
    val pages: List<PageDto>,
)

@Serializable
class PageDto(
    private val imageUrl: String,
) {
    fun toPage(index: Int, apiUrl: String) = Page(index, imageUrl = apiUrl + imageUrl)
}
