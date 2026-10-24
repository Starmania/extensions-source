package eu.kanade.tachiyomi.extension.pt.onereader

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

@Serializable
internal class CatalogDto(
    private val works: List<WorkDto>,
    private val pagination: CatalogPaginationDto,
) {
    fun toMangasPage() = MangasPage(works.map(WorkDto::toSManga), pagination.page < pagination.totalPages)
}

@Serializable
internal class CatalogPaginationDto(
    val page: Int,
    val totalPages: Int,
)

@Serializable
internal class HomeUpdatesDto(
    private val items: List<WorkDto>,
    private val pagination: HomePaginationDto,
) {
    fun toMangasPage() = MangasPage(items.map(WorkDto::toSManga), pagination.page < pagination.pages)
}

@Serializable
internal class HomePaginationDto(
    val page: Int,
    val pages: Int,
)

@Serializable
internal class WorkDto(
    private val id: String,
    private val title: String,
    private val coverUrl: String? = null,
    private val status: String? = null,
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = id
        title = this@WorkDto.title
        thumbnail_url = coverUrl
        status = this@WorkDto.status.toStatus()
    }
}

@Serializable
internal class WorkDetailsDto(
    private val work: WorkDetailDto,
    private val chapters: List<ChapterDto>,
) {
    fun toSManga() = work.toSManga()

    // Locked chapters (premium or early access) cannot be opened without an account.
    fun toSChapters(): List<SChapter> = chapters.filterNot(ChapterDto::accessLocked).map { it.toSChapter(work.id) }
}

@Serializable
internal class WorkDetailDto(
    val id: String,
    private val title: String,
    private val originalName: String? = null,
    private val nativeTitle: String? = null,
    private val author: String? = null,
    private val artist: String? = null,
    private val synopsis: String? = null,
    private val status: String? = null,
    private val contentType: String? = null,
    private val originCountry: String? = null,
    private val releaseYear: Int? = null,
    private val genres: List<String> = emptyList(),
    private val coverUrl: String? = null,
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = id
        title = this@WorkDetailDto.title
        thumbnail_url = coverUrl
        author = this@WorkDetailDto.author?.takeIf(String::isNotBlank)
        artist = this@WorkDetailDto.artist?.takeIf(String::isNotBlank)
        genre = genres.takeIf(List<String>::isNotEmpty)?.joinToString()
        status = this@WorkDetailDto.status.toStatus()
        description = buildDescription()
        initialized = true
    }

    private fun buildDescription(): String? {
        val metadata = buildList {
            originalName?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
                ?.let { add("Título original: $it") }
            nativeTitle?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
                ?.let { add("Título nativo: $it") }
            contentType?.takeIf(String::isNotBlank)?.let { add("Tipo: $it") }
            originCountry?.takeIf(String::isNotBlank)?.let { add("País: $it") }
            releaseYear?.let { add("Ano: $it") }
        }

        return buildString {
            synopsis?.trim()?.takeIf(String::isNotBlank)?.let(::append)
            if (isNotEmpty() && metadata.isNotEmpty()) append("\n\n")
            append(metadata.joinToString("\n"))
        }.ifBlank { null }
    }
}

@Serializable
internal class ChapterDto(
    private val number: JsonPrimitive,
    private val title: String? = null,
    private val postedAt: String? = null,
    val accessLocked: Boolean = false,
) {
    fun toSChapter(workId: String): SChapter = SChapter.create().apply {
        val number = this@ChapterDto.number.content
        url = "$workId/$number"
        memo = buildJsonObject {
            put("id", workId)
            put("number", number)
        }
        name = buildString {
            append("Capítulo $number")
            title?.takeIf(String::isNotBlank)?.let { append(" - $it") }
        }
        chapter_number = number.toFloatOrNull() ?: -1F
        date_upload = chapterDateFormat.tryParseDateTime(postedAt, ZoneOffset.UTC)
    }
}

@Serializable
internal class ChapterManifestDto(
    private val protection: ProtectionDto,
    private val chapter: ManifestChapterDto,
) {
    fun toPages(baseUrl: HttpUrl): List<Page> {
        // The "ScanReader" transport wraps page keys with a per-browser ECDH key pair.
        if (protection.transport != null) throw Exception("Este capítulo usa uma proteção não suportada")

        return chapter.pages.mapIndexed { index, path ->
            Page(index, imageUrl = requireNotNull(baseUrl.resolve(path)).toString())
        }
    }
}

@Serializable
internal class ProtectionDto(
    val transport: JsonObject? = null,
)

@Serializable
internal class ManifestChapterDto(
    val pages: List<String>,
)

@Serializable
internal class MediaDto(
    val mode: String,
    val url: String,
    private val key: String,
    private val contentType: String,
) {
    fun key(): ByteArray = Base64.getUrlDecoder().decode(key)

    fun mediaType() = contentType.toMediaType()
}

@Serializable
internal class CatalogMetaDto(
    private val genres: Map<String, List<GenreDto>>,
) {
    fun toFilterData() = FilterData(genres["ALL"].orEmpty().map(GenreDto::name))
}

@Serializable
internal class GenreDto(
    val name: String,
)

private fun String?.toStatus(): Int = when (this?.trim()?.lowercase(Locale.ROOT)) {
    "em lançamento", "lançando" -> SManga.ONGOING
    "completo" -> SManga.COMPLETED
    "hiatus", "hiato" -> SManga.ON_HIATUS
    "cancelado" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

private val chapterDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
