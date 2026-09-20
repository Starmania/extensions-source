package eu.kanade.tachiyomi.extension.ko.wolfdotcom

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl
import java.net.URLEncoder

interface UrlPartFilter {
    fun addToUrl(url: HttpUrl.Builder)
}

open class QueryFilter(
    name: String,
    private val param: String,
    private val options: List<Pair<String, String>>,
    default: Int = 0,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray(), default),
    UrlPartFilter {
    // The site ignores UTF-8 values here; genres only match when sent as EUC-KR
    override fun addToUrl(url: HttpUrl.Builder) {
        url.addEncodedQueryParameter(param, URLEncoder.encode(options[state].second, "EUC-KR"))
    }
}

class SortFilter(default: Int = 0) :
    QueryFilter(
        "정렬 기준",
        "o",
        listOf("최신순" to "n", "인기순" to "f"),
        default,
    )

class StatusFilter : Filter.Select<String>("상태", arrayOf("연재", "완결")) {
    val path get() = if (state == 1) "end" else "ing"
}

class CategoryFilter :
    QueryFilter(
        "분류",
        "t2",
        listOf("전체" to "", "일반" to "1", "BL" to "2", "성인" to "3"),
    )

class DayFilter :
    QueryFilter(
        "요일",
        "t1",
        listOf(
            "전체" to "",
            "월" to "1",
            "화" to "2",
            "수" to "3",
            "목" to "4",
            "금" to "5",
            "토" to "6",
            "일" to "7",
            "10" to "10",
        ),
    )

class WebtoonGenreFilter :
    QueryFilter(
        "장르",
        "t3",
        genres(
            "드라마", "판타지", "액션", "로맨스", "일상", "개그", "미스터리", "순정",
            "스포츠", "스릴러", "무협", "학원", "공포", "스토리",
        ),
    )

class ComicGenreFilter :
    QueryFilter(
        "장르",
        "t3",
        genres(
            "액션", "판타지", "로맨스", "드라마", "이세계", "전생", "무협", "일상", "일상+치유",
            "순정", "러브코미디", "개그", "학원", "스포츠", "미스터리", "추리", "스릴러", "공포",
            "호러", "도박", "역사", "시대", "게임", "SF", "요리", "먹방", "음악", "라노벨",
            "애니화", "BL", "백합", "성인", "붕탁", "TS", "여장", "17",
        ),
    )

// The site's own links use lowercase for Latin genres (t3=sf) and "+" for the space in 일상+치유
private fun genres(vararg names: String) = listOf("전체" to "") + names.map { it to it.lowercase().replace('+', ' ') }

val POPULAR = FilterList(SortFilter(1))
val LATEST = FilterList(SortFilter(0))
