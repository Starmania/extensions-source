package eu.kanade.tachiyomi.extension.en.nexcomic

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

@Serializable
class HomeDto(
    val initialSeries: List<SeriesDto>,
    val initialChapters: List<ChapterDto>,
    val initialPopularity: PopularityDto,
)

@Serializable
class PopularityDto(
    val all: List<SlugDto>,
)

@Serializable
class SlugDto(
    val slug: String,
)

@Serializable
class CatalogPropsDto(
    val initialCatalog: CatalogDto,
)

@Serializable
class CatalogDto(
    val series: List<SeriesDto>,
    val chapters: List<ChapterDto>,
)

@Serializable
class SeriesDto(
    val slug: String,
    val title: String,
    val alternativeTitles: List<String> = emptyList(),
    private val type: String? = null,
    private val status: String? = null,
    private val description: String? = null,
    private val genres: List<String> = emptyList(),
    private val authors: List<String> = emptyList(),
    private val artists: List<String> = emptyList(),
    private val avatar: ImageDto? = null,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = slug
        title = this@SeriesDto.title
        thumbnail_url = avatar?.let { baseUrl + it.url }
    }

    fun toDetails(baseUrl: String) = toSManga(baseUrl).apply {
        author = authors.joinToString().ifEmpty { null }
        artist = artists.joinToString().ifEmpty { null }
        description = this@SeriesDto.description
        genre = (listOfNotNull(type) + genres).joinToString()
        status = when (this@SeriesDto.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled", "dropped" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class ImageDto(
    val url: String,
)

@Serializable
class ChapterDto(
    val id: String,
    val seriesSlug: String,
    val locked: Boolean,
    val publishedAt: Long? = null,
    val pages: List<ImageDto> = emptyList(),
    private val chapterNumber: Float,
    private val title: String? = null,
) {
    fun toSChapter() = SChapter.create().apply {
        // id is "<series slug>:<chapter slug>"
        url = "$seriesSlug/${id.substringAfter(':')}"
        val number = chapterNumber.toString().removeSuffix(".0")
        name = if (title.isNullOrBlank()) "Chapter $number" else "Chapter $number: $title"
        chapter_number = chapterNumber
        date_upload = publishedAt ?: 0L
    }
}
