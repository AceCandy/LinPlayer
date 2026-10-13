package xyz.linplayer.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import androidx.compose.ui.graphics.toPixelMap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.ui.player.ProgressRow
import xyz.linplayer.app.ui.player.Osd
import xyz.linplayer.app.ui.player.PassiveProgress
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h440dp-land-mdpi", sdk = [36], application = Application::class)
class PhonePlayerOsdTest {
    @get:Rule val rule = createComposeRule()
    private val dark = mutableStateOf(false)
    private val speed = mutableStateOf(1.5)
    private var seek = 0.0
    private var action = ""

    private fun open(portrait: Boolean = false) {
        rule.setContent {
            LpTheme(darkOverride = dark.value) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(
                        listOf(Color(0xFF455F48), Color(0xFF181D26))))) {
                        Box(Modifier.fillMaxSize()) {
                        Osd(portrait, "1.13 MB/s",
                            title = "测试剧集", episodeLabel = "S1E7：一段用于核查横屏布局的单集标题",
                            position = 1517.0, duration = 2729.0, paused = false, speed = speed.value, buffered = 2050.0,
                            heat = emptyList(), pluginPanels = emptyList(),
                            onBack = { action = "返回" }, onToggle = { action = "暂停" },
                            onSeek = { seek = it }, onSpeed = { speed.value = it },
                            onLock = { action = "锁屏" }, onShot = { action = "截屏" },
                            onPanel = { action = it }, statusText = "21:34 · 83%",
                            onPrevious = { action = "上一集" }, onNext = { action = "下一集" },
                            onPip = { action = "画中画" }, onOrientation = { action = "方向" },
                            onChapters = { action = "章节" })
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-port-mdpi")
    fun fullPortraitKeepsReferenceZonesAndAllActions() {
        open(portrait = true)
        for (label in listOf("返回", "画中画", "比例", "切换方向", "章节", "更多", "暂停", "后退 10 秒", "前进 10 秒",
            "上一集", "下一集", "锁屏", "加速", "减速", "音轨", "字幕")) rule.onNodeWithContentDescription(label).assertIsDisplayed()
        assertProgressAndEpisodeOrder()
        val slider = rule.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNode().boundsInRoot
        val lock = rule.onNodeWithContentDescription("锁屏").fetchSemanticsNode().boundsInRoot
        assertTrue("进度行应在最底部功能行上方", slider.bottom <= lock.top)
        val targets = rule.onAllNodes(hasClickAction() or SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress)).fetchSemanticsNodes()
        targets.forEachIndexed { i, a -> targets.drop(i + 1).forEach { b ->
            val overlap = a.boundsInRoot.intersect(b.boundsInRoot)
            assertTrue("全屏竖屏小屏大字命中不能重叠：${a.config} ${a.boundsInRoot} / ${b.config} ${b.boundsInRoot}", overlap.width <= 0f || overlap.height <= 0f)
        } }
        rule.onNodeWithContentDescription("加速").performClick(); rule.onNodeWithText("1.75×").assertIsDisplayed()
        rule.onNodeWithContentDescription("减速").performClick(); rule.onNodeWithText("1.50×").assertIsDisplayed()
        rule.onNodeWithContentDescription("切换方向").performClick(); assertEquals("方向", action)
        rule.onNodeWithContentDescription("弹幕").performScrollTo().performClick(); assertEquals("danmaku", action)
        rule.onNodeWithContentDescription("截屏").performScrollTo().performClick(); assertEquals("截屏", action)
        rule.onNodeWithContentDescription("暂停").assertIsDisplayed()
        rule.onNodeWithContentDescription("加速").assertIsDisplayed()
        rule.onNodeWithContentDescription("切换方向").assertIsDisplayed()
    }

    @Test fun sliderDragKeepsItsValueAcrossPlaybackPollsAndCancel() {
        val position = mutableStateOf(10.0)
        val buffered = mutableStateOf(70.0)
        rule.setContent { LpTheme { ProgressRow(position.value, 100.0, emptyList(), { seek = it }, buffered = buffered.value) } }
        val slider = rule.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
        slider.performTouchInput { down(center); moveTo(androidx.compose.ui.geometry.Offset(width * .75f, centerY)) }
        val drag = slider.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
        rule.runOnIdle { position.value = 11.0; buffered.value = 90.0 }
        assertEquals(drag, slider.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        slider.performTouchInput { up() }
        assertEquals(drag.toDouble(), seek, .001)
        slider.performTouchInput { down(center); moveTo(androidx.compose.ui.geometry.Offset(width * .8f, centerY)); cancel() }
        rule.waitForIdle()
        assertEquals(11f, slider.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current, .001f)
    }

    @Test fun passiveProgressIsThinReadOnlyAndHidesForUnknownDuration() {
        val visible = mutableStateOf(true)
        val duration = mutableStateOf(100.0)
        val position = mutableStateOf(25.0)
        rule.setContent { LpTheme { PassiveProgress(position.value, duration.value, visible.value) } }
        val bar = rule.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
        assertEquals(.25f, bar.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current)
        assertEquals(3f, bar.fetchSemanticsNode().boundsInRoot.height, .01f)
        bar.assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))
        bar.assert(hasClickAction().not())
        rule.runOnIdle { position.value = 200.0 }
        assertEquals(1f, bar.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current)
        for (d in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            rule.runOnIdle { duration.value = d }
            bar.assertDoesNotExist()
        }
        rule.runOnIdle { duration.value = 100.0; visible.value = false }
        bar.assertDoesNotExist()
    }

    @Test fun bufferedTrackHasThreeVisibleSegmentsAndUnknownValuesClearBuffer() {
        val buffered = mutableStateOf(70.0)
        val duration = mutableStateOf(100.0)
        rule.setContent {
            LpTheme { Box(Modifier.fillMaxSize().background(Color.Black)) {
                ProgressRow(20.0, duration.value, emptyList(), {}, buffered = buffered.value)
            } }
        }
        val slider = rule.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
        val pixels = slider.captureToImage().toPixelMap()
        val played = pixels[(pixels.width * .1f).toInt(), pixels.height / 2]
        val cached = pixels[(pixels.width * .5f).toInt(), pixels.height / 2]
        val empty = pixels[(pixels.width * .9f).toInt(), pixels.height / 2]
        assertTrue("浅色缓冲段必须比未缓冲段更亮", cached.red > empty.red + .15f)
        assertTrue("已播段必须使用独立颜色", kotlin.math.abs(played.blue - played.red) > .1f)
        assertTrue(slider.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].endsWith("已缓冲至 1:10"))
        for (value in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            rule.runOnIdle { buffered.value = value }
            val p = slider.captureToImage().toPixelMap()
            assertTrue(kotlin.math.abs(p[(p.width * .5f).toInt(), p.height / 2].red - empty.red) < .03f)
            assertTrue(slider.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].endsWith("缓冲进度未知"))
        }
        rule.runOnIdle { buffered.value = 200.0 }
        assertTrue(slider.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].endsWith("已缓冲至 1:40"))
        for (value in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            rule.runOnIdle { duration.value = value }
            slider.assertIsNotEnabled()
            assertTrue(slider.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].endsWith("缓冲进度未知"))
        }
    }

    @Test fun landscapeControlsHaveSeparateZonesAndDispatchActions() {
        open()
        val pause = rule.onNodeWithContentDescription("暂停").fetchSemanticsNode().boundsInRoot
        val rewind = rule.onNodeWithContentDescription("后退 10 秒").fetchSemanticsNode().boundsInRoot
        val faster = rule.onNodeWithContentDescription("加速").fetchSemanticsNode().boundsInRoot
        val shot = rule.onNodeWithContentDescription("截屏").fetchSemanticsNode().boundsInRoot
        val picture = rule.onRoot().captureToImage().toPixelMap()
        val inside = picture[pause.center.x.toInt(), pause.top.toInt() + 2]
        val outside = picture[pause.center.x.toInt(), pause.top.toInt() - 2]
        assertTrue("半透明圆底应压暗画面以保持图标对比", inside.red < outside.red - .04f)
        assertTrue(rewind.right < pause.left && pause.right < faster.left && shot.right < rewind.left)
        rule.onNodeWithText("25:17").assertIsDisplayed()
        rule.onNodeWithText("45:29").assertIsDisplayed()
        assertProgressAndEpisodeOrder()
        rule.onNodeWithText("S1E7", substring = true).assertIsDisplayed()
        rule.onNodeWithText("21:34 · 83%").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/player-osd/landscape-light.png")
        rule.runOnIdle { dark.value = true }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/player-osd/landscape-dark.png")
        rule.onNodeWithContentDescription("后退 10 秒").performClick()
        assertEquals(1507.0, seek, 0.001)
        rule.onNodeWithContentDescription("前进 10 秒").performClick()
        assertEquals(1527.0, seek, 0.001)
        rule.onNodeWithContentDescription("加速").performClick()
        rule.onNodeWithText("1.75×").assertIsDisplayed()
        rule.onNodeWithContentDescription("选集").assertDoesNotExist()
        rule.onNodeWithContentDescription("音轨").performClick()
        assertEquals("audio", action)
        rule.onNodeWithContentDescription("截屏").performClick()
        assertEquals("截屏", action)
        for ((label, expected) in listOf("上一集" to "上一集", "下一集" to "下一集",
            "画中画" to "画中画", "切换方向" to "方向", "章节" to "章节")) {
            rule.onNodeWithContentDescription(label).performClick()
            assertEquals(expected, action)
        }
        rule.onNodeWithContentDescription("暂停").assertIsDisplayed()
        rule.onNodeWithContentDescription("加速").assertIsDisplayed()
        rule.onNodeWithContentDescription("更多").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    fun compactLandscapeHasNoOverlappingButtonsAtLargeFont() {
        open()
        val buttons = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        rule.onRoot().captureRoboImage("build/player-osd/landscape-compact.png")
        buttons.forEachIndexed { i, a -> buttons.drop(i + 1).forEach { b ->
            val overlap = a.boundsInRoot.intersect(b.boundsInRoot)
            assertTrue("按钮命中区不能重叠：${a.config} ${a.boundsInRoot} / ${b.config} ${b.boundsInRoot}", overlap.width <= 0f || overlap.height <= 0f)
        } }
        rule.onNodeWithContentDescription("音轨").assertIsDisplayed()
        rule.onNodeWithContentDescription("字幕").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/player-osd/landscape-compact.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-mdpi")
    fun portraitCommonControlsStayCenteredBelowProgress() {
        open(portrait = true)
        val common = listOf("锁屏", "音轨", "字幕")
            .map { rule.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot }
        common.forEach { assertEquals(48f, it.width, .1f); assertEquals(common.first().center.y, it.center.y, .1f) }
        val gaps = common.zipWithNext { a, b -> b.left - a.right }
        assertTrue("常用操作居中且命中不重叠", gaps.all { it >= 0f })
        assertEquals(180f, (common.first().left + common.last().right) / 2, .1f)
        rule.onNodeWithContentDescription("弹幕").performScrollTo().performClick()
        assertEquals("danmaku", action)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun portraitKeepsAllControlsAndDispatchesOrientation() {
        open(portrait = true)
        for (label in listOf("返回", "画中画", "比例", "切换方向", "章节", "更多", "暂停",
            "后退 10 秒", "前进 10 秒", "锁屏", "倍速", "上一集", "下一集",
            "音轨", "字幕")) rule.onNodeWithContentDescription(label).assertIsDisplayed()
        assertProgressAndEpisodeOrder()
        val buttons = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        buttons.forEachIndexed { i, a -> buttons.drop(i + 1).forEach { b ->
            val overlap = a.boundsInRoot.intersect(b.boundsInRoot)
            assertTrue("竖屏按钮命中区不能重叠", overlap.width <= 0f || overlap.height <= 0f)
        } }
        for ((label, expected) in listOf("切换方向" to "方向", "上一集" to "上一集", "下一集" to "下一集",
            "比例" to "ratio", "音轨" to "audio", "字幕" to "subtitle")) {
            rule.onNodeWithContentDescription(label).performClick()
            assertEquals(expected, action)
        }
        rule.onNodeWithContentDescription("倍速").performClick()
        assertEquals("speed", action)
        rule.onRoot().captureRoboImage("build/player-osd/portrait.png")
        rule.onNodeWithContentDescription("弹幕").performScrollTo().performClick()
        assertEquals("danmaku", action)
        rule.onNodeWithContentDescription("截屏").performScrollTo().performClick()
        assertEquals("截屏", action)
        rule.onNodeWithContentDescription("暂停").assertIsDisplayed()
        rule.onNodeWithContentDescription("锁屏").assertIsDisplayed()
        rule.onNodeWithContentDescription("切换方向").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-port-mdpi")
    fun compactPortraitKeepsCriticalActionsAndScrollableExtras() {
        open(portrait = true)
        for (label in listOf("返回", "画中画", "比例", "切换方向", "章节", "更多", "暂停", "上一集", "下一集", "音轨", "字幕")) {
            rule.onNodeWithContentDescription(label).assertIsDisplayed()
        }
        assertProgressAndEpisodeOrder()
        val targets = rule.onAllNodes(hasClickAction() or SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress)).fetchSemanticsNodes()
        targets.forEachIndexed { i, a -> targets.drop(i + 1).forEach { b ->
            val overlap = a.boundsInRoot.intersect(b.boundsInRoot)
            assertTrue("窄屏按钮与进度不能重叠", overlap.width <= 0f || overlap.height <= 0f)
        } }
        rule.onNodeWithContentDescription("截屏").performScrollTo().performClick()
        assertEquals("截屏", action)
        rule.onNodeWithContentDescription("弹幕").performScrollTo().performClick()
        assertEquals("danmaku", action)
        rule.onRoot().captureRoboImage("build/player-osd/portrait-compact.png")
    }

    private fun assertProgressAndEpisodeOrder(episodeOrder: Boolean = true) {
        val current = rule.onNodeWithText("25:17").fetchSemanticsNode().boundsInRoot
        val total = rule.onNodeWithText("45:29").fetchSemanticsNode().boundsInRoot
        val slider = rule.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNode().boundsInRoot
        assertEquals(current.center.y, slider.center.y, 1f)
        assertEquals(total.center.y, slider.center.y, 1f)
        assertTrue("时间必须位于滑杆左侧：$current / $total / $slider", current.right < total.left && total.right <= slider.left)
        if (!episodeOrder) return
        val previous = rule.onNodeWithContentDescription("上一集").fetchSemanticsNode().boundsInRoot
        val next = rule.onNodeWithContentDescription("下一集").fetchSemanticsNode().boundsInRoot
        val title = rule.onNodeWithText("S1E7", substring = true).fetchSemanticsNode().boundsInRoot
        assertTrue(previous.right <= next.left && next.right <= title.left)
    }

    @Test fun nextUpRejectsUnknownTimeAndEndOfPlayback() {
        for ((position, duration) in listOf(89.99 to 120.0, 120.0 to 120.0, 130.0 to 120.0,
            -1.0 to 20.0, 0.0 to 0.0, Double.NaN to 120.0, 100.0 to Double.POSITIVE_INFINITY)) {
            assertTrue(!xyz.linplayer.app.ui.player.nextUpVisible(position, duration))
        }
        assertTrue(xyz.linplayer.app.ui.player.nextUpVisible(108.0, 120.0))
        assertTrue(xyz.linplayer.app.ui.player.nextUpVisible(119.9, 120.0))
    }

    @Test fun nextUpUsesTenPercentWithinTenAndThirtySeconds() {
        for ((duration, start) in listOf(35.0 to 25.0, 100.0 to 90.0, 120.0 to 108.0,
            200.0 to 180.0, 300.0 to 270.0, 600.0 to 570.0)) {
            assertFalse("$duration 秒视频不能过早提示", xyz.linplayer.app.ui.player.nextUpVisible(start - .01, duration))
            assertTrue("$duration 秒视频应在边界提示", xyz.linplayer.app.ui.player.nextUpVisible(start, duration))
            assertTrue(xyz.linplayer.app.ui.player.nextUpVisible(duration - .1, duration))
            assertFalse(xyz.linplayer.app.ui.player.nextUpVisible(duration, duration))
        }
    }
}
