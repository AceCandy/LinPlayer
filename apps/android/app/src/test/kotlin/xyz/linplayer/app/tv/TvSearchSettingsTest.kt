package xyz.linplayer.app.tv

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.*
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.str
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.UiPrefs

/** 真实 TV 根的双栏布局与遥控；图片使用合成夹具。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvSearchSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: AppState
    private lateinit var core: FakeCore
    private lateinit var back: OnBackPressedDispatcher
    private val oldTheme = UiPrefs.theme.value
    private val oldHistory = UiPrefs.searchHistory.value

    @After fun clean() {
        scope.cancel()
        if (::app.isInitialized) app.bg.cancel()
        PageCache.clear()
        UiPrefs.searchHistory.value = oldHistory
        ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences("lp_ui", 0)
            .edit().putString("search_history", org.json.JSONArray(oldHistory).toString()).apply()
        UiPrefs.setTheme(ApplicationProvider.getApplicationContext(), oldTheme)
    }

    private fun mount(theme: String, large: Boolean = false, client: ((FakeCore) -> CorePort)? = null, configure: (FakeCore) -> Unit = {}) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        UiPrefs.setTheme(context, theme)
        UiPrefs.searchHistory.value = listOf("沙丘", "星际穿越", "银翼杀手")
        PageCache.clear()
        FakeImages.install(context)
        core = FakeCore().loggedIn().movie().settings()
        core.ret("emby.search", page(*(1..20).map { item("m$it", "测试影片 $it", year = 2024) }.toTypedArray()))
        configure(core)
        app = AppState(client?.invoke(core) ?: core, scope)
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, if (large) 1.3f else density.fontScale)) { TvRoot(app) }
        }
        rule.waitForIdle()
    }

    @Test fun searchDarkHasSeparateResultsAndRemoteReturn() = search("dark")
    @Test fun searchLightLargeHasSeparateResultsAndRemoteReturn() = search("light", large = true)

    private fun search(theme: String, large: Boolean = false) {
        mount(theme, large)
        rule.onNodeWithTag("rail.0").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionCenter)
        rule.onNode(hasSetTextAction()).performTextInput("影片")
        rule.waitUntil(5000) { core.calls.any { it.first == "emby.search" } }
        rule.waitForIdle()
        val field = rule.onNodeWithTag("search.field").fetchSemanticsNode().boundsInRoot
        val results = rule.onNodeWithTag("search.results").fetchSemanticsNode().boundsInRoot
        assertTrue("结果应在搜索操作区右侧", results.left >= field.right)
        val first = rule.onNode(hasContentDescription("测试影片 1") and hasClickAction())
        val bounds = first.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("首排须给浮起焦点留出上方空间", bounds.top - results.top >= with(rule.density) { 20.dp.toPx() })
        assertTrue("海报宽度须按右栏计算", bounds.width <= with(rule.density) { 138.dp.toPx() } + 1f)
        rule.onNodeWithText("人物").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionRight)
        rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("search.results"))).assertExists()
        rule.onRoot().captureRoboImage("build/search-settings/search-$theme.png")
        press(rule, Key.DirectionCenter)
        rule.onNodeWithTag("detail.play").assertIsDisplayed()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("search.results"))).assertExists()
    }


    @Test fun searchEmptyFailureAndAggregateKeepControlsUsable() {
        mount("dark") { fake ->
            fake.on("emby.search") { args ->
                if (args.str("query") == "失败") throw CoreException("E_NETWORK", "测试连接失败", false)
                page()
            }
            fake.ret("source.aggregateSearch", arr())
        }
        rule.onNodeWithTag("rail.0").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionCenter)
        rule.onNode(hasSetTextAction()).performTextInput("空结果")
        rule.waitUntil(5000) { core.calls.any { it.first == "emby.search" } }
        rule.onNodeWithText("「空结果」没搜到东西").assertIsDisplayed()
        rule.onNodeWithText("人物").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionRight)
        rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("search.controls"))).assertExists()
        press(rule, Key.DirectionLeft)
        rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("search.controls"))).assertExists()
        rule.onNode(hasSetTextAction()).performTextReplacement("失败")
        rule.waitUntil(5000) { core.calls.any { it.first == "emby.search" && it.second.str("query") == "失败" } }
        rule.onNodeWithText("重试").assertIsDisplayed().performClick()
        rule.waitUntil(5000) { core.calls.count { it.first == "emby.search" && it.second.str("query") == "失败" } == 2 }
        rule.onNodeWithContentDescription("开启聚合搜索").performClick()
        rule.waitUntil(5000) { core.calls.any { it.first == "source.aggregateSearch" } }
        rule.onNodeWithContentDescription("关闭聚合搜索").assertIsSelected()
        rule.onNodeWithText("「失败」没搜到东西").assertIsDisplayed()
    }


    @Test fun aggregateResultsAreReachableAndCanReturnToSearchControls() {
        mount("dark", client = { fake -> object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                val result = fake.callJson(command, args, onPartial)
                if (command == "source.aggregateSearch") onPartial?.invoke(buildJsonObject {
                    put("server_id", "test-source"); put("server_name", "测试来源")
                    put("emby_items", arr(item("m1", "聚合影片"), item("m2", "第二部聚合影片")))
                })
                return result
            }
        } }) { it.ret("source.aggregateSearch", arr()) }
        rule.onNodeWithTag("rail.0").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionCenter)
        rule.onNode(hasSetTextAction()).performTextInput("影片")
        rule.onNodeWithContentDescription("开启聚合搜索").performClick()
        rule.waitUntil(5000) { core.calls.any { it.first == "source.aggregateSearch" } }
        rule.waitForIdle()
        rule.onNodeWithText("银翼杀手").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionRight)
        rule.onNode(hasContentDescription("聚合影片") and isFocused()).assertExists()
        press(rule, Key.DirectionRight)
        rule.onNode(hasContentDescription("第二部聚合影片") and isFocused()).assertExists()
        press(rule, Key.DirectionLeft)
        rule.onNode(hasContentDescription("聚合影片") and isFocused()).assertExists()
        rule.onRoot().captureRoboImage("build/search-settings/search-aggregate.png")
        press(rule, Key.DirectionLeft)
        rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("search.controls"))).assertExists()
    }

    @Test fun settingsRetainsDynamicPluginAndAllGroups() {
        mount("dark") { fake ->
            fake.ret("plugin.sidebar", arr(buildJsonObject {
                put("id", "test-plugin:entry"); put("page", "test-page"); put("title", "测试插件入口")
            }))
        }
        rule.onNodeWithTag("rail.7").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionCenter)
        rule.onNodeWithTag("settings.group.播放与外观").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        for ((group, labels) in listOf(
            "播放与外观" to listOf("外观", "播放器", "mpv 配置", "弹幕"),
            "手机遥控" to listOf("手机扫码遥控"), "网络" to listOf("多线程加载", "主进度服"),
            "插件页面" to listOf("测试插件入口"),
            "内容与数据" to listOf("已屏蔽的内容", "备份与还原", "文件浏览", "存储与数据目录"),
            "扩展与账号" to listOf("Trakt / Bangumi 账号", "插件", "扩展组件"), "应用" to listOf("更新", "关于"),
        )) {
            rule.onNodeWithTag("settings.group.$group").assertIsFocused()
            press(rule, Key.DirectionCenter)
            rule.onNodeWithTag("settings.group.$group").assertIsSelected().assertIsFocused()
            labels.forEach { rule.onNodeWithText(it).assertIsDisplayed().assertHasClickAction() }
            if (group != "应用") press(rule, Key.DirectionDown)
        }
        rule.onNodeWithText("截屏").assertDoesNotExist()
    }

    @Test fun settingsDarkGroupsKeepFocusAndRestoreEntry() = settings("dark")
    @Test fun settingsLightLargeGroupsKeepFocusAndRestoreEntry() = settings("light", large = true)

    private fun settings(theme: String, large: Boolean = false) {
        mount(theme, large)
        rule.onNodeWithTag("rail.7").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionCenter)
        val group = rule.onNodeWithTag("settings.group.播放与外观")
        group.assertIsSelected()
        val left = group.fetchSemanticsNode().boundsInRoot
        val right = rule.onNodeWithText("播放器").fetchSemanticsNode().boundsInRoot
        assertTrue("设置入口应位于分类右侧", right.left > left.right)
        group.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        press(rule, Key.DirectionDown, Key.DirectionDown, Key.DirectionCenter)
        rule.onNodeWithTag("settings.group.网络").assertIsFocused().assertIsSelected()
        press(rule, Key.DirectionRight)
        rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("settings.entries"))).assertExists()
        rule.onRoot().captureRoboImage("build/search-settings/settings-$theme.png")
        press(rule, Key.DirectionCenter)
        rule.onNodeWithTag("settings.entries").assertDoesNotExist()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("settings.group.网络").assertIsSelected()
        rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("settings.entries"))).assertExists()
    }
}
