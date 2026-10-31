package eu.kanade.tachiyomi.extension.id.softkomik

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class LibDataDto(
    val data: List<MangaDto>,
    val maxPage: Int,
    val page: Int,
)

@Serializable
class MangaDto(
    val gambar: String,
    val title: String,
    @SerialName("title_slug") val titleSlug: String,
    val status: String? = null,
    val type: String? = null,
)

@Serializable
class MangaDetailsDto(
    val gambar: String,
    val title: String,
    val author: String? = null,
    @SerialName("Genre") val genre: List<String>? = emptyList(),
    val sinopsis: String? = null,
    val status: String? = null,
    val type: String? = null,
)

@Serializable
class ChapterDto(
    val chapter: String,
)

@Serializable
class ChapterPageImagesDto(
    val imageSrc: List<String>,
)

@Serializable
class ChapterPageDataDto(
    @SerialName("_id") val id: String,
    val storageInter2: Boolean? = false,
    val backBS3: Boolean? = false,
)

@Serializable
class EncryptedPropsDto(
    @SerialName("_stk") val stk: String,
)

@Serializable
class MangaPropsDto(
    val data: MangaDetailsDto,
)

@Serializable
class ChapterPropsDto(
    val data: ChapterPropsDataDto,
    val imgList: List<String> = emptyList(),
)

@Serializable
class ChapterPropsDataDto(
    val data: ChapterPageDataDto,
)

@Serializable
class ChapterListDto(
    val chapter: List<ChapterDto>,
)

@Serializable
class SessionDto(
    val ex: Long,
    val sign: String,
    val token: String,
    val contentAccess: ContentAccessDto? = null,
)

@Serializable
class ContentAccessDto(
    val token: String,
    val sign: String,
)

@Serializable
class BearerTokenDto(
    val token: String,
    val ex: Long,
)
