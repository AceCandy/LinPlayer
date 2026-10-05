package xyz.linplayer.app

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.ui.pages.sortFavoriteItems
import xyz.linplayer.app.ui.pages.favoriteCategory
import xyz.linplayer.app.data.View

/** 本地排序读取核心已有字段，升降序均将缺值放末尾，并保持相同值原序。 */
class FavoriteLocalSortTest {
    @Test fun libraryMembershipIsOptionalAndOnlyExplicitShortLibrariesClassifySeries() {
        val views = View.list(JsonArray(listOf(buildJsonObject {
            put("id", "short-library"); put("library_type", "hongguo")
        }, buildJsonObject {
            put("id", "ordinary-library"); put("name", "红果短剧"); put("collection_type", "tvshows")
        })))
        val short = views.filter { it.libraryType == "hongguo" }.map { it.id }.toSet()
        fun entry(libraries: List<String>?, type: String = "Series") = Item.from(buildJsonObject {
            put("id", "hg-group-example"); put("type_", type)
            put("library_ids", libraries?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
        })!!
        assertEquals("ShortDrama", favoriteCategory(entry(listOf("ordinary-library", "short-library")), short))
        for (libraries in listOf(null, emptyList(), listOf("ordinary-library"), listOf("unknown"))) {
            assertEquals("Series", favoriteCategory(entry(libraries), short))
        }
        assertEquals("Series", favoriteCategory(Item("hg-work-example", "红果短剧", "Series"), short))
        assertEquals("Series", favoriteCategory(entry(listOf("short-library")), emptySet()))
        assertEquals("Movie", favoriteCategory(entry(listOf("short-library"), "Movie"), short))
        assertEquals("Episode", favoriteCategory(entry(listOf("short-library"), "Episode"), short))
    }

    @Test fun sortUsesReturnedFieldsAndKeepsMissingValuesLast() {
        fun entry(id: String, first: Boolean) = Item.from(buildJsonObject {
            put("id", id); put("type_", "Movie")
            put("name", if (first) "Zulu" else "Alpha")
            put("sort_name", if (first) "Alpha" else "Zulu")
            put("date_updated", if (first) "2024-01-01T00:00:00Z" else "2022-01-01T00:00:00Z")
            put("year", if (first) 2020 else 2010)
            put("rating", if (first) 8.0 else 9.0)
        })!!
        val input = listOf(Item("missing", "", "Movie"), entry("a", true), entry("b", false), entry("tie", true))
        val asc = mapOf(
            "名称" to listOf("a", "tie", "b", "missing"),
            "评分" to listOf("a", "tie", "b", "missing"),
            "年份" to listOf("b", "a", "tie", "missing"),
            "更新时间" to listOf("b", "a", "tie", "missing"),
        )
        val desc = mapOf(
            "名称" to listOf("b", "a", "tie", "missing"),
            "评分" to listOf("b", "a", "tie", "missing"),
            "年份" to listOf("a", "tie", "b", "missing"),
            "更新时间" to listOf("a", "tie", "b", "missing"),
        )
        for ((sort, expected) in asc) assertEquals(expected, sortFavoriteItems(input, sort, true).map { it.id })
        for ((sort, expected) in desc) assertEquals(expected, sortFavoriteItems(input, sort, false).map { it.id })
        assertEquals(listOf("missing", "a", "b", "tie"), input.map { it.id })
    }
}
