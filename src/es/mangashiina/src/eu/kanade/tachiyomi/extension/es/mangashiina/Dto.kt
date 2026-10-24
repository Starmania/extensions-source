package eu.kanade.tachiyomi.extension.es.mangashiina

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
class CatalogDto(
    val mangas: List<CatalogMangaDto>,
)

@Serializable
class CatalogMangaDto(
    private val id: Int,
    val titulo: String,
    private val portada: String?,
    @SerialName("latest_update_at") val latestUpdateAt: String?,
    val totalViews: Int,
) {
    fun toSManga() = SManga.create().apply {
        url = id.toString()
        title = titulo
        thumbnail_url = portada
    }
}

@Serializable
class MangaDto(
    private val id: Int,
    private val titulo: String,
    @SerialName("titulo_original") private val tituloOriginal: String?,
    private val portada: String?,
    private val tipo: String?,
    private val genres: List<String>,
    private val descripcion: String?,
    private val studio: String?,
) {
    fun toSManga() = SManga.create().apply {
        url = id.toString()
        title = titulo
        thumbnail_url = portada
        author = studio?.takeIf { it.isNotBlank() }
        genre = (listOfNotNull(tipo) + genres).joinToString()
        description = buildString {
            descripcion?.trim()?.let(::append)
            tituloOriginal?.takeIf { it.isNotBlank() }?.let { append("\n\nTítulo original: $it") }
        }
    }
}

@Serializable
class ChapterListDto(
    val chapters: List<ChapterDto>,
)

@Serializable
class ChapterDto(
    private val id: Int,
    @SerialName("chapter_number") private val chapterNumber: Float,
    @SerialName("created_at") private val createdAt: String?,
    @SerialName("is_paid") private val isPaid: Boolean,
) {
    fun toSChapter() = SChapter.create().apply {
        url = id.toString()
        name = buildString {
            if (isPaid) append("🔒 ")
            append("Capítulo ")
            append(chapterNumber.toString().removeSuffix(".0"))
        }
        chapter_number = chapterNumber
        date_upload = Instant.tryParse(createdAt)
    }
}

@Serializable
class ChapterContentDto(
    val images: List<String> = emptyList(),
    val message: String? = null,
)
