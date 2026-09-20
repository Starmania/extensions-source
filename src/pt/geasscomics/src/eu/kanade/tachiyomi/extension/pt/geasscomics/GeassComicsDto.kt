package eu.kanade.tachiyomi.extension.pt.geasscomics

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.Serializable
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Instant

// ========================= API Response Wrapper =========================

@Serializable
class ApiResponse<T>(
    val data: T,
)

// ========================= Work DTOs =========================

@Serializable
class WorkListDto(
    val items: List<WorkDto>,
    private val page: Int,
    private val pageCount: Int,
) {
    fun hasNextPage(): Boolean = page < pageCount
}

@Serializable
class RankingEntryDto(
    val work: WorkDto,
)

@Serializable
class WorkDto(
    val slug: String,
    private val title: String,
    private val cover: String? = null,
    private val status: String? = null,
    private val tags: List<String> = emptyList(),
    val isNsfw: Boolean = false,
    private val author: String? = null,
    private val synopsis: String? = null,
    val chapters: List<ChapterDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        url = "/manga/$slug"
        title = this@WorkDto.title
        thumbnail_url = cover
        description = synopsis?.takeIf { it.isNotBlank() }
        author = this@WorkDto.author?.takeIf { it.isNotBlank() }
        genre = tags.joinToString().takeIf { it.isNotBlank() }
        status = when (this@WorkDto.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class FilterOptionDto(
    val slug: String,
    val label: String,
    val isNsfw: Boolean = false,
)

// ========================= Chapter DTOs =========================

private val sqlDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

@Serializable
class ChapterDto(
    private val id: String,
    private val number: Double,
    private val title: String? = null,
    private val releasedAt: String? = null,
) {
    fun toSChapter(workSlug: String) = SChapter.create().apply {
        val chapterNumber = number.toString().removeSuffix(".0")
        url = "/chapter/$id/$workSlug/$chapterNumber"
        name = buildString {
            append("Capítulo $chapterNumber")
            this@ChapterDto.title?.takeIf { it.isNotBlank() && !it.startsWith("Capítulo") }?.let {
                append(" - $it")
            }
        }
        chapter_number = number.toFloat()
        // Older chapters carry a zone-less "yyyy-MM-dd HH:mm:ss", newer ones an ISO instant.
        date_upload = releasedAt?.let {
            if ('T' in it) Instant.tryParse(it) else sqlDateFormat.tryParseDateTime(it)
        } ?: 0L
    }
}

// ========================= Pages DTOs =========================

@Serializable
class ReadDto(
    val pages: List<String>,
    val pageScrambles: List<String?> = emptyList(),
)

// ========================= Auth DTOs =========================

@Serializable
class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
class LoginResponseData(
    val accessToken: String,
)
