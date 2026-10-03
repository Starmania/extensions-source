package eu.kanade.tachiyomi.extension.es.insanosscan

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Serializable
class SeriesDto(
    private val id: Int,
    val title: String,
    private val description: String? = null,
    @SerialName("cover_image") private val coverImage: String? = null,
    private val genre: String? = null,
    private val author: String? = null,
    private val status: String? = null,
    @SerialName("view_count") val viewCount: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = id.toString()
        title = this@SeriesDto.title
        thumbnail_url = coverImage?.let { baseUrl + it }
    }

    fun toSMangaDetails(baseUrl: String) = toSManga(baseUrl).apply {
        description = this@SeriesDto.description
        author = this@SeriesDto.author
        genre = this@SeriesDto.genre
        status = when (this@SeriesDto.status?.lowercase()) {
            "en emisión" -> SManga.ONGOING
            "finalizado" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class ChapterDto(
    private val id: Int,
    @SerialName("series_id") private val seriesId: Int,
    @SerialName("chapter_number") val chapterNumber: Double,
    private val title: String? = null,
    @SerialName("is_unlocked") val isUnlocked: Boolean,
    @SerialName("published_at") private val publishedAt: String? = null,
) {
    fun toSChapter() = SChapter.create().apply {
        url = "$seriesId/$id"
        name = buildString {
            append("Capítulo ", chapterNumber.toString().removeSuffix(".0"))
            if (!title.isNullOrBlank()) append(" - ", title)
            if (!isUnlocked) append(" 🔒")
        }
        chapter_number = chapterNumber.toFloat()
        date_upload = DateTimeFormatter.ISO_LOCAL_DATE_TIME.tryParseDateTime(publishedAt, ZoneOffset.UTC)
    }
}

@Serializable
class PagesDto(val pages: List<String>)
