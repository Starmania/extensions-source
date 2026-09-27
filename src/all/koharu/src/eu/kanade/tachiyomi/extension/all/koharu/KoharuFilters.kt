package eu.kanade.tachiyomi.extension.all.koharu

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

object KoharuFilters {
    fun getFilters(fetched: List<Tag>?): FilterList {
        val tags = fetched?.takeIf { it.isNotEmpty() }
        return FilterList(
            SortFilter("Sort by", getSortsList),
            CategoryFilter("Category"),
            Filter.Separator(),
            TagFilter("Tags", tags?.filterIsInstance<Genre>() ?: KoharuTags.genreList),
            TagFilter("Female Tags", tags?.filterIsInstance<Female>() ?: KoharuTags.femaleList),
            TagFilter("Male Tags", tags?.filterIsInstance<Male>() ?: KoharuTags.maleList),
            TagFilter("Artists", tags?.filterIsInstance<Artist>() ?: KoharuTags.artistList),
            TagFilter("Circles", tags?.filterIsInstance<Circle>() ?: KoharuTags.circleList),
            TagFilter("Parodies", tags?.filterIsInstance<Parody>() ?: KoharuTags.parodyList),
            TagFilter("Mixed", tags?.filterIsInstance<Mixed>() ?: KoharuTags.mixedList),
            TagFilter("Other", tags?.filterIsInstance<Other>() ?: KoharuTags.otherList),
            GenreConditionFilter("Include condition", tagsConditionIncludeFilterOptions, "i"),
            GenreConditionFilter("Exclude condition", tagsConditionExcludeFilterOptions, "e"),
            Filter.Separator(),
            Filter.Header("Separate tags with commas (,)"),
            Filter.Header("Prepend with dash (-) to exclude"),
            TextFilter("Magazines", "magazine"),
            TextFilter("Publishers", "publisher"),
            TextFilter("Characters", "character"),
            TextFilter("Cosplayers", "cosplayer"),
            Filter.Header("Filter by pages, for example: (>20)"),
            TextFilter("Pages", "pages"),
        )
    }

    internal open class TextFilter(name: String, val type: String) : Filter.Text(name)
    internal open class SortFilter(
        name: String,
        private val vals: List<Pair<String, String>>,
        state: Int = 0,
    ) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray(), state) {
        fun getValue() = vals[state].second
    }

    internal class CategoryFilter(name: String) :
        Filter.Group<CheckBoxFilter>(
            name,
            listOf(
                Pair("Manga", 2),
                Pair("Doujinshi", 4),
                Pair("Illustration", 8),
            ).map { CheckBoxFilter(it.first, it.second, true) },
        )

    internal open class CheckBoxFilter(name: String, val value: Int, state: Boolean) : Filter.CheckBox(name, state)

    private val getSortsList: List<Pair<String, String>> = listOf(
        Pair("Recently Posted", "4"),
        Pair("Title", "2"),
        Pair("Pages", "3"),
        Pair("Most Viewed", "8"),
        Pair("Most Favorited", "9"),
    )

    internal class GenreConditionFilter(
        title: String,
        options: List<Pair<String, String>>,
        val param: String,
    ) : UriPartFilter(
        title,
        options.toTypedArray(),
    )

    open class Tag(val id: Int, val name: String, val namespace: Int)
    class Genre(id: Int, name: String) : Tag(id, name, namespace = 0)
    class Artist(id: Int, name: String) : Tag(id, name, namespace = 1)
    class Circle(id: Int, name: String) : Tag(id, name, namespace = 2)
    class Parody(id: Int, name: String) : Tag(id, name, namespace = 3)
    class Male(id: Int, name: String) : Tag(id, name, namespace = 8)
    class Female(id: Int, name: String) : Tag(id, name, namespace = 9)
    class Mixed(id: Int, name: String) : Tag(id, name, namespace = 10)
    class Other(id: Int, name: String) : Tag(id, name, namespace = 12)

    internal class TagFilter(title: String, tags: List<Tag>) : Filter.Group<TagTriState>(title, tags.map { TagTriState(it.name, it.id) })

    internal class TagTriState(name: String, val id: Int) : Filter.TriState(name)

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
        state: Int = 0,
    ) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
        fun toUriPart() = vals[state].second
    }

    // https://api.schale.network/books?include=<id>,<id>&i=1&exclude=<id>,<id>&e=1
    private val tagsConditionIncludeFilterOptions: List<Pair<String, String>> =
        listOf(
            "AND" to "",
            "OR" to "1",
        )

    private val tagsConditionExcludeFilterOptions: List<Pair<String, String>> =
        listOf(
            "OR" to "",
            "AND" to "1",
        )
}
