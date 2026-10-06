package xyz.linplayer.app

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.ui.pages.sortFavoriteItems
import xyz.linplayer.app.ui.pages.favoriteGroups
import xyz.linplayer.app.data.View

/** 本地排序读取核心已有字段，升降序均将缺值放末尾，并保持相同值原序。 */
class FavoriteLocalSortTest {
    @Test fun groupsUseLibraryIdsHideEmptyLibrariesAndPreserveUnknownMembership() {
        val libraries = listOf(View("a", "同名库", null), View("b", "同名库", null), View("empty", "空库", null))
        val shared = Item("shared", "跨库收藏", "Series", libraryIds = listOf("a", "b"))
        val legacy = Item("legacy", "旧收藏", "Movie")
        val unknown = Item("unknown", "未知库收藏", "Series", libraryIds = listOf("missing"))
        val entries = listOf(shared, legacy, unknown)
        val groups = favoriteGroups(entries, libraries)
        assertEquals(listOf("a", "b", null, null), groups.map { it.libraryId })
        assertEquals(listOf(listOf("shared"), listOf("shared"), listOf("legacy"), listOf("unknown")),
            groups.map { it.items.map { item -> item.id } })
        assertEquals(listOf("同名库", "同名库", "收藏的电影", "收藏的剧"), groups.map { it.title })
        assertEquals(listOf("Movie", "Series"), favoriteGroups(entries, emptyList()).map { it.type })
        assertEquals(emptyList<Any>(), favoriteGroups(emptyList(), libraries))
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
