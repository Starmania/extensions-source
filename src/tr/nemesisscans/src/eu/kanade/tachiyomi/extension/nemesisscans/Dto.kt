package eu.kanade.tachiyomi.extension.tr.nemesisscans

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import kotlin.time.Instant

@Serializable
class SeriesListDto(
    val data: List<SeriesDto>,
    val pagination: PaginationDto,
)

@Serializable
class PaginationDto(
    val page: Int,
    val pages: Int,
)

@Serializable
class LatestListDto(
    val data: List<LatestDto>,
)

@Serializable
class LatestDto(
    val series: SeriesDto,
)

@Serializable
class SeriesDetailDto(
    val series: SeriesDto,
)

@Serializable
class SeriesDto(
    @SerialName("_id") private val id: Int,
    private val title: TitleDto,
    private val poster: String? = null,
    private val description: String? = null,
    private val genres: List<String> = emptyList(),
    private val isFinished: Boolean = false,
) {
    fun toSManga() = SManga.create().apply {
        url = id.toString()
        title = this@SeriesDto.title.toString()
        thumbnail_url = poster
        description = this@SeriesDto.description
            ?.let { Jsoup.parseBodyFragment(it.replace(BR_REGEX, "\n")).wholeText().trim() }
        genre = genres.joinToString()
        status = if (isFinished) SManga.COMPLETED else SManga.ONGOING
    }
}

@Serializable
class TitleDto(
    private val english: String? = null,
    private val romaji: String? = null,
    private val native: String? = null,
) {
    // Same preference order as the site's own getTitle()
    override fun toString() = english ?: romaji ?: native!!
}

@Serializable
class EpisodeListDto(
    val episodes: List<EpisodeDto>,
)

@Serializable
class EpisodeDto(
    private val episodeNumber: Int,
    private val episodeName: String? = null,
    private val createdAt: String? = null,
) {
    fun toSChapter(seriesId: String) = SChapter.create().apply {
        url = "$seriesId/$episodeNumber"
        name = buildString {
            append("Bölüm ", episodeNumber)
            if (!episodeName.isNullOrBlank()) append(" - ", episodeName)
        }
        chapter_number = episodeNumber.toFloat()
        date_upload = Instant.tryParse(createdAt)
    }
}

@Serializable
class EpisodeDetailDto(
    val episode: EpisodeImagesDto,
)

@Serializable
class EpisodeImagesDto(
    val images: List<ImageDto>,
)

@Serializable
class ImageDto(
    val url: String,
    val order: Int,
)

private val BR_REGEX = Regex("""<br\s*/?>""")
