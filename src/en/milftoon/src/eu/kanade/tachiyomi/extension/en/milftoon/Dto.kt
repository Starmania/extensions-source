package eu.kanade.tachiyomi.extension.en.milftoon

import kotlinx.serialization.Serializable

@Serializable
class RouteDto<T>(val data: T)

@Serializable
class ListDto(
    val items: List<ListItemDto>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
)

@Serializable
class ListItemDto(
    val slug: String,
    val title: String,
    val coverPath: String,
    val author: String? = null,
)

@Serializable
class ReaderDto(
    val comic: ComicDto,
    val chapter: ChapterDto,
    val pages: List<PageDto>,
)

@Serializable
class ComicDto(
    val slug: String,
    val title: String,
    val coverPath: String,
)

@Serializable
class ChapterDto(
    val slug: String,
    val name: String,
    val position: Int,
    val publishedAt: String,
)

@Serializable
class PageDto(val src: String)
