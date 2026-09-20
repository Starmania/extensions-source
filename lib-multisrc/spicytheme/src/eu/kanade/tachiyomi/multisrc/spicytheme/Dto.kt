package eu.kanade.tachiyomi.multisrc.spicytheme

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
class MangaDto(
    val name: String,
    val slug: String,
    @SerialName("urlImg") private val thumbnailUrl: String,
    @SerialName("sinopsis") private val synopsis: String? = null,
    val alternativeName: String? = null,
    @SerialName("actualizacionCap") val lastUpdate: String? = null,
    private val trending: TrendingDto? = null,
    private val state: StateDto? = null,
    @SerialName("genders") private val genres: List<GenreDto>? = null,
    @SerialName("lastChapters") val chapters: List<ChapterDto>? = null,
) {
    val views get() = trending?.views ?: 0

    fun toSManga() = SManga.create().apply {
        url = slug
        title = name
        thumbnail_url = thumbnailUrl
    }

    fun toSMangaDetails() = toSManga().apply {
        description = synopsis
        status = when (state?.name) {
            "En emision" -> SManga.ONGOING
            "En pausa" -> SManga.ON_HIATUS
            "Abandonado", "Cancelado" -> SManga.CANCELLED
            "Finalizado" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        genre = genres?.joinToString { it.gender.name }
        update_strategy = if (status == SManga.COMPLETED) UpdateStrategy.ONLY_FETCH_ONCE else UpdateStrategy.ALWAYS_UPDATE
    }
}

@Serializable
class TrendingDto(
    @SerialName("visitas") val views: Int,
)

@Serializable
class StateDto(
    @SerialName("estado") val name: String,
)

@Serializable
class GenreDto(
    val gender: GenderNameDto,
)

@Serializable
class GenderNameDto(
    val name: String,
)

@Serializable
class ChapterDto(
    private val num: String,
    private val slug: String,
    private val name: String? = null,
    @SerialName("created_at") private val createdAt: String,
) {
    fun toSChapter(mangaSlug: String) = SChapter.create().apply {
        url = "$mangaSlug/$slug"
        this.name = buildString {
            append("Capítulo ").append(num)
            if (!this@ChapterDto.name.isNullOrBlank()) append(" - ").append(this@ChapterDto.name)
        }
        date_upload = Instant.tryParse(createdAt)
        chapter_number = num.toFloatOrNull() ?: -1f
    }
}

@Serializable
class PagesDto(
    val pages: PageImagesDto,
)

@Serializable
class PageImagesDto(
    @SerialName("urlImg") val rawImages: String,
)
