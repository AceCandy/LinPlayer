package xyz.linplayer.app

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.*
import xyz.linplayer.app.tv.*
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.theme.LpTheme
import java.io.File
import java.net.URLDecoder

/** 正式页面消费跨端同一份资料与图片；合成数据不代表真实媒体播放验收。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class VisualAlignmentTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: AppState? = null
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val oldImageLoader = SingletonImageLoader.get(context)
    private val oldTheme = UiPrefs.theme.value
    private val oldColor = UiPrefs.colorScheme.value
    private val oldHideLibraries = UiPrefs.hideHomeLibraries.value
    private val oldTouchMode = org.robolectric.shadows.ShadowWindowManagerGlobal.getInTouchMode()
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "tools/visual-fixtures/catalog.json").exists() }
    private val fixture = File(root, "tools/visual-fixtures")
    private val data = Json.parseToJsonElement(File(fixture, "catalog.json").readText()).jsonObject
    private val items = data.getValue("items").jsonArray
    private val details = (items + data.getValue("details").jsonArray).associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
    private val fetched = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @After fun clean() {
        scope.cancel(); app?.bg?.cancel(); PageCache.clear()
        UiPrefs.setTheme(context, oldTheme); UiPrefs.setColorScheme(context, oldColor)
        UiPrefs.setHideHomeLibraries(context, oldHideLibraries)
        SingletonImageLoader.setUnsafe(oldImageLoader)
        xyz.linplayer.app.tv.kit.TvC.sharedColors = null
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(oldTouchMode)
    }

    @Test fun phoneDark() = capture("phone", false)
    @Test fun phoneLight() = capture("phone", true)
    @Test @Config(qualifiers = "w960dp-h540dp-mdpi") fun tvDark() = capture("tv", false)
    @Test @Config(qualifiers = "w960dp-h540dp-mdpi") fun tvLight() = capture("tv", true)
    @Test @Config(qualifiers = "w1024dp-h768dp-mdpi") fun tabletDark() = capture("tablet", false)
    @Test @Config(qualifiers = "w960dp-h540dp-land-mdpi") fun phonePlayerDark() = capturePlayer(false, false)
    @Test @Config(qualifiers = "w960dp-h540dp-land-mdpi") fun phonePlayerLight() = capturePlayer(false, true)
    @Test @Config(qualifiers = "w960dp-h540dp-land-mdpi") fun tvPlayerDark() = capturePlayer(true, false)
    @Test @Config(qualifiers = "w960dp-h540dp-land-mdpi") fun tvPlayerLight() = capturePlayer(true, true)

    @Test @Config(qualifiers = "w960dp-h540dp-land-mdpi") fun tvPlayerFailureLight() {
        UiPrefs.setTheme(context, "light")
        val state = AppState(FakeCore().loggedIn().settings(), scope).also { app = it }
        runBlocking { state.boot() }
        var retries = 0
        rule.setContent {
            TvFrame(state) {
                PlayerFailure("合成播放错误", false, { retries++ })
            }
        }
        rule.onNodeWithText("这一片没能播起来").assertIsDisplayed()
        rule.onNodeWithText("重试").assertIsFocused().performKeyInput {
            pressKey(androidx.compose.ui.input.key.Key.DirectionCenter)
        }
        rule.runOnIdle { assertEquals(1, retries) }
        assertEquals(xyz.linplayer.app.tv.kit.TvC.bg, rule.onRoot().captureToImage().toPixelMap()[0, 0])
        val output = File(root, "build/visual-alignment/tv").apply { mkdirs() }
        rule.onRoot().captureRoboImage(File(output, "light-player-failure.png").path)
    }

    private fun capturePlayer(tv: Boolean, light: Boolean) {
        UiPrefs.setTheme(context, if (light) "light" else "dark")
        UiPrefs.setColorScheme(context, "amber")
        val state = AppState(FakeCore().loggedIn().settings(), scope).also { app = it }
        runBlocking { state.boot() }
        val still = BitmapFactory.decodeFile(File(fixture, "visual-1-Backdrop.png").path).asImageBitmap()
        val nav = TvNav().apply { push(TvRoute.Player("visual-1", "远岸来信")) }
        val ui = PlayerUi().apply {
            title = "远岸来信"; position = 1517.0; duration = 2729.0; buffered = 2050.0
            buffering = false; everMoved = true
        }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            if (tv) TvFrame(state) {
                CompositionLocalProvider(LocalNav provides nav, LocalFocusMemory provides nav.top.memory) {
                    Box(Modifier.fillMaxSize()) {
                        Image(still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        Osd(ui, TvRoute.Player("visual-1", "远岸来信"), "mpv",
                            playbackFocus = androidx.compose.ui.focus.FocusRequester(), onSeek = {}, onPause = {}, onNext = {}, onPanel = {}, nextUpVisible = false)
                    }
                }
            } else LpTheme(darkOverride = !light) {
                Box(Modifier.fillMaxSize()) {
                    Image(still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    xyz.linplayer.app.ui.player.Osd(
                        portrait = false, netSpeed = "", title = "远岸来信",
                        position = 1517.0, duration = 2729.0, paused = false, speed = 1.0,
                        heat = emptyList(), buffered = 2050.0,
                        onBack = {}, onToggle = {}, onSeek = {}, onSpeed = {},
                        onLock = {}, onShot = {}, onPanel = {})
                }
            }
        }
        repeat(20) { rule.mainClock.advanceTimeBy(80); rule.waitForIdle() }
        rule.onNodeWithText("25:17").assertIsDisplayed()
        if (tv) rule.onNodeWithText("45:29").assertIsDisplayed()
        val output = File(root, "build/visual-alignment/${if (tv) "tv" else "phone-landscape"}").apply { mkdirs() }
        rule.onRoot().captureRoboImage(File(output, "${if (light) "light" else "dark"}-player.png").path)
    }

    private fun capture(platform: String, light: Boolean) {
        PageCache.clear()
        if (platform == "tv") androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        UiPrefs.setTheme(context, if (light) "light" else "dark")
        UiPrefs.setColorScheme(context, "amber")
        UiPrefs.setHideHomeLibraries(context, false)
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<coil3.Uri> {
                override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher =
                    Fetcher {
                        val url = URLDecoder.decode(data.toString(), "UTF-8")
                        val item = details.entries.firstOrNull { (id, _) -> "/$id/" in url }?.value?.jsonObject
                        val id = item?.get("art_id")?.jsonPrimitive?.content
                            ?: item?.get("id")?.jsonPrimitive?.content ?: "visual-1"
                        if (id == "visual-4") throw java.io.FileNotFoundException("缺图夹具")
                        val kind = if ("Backdrop" in url) "Backdrop" else "Primary"
                        val image = BitmapFactory.decodeFile(File(fixture, "$id-$kind.png").path)
                            ?: error("无法读取视觉图片")
                        fetched.add("$id-$kind")
                        ImageFetchResult(image.asImage(), false, DataSource.MEMORY)
                    }
            })
        }.coroutineContext(Dispatchers.Unconfined).build())
        fun selected(key: String) = JsonArray(data.getValue(key).jsonArray.map { id ->
            details.getValue(id.jsonPrimitive.content)
        })
        val core = FakeCore().loggedIn().settings().apply {
            ret("account.listAccounts", arr(account("http://emby-a.invalid", "视觉样本库", "", true)))
            ret("emby.views", data.getValue("libraries"))
            ret("emby.listResume", selected("resume"))
            ret("emby.listLatest", selected("latest"))
            ret("emby.cachedItems", selected("hero"))
            ret("emby.heroCandidates", data.getValue("hero"))
            ret("emby.listCollections", arr())
            on("emby.itemDetail") { request -> details.getValue(request.str("item_id")!!) }
            ret("emby.itemMedia", data.getValue("media"))
            ret("prefs.preloadItem", data.getValue("media"))
            ret("emby.seriesSeasons", selected("seasons"))
            on("emby.seasonEpisodes") { request -> buildJsonObject {
                put("items", if ((request.long("start_index") ?: 0) == 0L) selected("episodes") else arr())
                put("total", 3)
            } }
            ret("emby.aggregateVersions", arr())
            ret("emby.similarItems", JsonArray(items.drop(1)))
            ret("emby.listItemsPage", buildJsonObject { put("items", items); put("total", items.size); put("has_more", false) })
            ret("emby.getFilters", data.getValue("filters"))
            ret("emby.blockedList", arr())
        }
        val state = AppState(core, scope).also { app = it }
        runBlocking { state.boot() }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            if (platform == "tv") TvRoot(state)
            else LpTheme(darkOverride = !light) {
                CompositionLocalProvider(LocalApp provides state) { PhoneRoot(state) }
            }
        }
        val output = File(root, "build/visual-alignment/$platform").apply { mkdirs() }
        val mode = if (light) "light" else "dark"
        fun settle() {
            repeat(20) { rule.mainClock.advanceTimeBy(80); rule.waitForIdle(); Thread.sleep(10) }
        }
        fun shot(name: String, expected: String, node: SemanticsNodeInteraction = rule.onRoot()) {
            settle()
            assertTrue("$platform/$name 未完成内容加载", rule.onAllNodesWithText(expected, substring = true).fetchSemanticsNodes().isNotEmpty())
            node.captureRoboImage(File(output, "$mode-$name.png").path)
        }
        shot("home", "远岸来信")
        assertTrue("首页背景未加载，不能接受空白截图", fetched.contains("visual-1-Backdrop"))
        if (light) {
            val title = rule.onNodeWithText("继续观看").fetchSemanticsNode().boundsInRoot
            val background = rule.onRoot().captureToImage().toPixelMap()[
                title.left.toInt() - 2, title.center.y.toInt()].luminance()
            // 头图延伸到续播图片中点，压在背景上的栏目标题按叠图规则用白字。
            val foreground = androidx.compose.ui.graphics.Color.White.luminance()
            val contrast = (maxOf(background, foreground) + .05f) / (minOf(background, foreground) + .05f)
            assertTrue("浅色首页栏目标题与实际背景的对比度不足: $contrast", contrast >= 4.5f)
        }
        if (platform == "tv") {
            val home = rule.onNodeWithTag("rail.1")
            val favorites = rule.onNodeWithTag("rail.3")
            home.assertIsSelected().assertIsFocused().performKeyInput {
                pressKey(androidx.compose.ui.input.key.Key.DirectionRight)
            }
            settle()
            favorites.assertIsFocused().assertIsSelected()
            home.assertIsNotSelected().assertIsNotFocused()
            settle()
            rule.onRoot().captureRoboImage(File(output, "$mode-home-nav-focus.png").path)
            favorites.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionLeft) }
            settle()
            home.assertIsFocused().assertIsSelected()
            home.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
            settle()
            rule.onNode(isFocused() and hasAnyAncestor(hasTestTag("home.banners"))).assertDoesNotExist()
            shot("home-content-focus", "远岸来信")
            rule.onNode(isFocused()).performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionUp) }
            shot("home-content-up", "远岸来信")
            rule.onNode(isFocused()).performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
            shot("home-library-focus", "远岸来信")
            rule.onNode(isFocused()).performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
            shot("home-resume-focus", "继续观看")
        }
        val vertical = hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)
        rule.onNode(vertical).performScrollToKey("tail")
        settle()
        val range = rule.onNode(vertical).fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("首页尚未滚到底", range.maxValue(), range.value(), .5f)
        val horizontal = hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange)
        val lastCard = rule.onNode(hasText("远岸来信") and hasAnyAncestor(horizontal)).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val limit = if (platform == "tv") rule.onRoot().fetchSemanticsNode().boundsInRoot.bottom
            else rule.onNodeWithTag("phone.tabs").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue("首页滚到底后卡片仍被遮挡", lastCard.bottom < limit)
        shot("home-bottom", "远岸来信")
        rule.runOnIdle { openPage("library:visual-library:电影") }
        shot("library", "远岸来信")
        if (platform == "tv") {
            rule.onNodeWithText("远岸来信").assertIsDisplayed()
            rule.onNodeWithText("2025").assertIsDisplayed()
            val year = rule.onNodeWithText("2025").fetchSemanticsNode().boundsInRoot
            assertTrue("TV首排海报的年份不能被屏幕底部裁切",
                year.bottom < rule.onRoot().fetchSemanticsNode().boundsInRoot.bottom)
        }
        rule.runOnIdle { openPage("detail:visual-1:Movie") }
        shot("detail", "远岸来信")
        if (platform == "tv") {
            rule.onNodeWithTag("rail.1").assertDoesNotExist()
            val play = rule.onNodeWithTag("detail.play").assertIsDisplayed().getUnclippedBoundsInRoot()
            val screen = rule.onRoot().getUnclippedBoundsInRoot()
            assertTrue("TV电影详情主播放操作必须完整位于首屏", play.top >= screen.top && play.bottom <= screen.bottom)
            val hero = rule.onNodeWithTag("detail.hero").getUnclippedBoundsInRoot()
            assertEquals("TV详情是全屏页，头图应从屏幕顶端铺满", screen.top, hero.top)
            assertEquals("TV详情背景不能留下左侧空边", screen.left, hero.left)
            assertEquals("TV详情背景不能留下右侧空边", screen.right, hero.right)
            assertTrue("TV头图应覆盖首屏主体", hero.bottom - hero.top >= (screen.bottom - screen.top) * .65f)
            rule.onNode(hasContentDescription("版本") and hasAnyAncestor(hasTestTag("detail.hero"))).assertIsDisplayed()
            assertTrue("TV播放按钮应按内容收拢而非铺满屏幕", play.right - play.left < (screen.right - screen.left) * .5f)
            assertEquals("标题与播放按钮应对齐同一安全边距",
                rule.onNodeWithTag("detail.title").getUnclippedBoundsInRoot().left, play.left)
            rule.onNodeWithContentDescription("返回").assertDoesNotExist()
            rule.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
            settle()
            rule.onNodeWithTag("detail.play.options").assertIsFocused()
            shot("detail-options-focus", "远岸来信")
        } else rule.onNodeWithContentDescription("返回").assertIsDisplayed()
        rule.runOnIdle { openPage("detail:visual-series:Series") }
        shot("series", "山海之间")
        if (platform == "tv") {
            val play = rule.onNodeWithTag("detail.play").assertIsDisplayed().assertIsFocused().getUnclippedBoundsInRoot()
            val screen = rule.onRoot().getUnclippedBoundsInRoot()
            assertTrue("剧集续播按钮同样保持紧凑宽度", play.right - play.left < (screen.right - screen.left) * .5f)
            rule.onNode(hasContentDescription("版本") and hasAnyAncestor(hasTestTag("detail.hero"))).assertIsDisplayed()
            val firstEpisode = data.getValue("episodes").jsonArray.first().jsonPrimitive.content
            val image = rule.onNodeWithTag("detail.ep.image.$firstEpisode", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("TV剧集首屏应展示整张分集缩略图", image.bottom <= screen.bottom)
            assertTrue("TV剧集首屏还应看清分集标题",
                rule.onNodeWithTag("detail.ep.$firstEpisode").getUnclippedBoundsInRoot().bottom <= screen.bottom)
        }
        rule.runOnIdle { openPage("settings") }
        shot("settings", "播放与外观")
    }
}
