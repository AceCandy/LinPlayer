package xyz.linplayer.app

import android.app.Application
import coil3.asImage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
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
import xyz.linplayer.app.ui.pages.CachedHomeBanner
import xyz.linplayer.app.ui.pages.homePlaybackTarget
import xyz.linplayer.app.ui.theme.LpTheme
import com.github.takahirom.roborazzi.captureRoboImage

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi", sdk = [36], application = Application::class)
class HomeHeroTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun clean() { scope.cancel() }

    private fun image(id: String) = CachedHomeImage(Item(id, "作品$id", "Movie", year = 2026,
        genres = listOf("动作")), "fake:$id", true)

    @Test fun singleItemHidesIndicatorAndPlayDoesNotOpenDetail() {
        var opened = ""
        var played = ""
        FakeImages.install(ApplicationProvider.getApplicationContext())
        rule.setContent { LpTheme(darkOverride = true) {
            Box(Modifier.fillMaxSize()) {
                CachedHomeBanner(listOf(image("one")), true, { opened = it.id }, onPlay = { played = it.id })
            }
        } }
        rule.onNodeWithTag("home.hero.indicators").assertDoesNotExist()
        rule.onNodeWithText("2026 · 动作").assertIsDisplayed()
        rule.onNodeWithText("播放").performClick()
        rule.runOnIdle { assertEquals("one", played); assertEquals("", opened) }
        rule.onNodeWithText("作品one").performClick()
        rule.runOnIdle { assertEquals("one", opened) }
        rule.onRoot().captureRoboImage("build/home-cinema/small-hero.png")
    }

    @Test fun horizontalSwipeChangesItemAndRestartsAutoTimer() {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val images = listOf(image("first"), image("second"))
        rule.mainClock.autoAdvance = false
        rule.setContent { LpTheme(darkOverride = true) { CachedHomeBanner(images, true, {}) } }
        rule.mainClock.advanceTimeBy(4800)
        rule.onNodeWithTag("home.banners").performTouchInput { swipeLeft() }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("作品second").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(3500)
        rule.onNodeWithText("作品second").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(2000)
        rule.onNodeWithText("作品first").assertIsDisplayed()
    }

    @Test fun holdingHeroPausesTimerUntilRelease() {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val images = listOf(image("first"), image("second"))
        rule.mainClock.autoAdvance = false
        rule.setContent { LpTheme(darkOverride = true) { CachedHomeBanner(images, true, {}) } }
        rule.mainClock.advanceTimeBy(4500)
        rule.onNodeWithTag("home.banners").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(8000)
        rule.onNodeWithText("作品first").assertIsDisplayed()
        rule.onNodeWithTag("home.banners").performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(4000)
        rule.onNodeWithText("作品first").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(1600)
        rule.onNodeWithText("作品second").assertIsDisplayed()
    }

    @Test fun removedAnimationsDisableAutoRotation() {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val images = listOf(image("first"), image("second"))
        rule.mainClock.autoAdvance = false
        rule.setContent { LpTheme(darkOverride = true) {
            CompositionLocalProvider(xyz.linplayer.app.ui.theme.LocalMotionScale provides 0f) {
                CachedHomeBanner(images, true, {})
            }
        } }
        rule.mainClock.advanceTimeBy(12000)
        rule.onNodeWithText("作品first").assertIsDisplayed()
        rule.onNodeWithText("作品second").assertDoesNotExist()
    }

    @Test fun backgroundPausesRotationAndResumeStartsFreshInterval() {
        val owner = object : androidx.lifecycle.LifecycleOwner {
            override val lifecycle = androidx.lifecycle.LifecycleRegistry(this).apply {
                currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            }
        }
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val images = listOf(image("first"), image("second"))
        rule.mainClock.autoAdvance = false
        rule.setContent { LpTheme(darkOverride = true) {
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                CachedHomeBanner(images, true, {})
            }
        } }
        rule.mainClock.advanceTimeBy(4000)
        rule.runOnIdle { owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        rule.mainClock.advanceTimeBy(12000)
        rule.onNodeWithText("作品first").assertIsDisplayed()
        rule.runOnIdle { owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        rule.mainClock.advanceTimeBy(4000)
        rule.onNodeWithText("作品first").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(1600)
        rule.onNodeWithText("作品second").assertIsDisplayed()
    }

    @Test fun invisibleHeroDoesNotAutoRotate() {
        FakeImages.install(ApplicationProvider.getApplicationContext())
        val images = listOf(image("first"), image("second"))
        rule.mainClock.autoAdvance = false
        rule.setContent { LpTheme(darkOverride = true) { CachedHomeBanner(images, false, {}) } }
        rule.mainClock.advanceTimeBy(12000)
        rule.onNodeWithText("作品first").assertIsDisplayed()
        rule.onNodeWithText("作品second").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w430dp-h932dp-mdpi")
    fun longTitleLargeFontKeepsPlayInsideHero() {
        rule.setContent { LpTheme(darkOverride = true) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                CachedHomeBanner(listOf(image("one").copy(item = image("one").item.copy(
                    name = "非常长的真实标题需要最多两行显示并保留完整播放入口"))), true, {})
            }
        } }
        val hero = rule.onNodeWithTag("home.banners").fetchSemanticsNode().boundsInRoot
        val play = rule.onNodeWithText("播放").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(play.bottom <= hero.bottom && play.right <= hero.right)
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test fun missingBackdropFallsBackToPrimaryAndKeepsActions() {
        val requested = mutableListOf<String>()
        coil3.SingletonImageLoader.setUnsafe(coil3.ImageLoader.Builder(
            ApplicationProvider.getApplicationContext<android.content.Context>())
            .coroutineContext(Dispatchers.Unconfined)
            .components { add(coil3.fetch.Fetcher.Factory<coil3.Uri> { data, _, _ ->
                coil3.fetch.Fetcher {
                    requested += data.toString()
                    if (data.toString() == "fake:missing") error("背景图不存在")
                    coil3.fetch.ImageFetchResult(
                        android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888).asImage(),
                        false, coil3.decode.DataSource.MEMORY)
                }
            }) }.build())
        rule.setContent { LpTheme(darkOverride = true) {
            CachedHomeBanner(listOf(image("one").copy(url = "fake:missing")), true, {},
                primaryImage = { "fake:primary" })
        } }
        rule.waitUntil { "fake:primary" in requested }
        rule.onNodeWithText("作品one").assertIsDisplayed()
        rule.onNodeWithText("播放").assertIsDisplayed()
        assertEquals(1, requested.count { it == "fake:missing" })
    }

    @Test fun seriesPlaybackUsesCurrentEpisodePlayedFlagsAcrossSeasons() = runBlocking {
        val core = FakeCore().loggedIn()
        core.ret("emby.seriesSeasons", arr(item("season1", "第一季", "Season"), item("season2", "第二季", "Season")))
        core.on("emby.seasonEpisodes") { args ->
            if (args?.str("parent_id") == "season1") page(item("watched", "已看", "Episode", played = true))
            else page(item("partial", "未完整看完", "Episode", runtime = 1800.0, resume = 600.0), item("future", "下一集", "Episode"))
        }
        val app = AppState(core, scope)
        app.boot()
        val target = homePlaybackTarget(app, Item("series", "剧集", "Series", played = true))
        assertEquals("partial", target.id)
        assertEquals(2, core.calls.count { it.first == "emby.seasonEpisodes" })
        assertEquals(600.0, target.resumeSecs, 0.0)
    }
}
