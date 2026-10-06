package xyz.linplayer.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CorePort
import kotlinx.serialization.json.JsonElement
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.arr
import xyz.linplayer.app.tv.item
import xyz.linplayer.app.tv.page
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.AggregatePage
import xyz.linplayer.app.ui.pages.FavoritesPage
import xyz.linplayer.app.ui.pages.SearchPage
import xyz.linplayer.app.ui.theme.LpTheme

/** 浏览页的状态恢复与固定条件区回归，页面和导航均为真实 Compose。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneBrowseUiTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dark = mutableStateOf(false)
    private val serverName = "这是一台名字非常长的电影与剧集服务器"

    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun overview() = arr(buildJsonObject {
        put("server_id", "server-a"); put("server_name", serverName); put("active", true)
        put("counts", buildJsonObject { put("movie", 128); put("series", 42) })
        put("resume", arr(item("resume-1", "重逢", "Episode", series = "远方的故事",
            season = 1, episode = 12, runtime = 3600.0, resume = 1801.0)))
    })

    private fun open(core: CorePort, route: Any = Route.Aggregate, fontScale: Float = 1f) {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = route) {
                        composable<Route.Aggregate> { AggregatePage(nav) }
                        composable<Route.Favorites> { FavoritesPage(nav) }
                        composable<Route.FavoriteCategory> {
                            val route = it.toRoute<Route.FavoriteCategory>()
                            FavoritesPage(nav, route.type, route.libraryId, route.title)
                        }
                        composable<Route.Detail> { Text("详情目标：" + it.toRoute<Route.Detail>().itemId) }
                        composable<Route.Search> { SearchPage(nav, it) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun aggregateInitialFailureIsNotEmptyAndCanRetry() {
        val core = FakeCore().loggedIn()
        core.on("emby.aggregateOverview") { throw CoreException("E_NETWORK", "聚合读取失败", true) }
        open(core)
        rule.onNodeWithText("还没有添加服务器").assertDoesNotExist()
        rule.onNodeWithText("重试").assertIsDisplayed()
        core.ret("emby.aggregateOverview", overview())
        rule.onNodeWithText("重试").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(serverName).assertIsDisplayed()
        assertEquals(2, core.calls.count { it.first == "emby.aggregateOverview" })
    }

    @Test fun aggregateRefreshFailureKeepsExistingContent() {
        val core = FakeCore().loggedIn()
        core.ret("emby.aggregateOverview", overview())
        open(core)
        core.on("emby.aggregateOverview") { throw CoreException("E_NETWORK", "聚合刷新失败", true) }
        rule.onNodeWithContentDescription("刷新").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(serverName).assertIsDisplayed()
        rule.onNodeWithText("还没有添加服务器").assertDoesNotExist()
        rule.onNodeWithText("重试").assertIsDisplayed()
    }
    @Test fun aggregateShowsResumeAndCurrentServerInBothThemes() {
        val core = FakeCore().loggedIn()
        core.ret("emby.aggregateOverview", overview())
        open(core, fontScale = 1.3f)
        rule.onNodeWithText("当前").assertIsDisplayed()
        rule.onNodeWithText("电影 128  ·  剧集 42").assertIsDisplayed()
        rule.onNodeWithText("S1E12 · 重逢", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("剩余 29:59", useUnmergedTree = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/browse-ui/aggregate-light-large.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/browse-ui/aggregate-dark-large.png")
    }

    @Test fun aggregateServerTimeoutShowsErrorAlongsideHealthyServer() {
        val core = FakeCore().loggedIn()
        core.ret("emby.aggregateOverview", arr(overview()[0], buildJsonObject {
            put("server_id", "server-slow"); put("server_name", "慢服务器")
            put("counts", buildJsonObject { put("movie", 3) })
            put("resume", arr())
            put("error", "读取超时，请检查服务器连接后刷新")
        }))
        open(core)
        rule.onNodeWithText(serverName).assertIsDisplayed()
        rule.onNodeWithText("S1E12 · 重逢", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("慢服务器").assertIsDisplayed()
        rule.onNodeWithText("读取超时，请检查服务器连接后刷新", substring = true).assertIsDisplayed()
        rule.onNodeWithText("还没有添加服务器").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/browse-ui/aggregate-server-timeout.png")
    }

    @Test fun successfulEmptyOverviewStillOffersAddingServer() {
        val core = FakeCore().loggedIn()
        core.ret("emby.aggregateOverview", arr())
        open(core)
        rule.onNodeWithText("还没有添加服务器").assertIsDisplayed()
        rule.onNodeWithText("去添加服务器").assertIsDisplayed()
        rule.onNodeWithText("重试").assertDoesNotExist()
    }

    @Test fun favoritesOverviewHasNoSortAndKeepsSeparateRows() {
        val core = FakeCore().loggedIn()
        core.ret("emby.listFavorites", page(*(1..30).map {
            item("fav-$it", "收藏 $it", if (it <= 15) "Movie" else "Series",
                year = 2024, runtime = 3600.0, resume = 1000.0)
        }.toTypedArray()))
        open(core, Route.Favorites, fontScale = 1.3f)
        rule.onNodeWithTag("favorites.sort").assertDoesNotExist()
        rule.onNodeWithText("更新时间").assertDoesNotExist()
        rule.onNodeWithText("收藏的电影").assertIsDisplayed()
        rule.onNodeWithText("收藏的剧").assertIsDisplayed()
        rule.onNodeWithText("收藏的分集").assertDoesNotExist()
        rule.onNodeWithText("收藏的短剧").assertDoesNotExist()
        assertEquals(0, core.calls.count { it.first == "emby.views" })
        rule.onNodeWithText("其它收藏").assertDoesNotExist()
        val rows = rule.onAllNodes(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        rows.assertCountEquals(2)
        rows[0].performScrollToKey("fav-15")
        rule.onNodeWithText("收藏 15").assertIsDisplayed()
        rows[0].performScrollToIndex(0)
        rule.onRoot().captureRoboImage("build/browse-ui/favorites-light-large.png")
        rows[1].performScrollToKey("fav-30")
        rule.onNodeWithText("收藏 30").assertIsDisplayed()
        rule.onNodeWithText("评分").assertDoesNotExist()
        rule.onAllNodesWithText("剩余 ", substring = true).assertCountEquals(0)
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/browse-ui/favorites-dark-large.png")
    }

    @Test fun favoritesGroupByLibraryAndKeepLegacySeries() {
        val core = FakeCore().loggedIn()
        val short = JsonObject(item("short", "短剧收藏", "Series") +
            ("library_ids" to arr(JsonPrimitive("tv"), JsonPrimitive("short-lib"))))
        core.ret("emby.listFavorites", page(short, item("hg-group-legacy", "旧接口剧集", "Series")))
        core.ret("emby.views", arr(buildJsonObject {
            put("id", "short-lib"); put("name", "独立库"); put("library_type", "hongguo")
        }, buildJsonObject { put("id", "empty-lib"); put("name", "空媒体库") }))
        open(core, Route.Favorites)
        rule.onNodeWithText("空媒体库").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/browse-ui/favorite-library-overview-light.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/browse-ui/favorite-library-overview-dark.png")
        rule.onNodeWithText("独立库").assertIsDisplayed().performClick()
        rule.onNodeWithText("短剧收藏").assertIsDisplayed()
        rule.onNodeWithText("旧接口剧集").assertDoesNotExist()
        assertEquals(1, core.calls.count { it.first == "emby.views" })
        val before = core.calls.size
        rule.onNodeWithTag("favorites.sort").performClick()
        rule.onNodeWithText("名称").performClick()
        rule.waitForIdle()
        assertEquals("本地排序不得请求库或收藏", before, core.calls.size)
        rule.onRoot().captureRoboImage("build/browse-ui/favorite-short-light.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/browse-ui/favorite-short-dark.png")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("收藏的剧").performClick()
        rule.onNodeWithText("旧接口剧集").assertIsDisplayed()
        rule.onNodeWithText("短剧收藏").assertDoesNotExist()
    }

    @Test fun favoritesKeepSeriesWhenLibraryLookupFails() {
        val core = FakeCore().loggedIn()
        core.ret("emby.listFavorites", page(JsonObject(item("short", "不能丢失的剧", "Series") +
            ("library_ids" to arr(JsonPrimitive("short-lib"))))))
        core.on("emby.views") { throw CoreException("E_NETWORK", "库信息暂不可用", true) }
        open(core, Route.Favorites)
        rule.onNodeWithText("收藏的剧").assertIsDisplayed()
        rule.onNodeWithText("不能丢失的剧").assertIsDisplayed()
        rule.onNodeWithText("收藏的短剧").assertDoesNotExist()
        rule.onNodeWithText("库信息暂不可用").assertDoesNotExist()
    }

    @Test fun favoritesUseActualLibraryNameWithoutRequiringLibraryType() {
        val core = FakeCore().loggedIn()
        core.ret("emby.listFavorites", page(JsonObject(item("short", "归属已知类型未知", "Series") +
            ("library_ids" to arr(JsonPrimitive("short-lib"))))))
        core.ret("emby.views", arr(buildJsonObject {
            put("id", "short-lib"); put("name", "红果短剧"); put("collection_type", "tvshows")
        }))
        open(core, Route.Favorites)
        rule.onNodeWithText("收藏的剧").assertDoesNotExist()
        rule.onNodeWithText("红果短剧").assertIsDisplayed().performClick()
        rule.onNodeWithText("归属已知类型未知").assertIsDisplayed()
        rule.onNodeWithText("收藏的短剧").assertDoesNotExist()
    }

    @Test fun favoritesKeepEpisodesAndOtherTypesWithoutEmptyMovieSections() {
        val core = FakeCore().loggedIn()
        core.ret("emby.listFavorites", page(
            item("episode", "收藏单集", "Episode"), item("season", "收藏整季", "Season")))
        open(core, Route.Favorites)
        rule.onNodeWithText("收藏的电影").assertDoesNotExist()
        rule.onNodeWithText("收藏的剧").assertDoesNotExist()
        rule.onNodeWithText("收藏的分集").assertIsDisplayed()
        rule.onNodeWithText("其它收藏").assertIsDisplayed()
        rule.onNodeWithText("收藏单集").assertIsDisplayed()
        rule.onNodeWithText("收藏整季").assertIsDisplayed()
    }

    @Test fun favoriteTitlesOpenTheirOwnGridAndReturnToOverview() {
        val core = FakeCore().loggedIn()
        core.ret("emby.listFavorites", page(
            item("movie", "电影收藏"), item("series", "电视剧收藏", "Series")))
        open(core, Route.Favorites, fontScale = 1.3f)
        rule.onNodeWithText("收藏的电影").performClick()
        rule.onNodeWithContentDescription("返回").assertIsDisplayed()
        rule.onNodeWithText("电影收藏").assertIsDisplayed()
        rule.onNodeWithText("电视剧收藏").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/browse-ui/favorite-movies-light-large.png")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("收藏的剧").performClick()
        rule.onNodeWithText("电视剧收藏").assertIsDisplayed()
        rule.onNodeWithText("电影收藏").assertDoesNotExist()
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/browse-ui/favorite-series-dark-large.png")
        val beforeSort = core.calls.count { it.first == "emby.listFavorites" }
        rule.onNodeWithTag("favorites.sort").performClick()
        rule.onNodeWithText("评分").performClick()
        rule.waitForIdle()
        assertEquals(beforeSort, core.calls.count { it.first == "emby.listFavorites" })
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithTag("favorites.sort").assertDoesNotExist()
        rule.onNodeWithText("更新时间").assertDoesNotExist()
        rule.onNodeWithText("收藏的电影").assertIsDisplayed()
        rule.onNodeWithText("收藏的剧").performClick()
        rule.onNodeWithTag("favorites.sort").assertContentDescriptionEquals("排序：评分，降序")
        rule.onNodeWithContentDescription("电视剧收藏").performClick()
        rule.onNodeWithText("详情目标：series").assertIsDisplayed()
        assertEquals(0, core.calls.count { it.first == "emby.listItemsPage" })
    }

    @Test fun favoriteCategoryContinuesPastPagesWithoutMatchingItems() {
        val core = FakeCore().loggedIn()
        core.on("emby.listFavorites") { args ->
            if (args?.get("start_index").toString() == "0") buildJsonObject {
                put("items", arr(item("movie", "第一页电影")))
                put("next_index", 60); put("has_more", true)
            } else buildJsonObject {
                put("items", arr(item("series", "后续电视剧", "Series")))
                put("next_index", 61); put("has_more", false)
            }
        }
        open(core, Route.FavoriteCategory("Series"))
        rule.waitForIdle()
        assertEquals("60", core.calls.last { it.first == "emby.listFavorites" }.second?.get("start_index").toString())
        rule.onNodeWithText("后续电视剧").assertIsDisplayed()
        rule.onNodeWithText("第一页电影").assertDoesNotExist()
        rule.onNodeWithText("还没有收藏任何内容").assertDoesNotExist()
    }

    @Test fun favoriteLibraryContinuesPastUnmatchedPagesAndSortsLocally() {
        val core = FakeCore().loggedIn()
        fun member(id: String, library: String) = JsonObject(item(id, id, "Series") +
            ("library_ids" to arr(JsonPrimitive(library))))
        core.on("emby.listFavorites") { args -> buildJsonObject {
            val first = args?.get("start_index").toString() == "0"
            put("items", if (first) arr(member("其它库收藏", "other")) else arr(member("目标库收藏", "target")))
            put("next_index", if (first) 60 else 61); put("has_more", first)
        } }
        core.ret("emby.views", arr(buildJsonObject { put("id", "target"); put("name", "我的剧集库") }))
        open(core, Route.FavoriteCategory(libraryId = "target", title = "我的剧集库"))
        rule.onNodeWithText("我的剧集库").assertIsDisplayed()
        rule.onNodeWithText("目标库收藏").assertIsDisplayed()
        rule.onNodeWithText("其它库收藏").assertDoesNotExist()
        assertEquals(listOf("0", "60"), core.calls.filter { it.first == "emby.listFavorites" }
            .map { it.second?.get("start_index").toString() })
        val before = core.calls.size
        rule.onNodeWithTag("favorites.sort").performClick()
        rule.onNodeWithText("名称").performClick()
        rule.waitForIdle()
        assertEquals(before, core.calls.size)
    }

    @Test fun favoriteCategoryEmptyStateOnlyDescribesItsOwnType() {
        val core = FakeCore().loggedIn()
        core.ret("emby.listFavorites", page(item("movie", "收藏电影")))
        open(core, Route.FavoriteCategory("Series"))
        rule.onNodeWithText("还没有收藏电视剧").assertIsDisplayed()
        rule.onNodeWithText("还没有收藏任何内容").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w320dp-h873dp-mdpi")
    fun favoriteCategoryUsesLibraryGridAndLocalSortHeader() {
        val core = FakeCore().loggedIn()
        core.ret("emby.listFavorites", page(*(1..30).map {
            item("movie-$it", "电影 $it", rating = it.toDouble())
        }.toTypedArray()))
        open(core, Route.FavoriteCategory("Movie"), fontScale = 1.3f)
        val title = rule.onNodeWithText("收藏的电影").fetchSemanticsNode().boundsInRoot
        val sort = rule.onNodeWithTag("favorites.sort").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(sort.left >= title.right && sort.center.y <= title.bottom + 12f)
        val sortLabel = rule.onNodeWithText("更新时间", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("排序内容与标题应垂直居中", title.center.y, sortLabel.center.y, 1f)
        val first = rule.onNodeWithText("电影 1").fetchSemanticsNode().boundsInRoot
        val third = rule.onNodeWithText("电影 3").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, third.top, .1f)
        val before = core.calls.count { it.first == "emby.listFavorites" }
        rule.onNodeWithTag("favorites.sort").performClick()
        rule.onNodeWithText("评分").performClick()
        rule.waitForIdle()
        assertEquals(before, core.calls.count { it.first == "emby.listFavorites" })
        rule.onNodeWithText("电影 30").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/browse-ui/favorite-local-sort-compact.png")
        rule.onNodeWithTag("favorites.sort").performClick()
        rule.onNodeWithText("升序").performClick()
        rule.waitForIdle()
        assertEquals(before, core.calls.count { it.first == "emby.listFavorites" })
        rule.onNodeWithText("电影 1").assertIsDisplayed()
        rule.onNodeWithTag("favorites.sort").assertContentDescriptionEquals("排序：评分，升序")
    }

    @Test fun favoriteLocalSortReordersAppendedPagesWithoutChangingTheCursor() {
        val core = FakeCore().loggedIn()
        core.on("emby.listFavorites") { args ->
            buildJsonObject {
                if (args?.get("start_index").toString() == "0") {
                    put("items", arr(item("low", "低分电影", rating = 2.0)))
                    put("next_index", 60); put("has_more", true)
                } else {
                    put("items", arr(item("high", "高分电影", rating = 9.0)))
                    put("next_index", 61); put("has_more", false)
                }
            }
        }
        open(core, Route.FavoriteCategory("Movie"))
        rule.onNodeWithTag("favorites.sort").performClick()
        rule.onNodeWithText("评分").performClick()
        rule.waitForIdle()
        assertEquals(1, core.calls.count { it.first == "emby.listFavorites" })
        rule.onNodeWithText("加载更多").performClick()
        rule.waitForIdle()
        val request = core.calls.last { it.first == "emby.listFavorites" }.second
        assertEquals("60", request?.get("start_index").toString())
        assertEquals("更新时间", request.str("sort"))
        val high = rule.onNodeWithText("高分电影").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val low = rule.onNodeWithText("低分电影").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("后续页应合并进当前评分降序", high.left < low.left)
        rule.onNodeWithTag("favorites.sort").performClick()
        rule.onNodeWithText("升序").performClick()
        rule.waitForIdle()
        assertEquals(2, core.calls.count { it.first == "emby.listFavorites" })
        assertTrue(rule.onNodeWithText("低分电影").fetchSemanticsNode().boundsInRoot.left <
            rule.onNodeWithText("高分电影").fetchSemanticsNode().boundsInRoot.left)
    }

    @Test fun favoriteCategoryCanRetryNextPageWithoutLosingExistingItems() {
        val core = FakeCore().loggedIn()
        core.on("emby.listFavorites") { args ->
            if (args?.get("start_index").toString() != "0") throw CoreException("E_NETWORK", "加载失败", true)
            buildJsonObject {
                put("items", arr(item("series-1", "第一页剧", "Series")))
                put("next_index", 60); put("has_more", true)
            }
        }
        open(core, Route.FavoriteCategory("Series"))
        rule.onNodeWithText("第一页剧").assertIsDisplayed()
        rule.onNodeWithText("加载更多").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("重试加载").assertIsDisplayed()
        assertEquals(2, core.calls.count { it.first == "emby.listFavorites" })
        core.ret("emby.listFavorites", buildJsonObject {
            put("items", arr(item("movie", "不应混入电影"), item("series-2", "第二页剧", "Series")))
            put("next_index", 62); put("has_more", false)
        })
        rule.onNodeWithText("重试加载").performClick()
        rule.waitForIdle()
        assertEquals("60", core.calls.last { it.first == "emby.listFavorites" }.second?.get("start_index").toString())
        rule.onNodeWithText("第一页剧").assertIsDisplayed()
        rule.onNodeWithText("第二页剧").assertIsDisplayed()
        rule.onNodeWithText("不应混入电影").assertDoesNotExist()
        rule.onNodeWithText("重试加载").assertDoesNotExist()
    }

    @Test fun favoritesAppendPagesToTheirSectionsOnlyWhenRequested() {
        val core = FakeCore().loggedIn()
        core.on("emby.listFavorites") { args ->
            if (args?.get("start_index").toString() == "0") buildJsonObject {
                put("items", arr(item("movie", "第一页电影")))
                put("next_index", 1); put("has_more", true)
            } else buildJsonObject {
                put("items", arr(item("series", "第二页电视剧", "Series")))
                put("next_index", 2); put("has_more", false)
            }
        }
        open(core, Route.Favorites)
        assertEquals(1, core.calls.count { it.first == "emby.listFavorites" })
        rule.onNodeWithText("收藏的剧").assertDoesNotExist()
        rule.onNodeWithText("加载更多").performClick()
        rule.waitForIdle()
        assertEquals("1", core.calls.last { it.first == "emby.listFavorites" }.second?.get("start_index").toString())
        rule.onNodeWithText("第一页电影").assertIsDisplayed()
        rule.onNodeWithText("第二页电视剧").assertIsDisplayed()
        rule.onNodeWithText("加载更多").assertDoesNotExist()
    }

    private fun pullDown() {
        rule.onRoot().performTouchInput {
            swipeDown(startY = height * .25f, endY = height * .85f, durationMillis = 600)
        }
        rule.waitForIdle()
    }

    @Test fun favoritesPullRefreshKeepsContentOnFailureAndCanRetry() {
        val core = FakeCore().loggedIn()
        core.ret("emby.listFavorites", page(item("old", "原收藏")))
        open(core, Route.Favorites)
        core.on("emby.listFavorites") { throw CoreException("E_NETWORK", "刷新失败", true) }
        pullDown()
        assertEquals(2, core.calls.count { it.first == "emby.listFavorites" })
        rule.onNodeWithText("原收藏").assertIsDisplayed()
        core.ret("emby.listFavorites", page(item("fresh", "刷新后的收藏")))
        pullDown()
        rule.onNodeWithText("刷新后的收藏").assertIsDisplayed()
        rule.onNodeWithText("原收藏").assertDoesNotExist()
        assertEquals(3, core.calls.count { it.first == "emby.listFavorites" })
        assertTrue(core.calls.filter { it.first == "emby.listFavorites" }.all { it.second?.get("start_index").toString() == "0" })
    }

    @Test fun favoriteEmptyCategoryCanPullRefresh() {
        val core = FakeCore().loggedIn()
        core.ret("emby.listFavorites", page())
        open(core, Route.FavoriteCategory("Movie"))
        core.ret("emby.listFavorites", page(item("fresh", "新增电影收藏")))
        pullDown()
        rule.onNodeWithText("新增电影收藏").assertIsDisplayed()
        assertEquals(2, core.calls.count { it.first == "emby.listFavorites" })
    }

    @Test fun searchCanPullRefreshEmptyResultsAndKeepScope() {
        val core = FakeCore().loggedIn()
        core.ret("emby.search", page())
        open(core, Route.Search(viewId = "movie-lib", q = "刷新词"))
        rule.waitUntil(5000) { core.calls.any { it.first == "emby.search" } }
        rule.waitForIdle()
        core.on("emby.search") { throw CoreException("E_NETWORK", "搜索刷新失败", true) }
        pullDown()
        rule.waitUntil(5000) { core.calls.count { it.first == "emby.search" } == 2 }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/browse-ui/search-refresh-error.png")
        rule.onNodeWithText("搜索刷新失败", substring = true).assertIsDisplayed()
        core.ret("emby.search", page(item("fresh", "刷新搜索结果")))
        pullDown()
        rule.waitUntil(5000) { core.calls.count { it.first == "emby.search" } == 3 }
        rule.waitForIdle()
        rule.onNodeWithText("刷新搜索结果").assertIsDisplayed()
        assertEquals("movie-lib", core.calls.last { it.first == "emby.search" }.second.str("parent_id"))
        assertEquals("刷新词", core.calls.last { it.first == "emby.search" }.second.str("query"))
    }

    @Test fun aggregateSearchCanPullRefreshAfterManualSearch() {
        val core = FakeCore().loggedIn()
        core.ret("emby.search", page())
        core.ret("source.aggregateSearch", arr())
        open(core, Route.Search(q = "聚合词"))
        rule.waitUntil(5000) { core.calls.any { it.first == "emby.search" } }
        rule.onNodeWithContentDescription("开启聚合搜索").performClick()
        rule.waitUntil(5000) { core.calls.count { it.first == "source.aggregateSearch" } == 1 }
        rule.waitForIdle()
        core.on("source.aggregateSearch") { throw CoreException("E_NETWORK", "聚合刷新失败", true) }
        pullDown()
        rule.waitUntil(5000) { core.calls.count { it.first == "source.aggregateSearch" } == 2 }
        rule.waitForIdle()
        rule.onNodeWithText("聚合刷新失败", substring = true).assertIsDisplayed()
        rule.onNodeWithText("没搜到东西", substring = true).assertDoesNotExist()
        core.ret("source.aggregateSearch", arr())
        pullDown()
        rule.waitUntil(5000) { core.calls.count { it.first == "source.aggregateSearch" } == 3 }
        assertEquals(1, core.calls.count { it.first == "emby.search" })
    }

    @Test fun searchInlineAggregateToggleSearchesImmediatelyWithoutExtraToolbarOrButton() {
        val core = FakeCore().loggedIn()
        core.ret("emby.search", page(*(1..12).map {
            item("search-$it", "结果 $it", year = 2024, rating = if (it == 1) 8.6 else null)
        }.toTypedArray()))
        core.ret("source.aggregateSearch", arr())
        open(core, Route.Search(q = "故事"), fontScale = 1.3f)
        rule.waitUntil(5000) { core.calls.any { it.first == "emby.search" } }
        rule.waitForIdle()
        rule.onNodeWithText("包括集").assertDoesNotExist()
        rule.onNodeWithText("聚合(含数据源)").assertDoesNotExist()
        rule.onNodeWithText("结果 1").assertIsDisplayed()
        rule.onNodeWithContentDescription("返回").assertDoesNotExist()
        rule.onNodeWithText("搜索").assertDoesNotExist()
        val field = rule.onNodeWithTag("search.field").fetchSemanticsNode().boundsInRoot
        val icon = rule.onNodeWithTag("search.aggregate").fetchSemanticsNode().boundsInRoot
        val results = rule.onNodeWithTag("search.results").fetchSemanticsNode().boundsInRoot
        assertTrue("聚合图标须在输入框内", icon.left >= field.left && icon.right <= field.right && icon.top >= field.top && icon.bottom <= field.bottom)
        assertTrue("结果和输入框之间留出间距", results.top >= field.bottom + 16f)
        rule.onRoot().captureRoboImage("build/browse-ui/search-light-large.png")
        assertEquals("[\"Series\",\"Movie\"]", core.calls.last { it.first == "emby.search" }.second?.get("types").toString())
        rule.onNodeWithContentDescription("开启聚合搜索").performClick()
        rule.onNodeWithContentDescription("关闭聚合搜索").assertIsSelected()
        rule.waitForIdle()
        rule.onNode(hasText("搜索") and hasClickAction()).assertDoesNotExist()
        rule.waitUntil(5000) { core.calls.any { it.first == "source.aggregateSearch" } }
        assertEquals("故事", core.calls.last { it.first == "source.aggregateSearch" }.second.str("query"))
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitUntil(5000) { core.calls.count { it.first == "source.aggregateSearch" } == 2 }
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/browse-ui/search-dark-large.png")
    }

    @Test fun aggregateResultsShowOnlyServerNameAndHorizontalPosters() {
        val core = FakeCore().loggedIn()
        core.ret("emby.search", page(item("local", "本服电影")))
        core.ret("source.aggregateSearch", arr())
        val client = object : CorePort by core {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = core.callJson(command, args, onPartial)
                if (command == "source.aggregateSearch") onPartial?.invoke(buildJsonObject {
                    put("server_id", "server-a"); put("server_name", "我的媒体服务器")
                    put("warning", "服务端不支持标识查询,名称搜索可能遗漏不同译名的条目")
                    put("emby_items", arr(*(1..50).map { item("agg-$it", "横滑结果 $it") }.toTypedArray()))
                })
                return result
            }
        }
        open(client, Route.Search(q = "故事"), fontScale = 1.3f)
        rule.waitUntil(5000) { core.calls.any { it.first == "emby.search" } }
        rule.onNodeWithContentDescription("开启聚合搜索").performClick()
        rule.waitUntil(5000) { core.calls.any { it.first == "source.aggregateSearch" } }
        rule.waitForIdle()
        rule.onNodeWithText("我的媒体服务器").assertIsDisplayed()
        rule.onNodeWithText("最多", substring = true).assertDoesNotExist()
        rule.onNodeWithText("服务端不支持标识查询", substring = true).assertDoesNotExist()
        rule.onNode(hasText("搜索") and hasClickAction()).assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/browse-ui/search-aggregate-clean-light.png")
        rule.runOnIdle { dark.value = true }
        rule.onRoot().captureRoboImage("build/browse-ui/search-aggregate-clean-dark.png")
        rule.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .performScrollToIndex(49)
        rule.onNodeWithText("横滑结果 50").assertIsDisplayed()
    }

    @Test fun librarySearchNeverOffersCrossSourceToggle() {
        val core = FakeCore().loggedIn()
        core.ret("emby.search", page(item("scoped", "库内条目")))
        open(core, Route.Search(viewId = "lib-movie", q = "条目"))
        rule.waitUntil(5000) { core.calls.any { it.first == "emby.search" } }
        rule.onNodeWithText("聚合(含数据源)").assertDoesNotExist()
        rule.onNodeWithContentDescription("开启聚合搜索").assertDoesNotExist()
        assertEquals("lib-movie", core.calls.last { it.first == "emby.search" }.second.str("parent_id"))
    }

}
