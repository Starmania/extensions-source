package eu.kanade.tachiyomi.extension.pt.cerisescans

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
class ComicListDto(
    private val data: List<ComicDto>,
    @SerialName("totalPages") val totalPages: Int,
) {
    // Novels have text chapters instead of images, so they can't be read here.
    fun comics() = data.filter { it.type == "comic" }
}

@Serializable
class ComicDto(
    private val slug: String,
    private val title: String,
    @SerialName("cover_image") private val coverImage: String? = null,
    private val description: String? = null,
    private val author: String? = null,
    private val artist: String? = null,
    private val status: String? = null,
    private val genres: List<String> = emptyList(),
    val type: String = "comic",
    val lastChapters: List<ChapterDto> = emptyList(),
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = slug
        title = this@ComicDto.title.trim()
        thumbnail_url = coverImage?.takeIf { it.isNotEmpty() }?.let { baseUrl + it }
        description = this@ComicDto.description?.takeIf { it.isNotEmpty() }
        author = this@ComicDto.author?.takeIf { it.isNotEmpty() }
        artist = this@ComicDto.artist?.takeIf { it.isNotEmpty() }
        genre = genres.joinToString()
        status = when (this@ComicDto.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "dropped", "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class ChapterDto(
    private val id: String,
    private val number: String,
    private val title: String? = null,
    @SerialName("created_at") private val createdAt: String? = null,
    val images: List<String> = emptyList(),
) {
    fun toSChapter() = SChapter.create().apply {
        url = id
        name = title?.takeIf { it.isNotBlank() } ?: "Capítulo ${number.removeSuffix(".0")}"
        chapter_number = number.toFloatOrNull() ?: -1f
        date_upload = Instant.tryParse(createdAt)
    }
}
