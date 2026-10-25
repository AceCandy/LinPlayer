package xyz.linplayer.app

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.SourceCategoryPage
import xyz.linplayer.app.ui.pages.SourceDetailPage
import xyz.linplayer.app.ui.pages.SourceHomePage
import xyz.linplayer.app.ui.theme.LpTheme

/** 数据源首页 / 分类 / 详情的截图基线与选中态。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneSourceUiTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun card(i: Int) = buildJsonObject {
        put("id", "v$i"); put("title", "片名 $i"); put("year", 2020 + i % 6)
        put("poster", buildJsonObject { put("url", "fake:poster-$i") })
        if (i % 3 == 0) put("remarks", "更新至 ${i}集")
    }

    private val category = buildJsonObject {
        put("id", "movie"); put("name", "电影")
        put("filters", buildJsonArray {
            add(buildJsonObject {
                put("key", "area"); put("name", "地区")
                put("options", buildJsonArray {
                    listOf("" to "全部", "cn" to "大陆", "us" to "欧美", "jp" to "日本").forEach { (v, n) ->
                        add(buildJsonObject { put("value", v); put("name", n) })
                    }
                })
            })
        })
    }

    private fun fake() = FakeCore().loggedIn().apply {
        ret("account.listAccounts", JsonArray(emptyList()))
        ret("source.continueWatching", JsonArray(emptyList()))
        ret("source.caps", buildJsonObject { put("home", true) })
        ret("source.home", buildJsonObject {
            put("categories", buildJsonArray {
                add(category)
                listOf("剧集", "动漫", "综艺").forEach { n -> add(buildJsonObject { put("id", n); put("name", n) }) }
            })
            put("recommended", JsonArray((1..6).map { card(it) }))
        })
        ret("source.category", buildJsonObject { put("items", JsonArray((1..6).map { card(it) })); put("next", "") })
        ret("source.detail", buildJsonObject {
            put("id", "v1"); put("title", "远方的故事"); put("year", 2024)
            put("genres", buildJsonArray { add("剧情"); add("悬疑") })
            put("overview", "一封迟到的信，开启一段跨越山海的旅程。")
            put("poster", buildJsonObject { put("url", "fake:poster-1") })
            put("lines", buildJsonArray {
                listOf("l1" to "线路一", "l2" to "线路二").forEach { (id, n) ->
                    add(buildJsonObject {
                        put("id", id); put("name", n)
                        put("episodes", JsonArray((1..10).map { e -> buildJsonObject { put("id", "$id-$e"); put("name", "第${e}集") } }))
                    })
                }
            })
        })
    }

    private fun open(core: FakeCore, start: Any, dark: Boolean) {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val app = AppState(core, scope)
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme(darkOverride = dark) {
                CompositionLocalProvider(LocalApp provides app) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = start) {
                        composable<Route.Home> { SourceHomePage(nav, "src-a", "测试源") }
                        composable<Route.SourceCategory> { SourceCategoryPage(nav, it) }
                        composable<Route.SourceDetail> { SourceDetailPage(nav, it) }
                        composable<Route.Player> { Text("播放") }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun homeLight() {
        open(fake(), Route.Home, dark = false)
        rule.onNodeWithText("推荐").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/source-ui/home-light.png")
    }

    @Test fun categoryFilterDark() {
        val core = fake()
        open(core, Route.SourceCategory("src-a", category.toString()), dark = true)
        rule.onNodeWithText("日本").performClick()
        rule.waitForIdle()
        assertEquals("[\"jp\"]", core.calls.last { it.first == "source.category" }.second?.get("filters")?.jsonObject?.get("area").toString())
        rule.onRoot().captureRoboImage("build/source-ui/category-dark.png")
    }

    @Test fun detailLinesLight() {
        open(fake(), Route.SourceDetail("src-a", "v1"), dark = false)
        rule.onNodeWithText("线路二 · 10").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/source-ui/detail-light.png")
    }
}
