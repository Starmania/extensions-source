package eu.kanade.tachiyomi.extension.es.lectormangalat

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
class DataDto<T>(val data: T)

@Serializable
class SeriesPageDto(
    val data: List<SeriesDto>,
    private val meta: MetaDto,
) {
    val hasNextPage get() = meta.currentPage < meta.lastPage
}

@Serializable
class MetaDto(
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
)

@Serializable
class SeriesDto(
    private val slug: String,
    private val titulo: String,
    private val portada: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = "/comics/$slug"
        title = titulo
        thumbnail_url = portada
    }
}

@Serializable
class SeriesDetailsDto(
    private val slug: String,
    private val titulo: String,
    @SerialName("titulo_alternativo") private val tituloAlternativo: String? = null,
    private val sinopsis: String? = null,
    private val portada: String? = null,
    private val estado: String? = null,
    private val generos: List<String> = emptyList(),
    private val grupo: GroupDto? = null,
    private val capitulos: List<ChapterDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        url = "/comics/$slug"
        title = titulo
        thumbnail_url = portada
        description = buildString {
            sinopsis?.let { append(it) }
            tituloAlternativo?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append("\n\n")
                append("Títulos alternativos: ", it)
            }
        }
        genre = generos.joinToString()
        status = when (estado?.lowercase()) {
            "en emisión" -> SManga.ONGOING
            "finalizado" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    fun toSChapters() = capitulos.map { it.toSChapter(slug, grupo?.nombre) }
}

@Serializable
class GroupDto(val nombre: String)

@Serializable
class ChapterDto(
    private val numero: Float,
    private val titulo: String? = null,
    @SerialName("publicado_en") private val publicadoEn: String? = null,
) {
    fun toSChapter(slug: String, group: String?) = SChapter.create().apply {
        val number = numero.toString().removeSuffix(".0")
        url = "/comics/$slug/capitulo-$number"
        name = buildString {
            append("Capítulo ", number)
            titulo?.takeIf { it.isNotBlank() }?.let { append(": ", it) }
        }
        chapter_number = numero
        date_upload = Instant.tryParse(publicadoEn)
        scanlator = group
    }
}

@Serializable
class ChapterPagesDto(val paginas: List<String>)
