package xyz.linplayer.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.tv.movie
import xyz.linplayer.app.ui.pages.DetailPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.*
import xyz.linplayer.app.ui.theme.LocalMotionScale
import xyz.linplayer.app.ui.theme.LpTheme

/** 使用实际像素核验叠层与共享海报中间帧，不能用最终位置代替转场证据。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h600dp-mdpi", application = Application::class)
class PhonePosterMotionTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Application>()

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun images(gate: CompletableDeferred<Unit> = CompletableDeferred(Unit), heldUrl: String? = null, failedUrl: String? = null): () -> Boolean {
        var fetched = false
        val loader = ImageLoader.Builder(ctx).coroutineContext(Dispatchers.Unconfined)
            .components {
                add(object : Fetcher.Factory<Uri> {
                    override fun create(data: Uri, options: Options, imageLoader: ImageLoader) = Fetcher {
                        if (heldUrl == null || data.toString() == heldUrl) gate.await()
                        fetched = true
                        if (data.toString() == failedUrl) error("test image failure")
                        val image = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
                        image.eraseColor(android.graphics.Color.GREEN)
                        ImageFetchResult(image.asImage(), false, DataSource.NETWORK)
                    }
                })
            }.build()
        SingletonImageLoader.setUnsafe(loader)
        return { fetched }
    }

    @Test fun idleCardsDoNotInstallSharedLayoutModifiers() {
        val modifiers = mutableListOf<Modifier>()
        rule.setContent {
            LpTheme {
                val nav = rememberNavController()
                PosterMotionHost(nav) {
                    NavHost(nav, Route.Home) {
                        posterComposable<Route.Home> {
                            val idle = (1..30).map { Modifier.sharedPoster("item-$it", "card-$it") }
                            SideEffect { modifiers.clear(); modifiers.addAll(idle) }
                            Box(Modifier.size(100.dp).background(Color.Green))
                        }
                    }
                }
            }
        }
        rule.runOnIdle {
            println("idle_shared_modifiers=${modifiers.count { it !== Modifier }}")
            assertEquals(30, modifiers.size)
            assertTrue("idle posters must not participate in shared lookahead layout", modifiers.all { it === Modifier })
        }
    }

    @Test fun placeholderAndDecodedImageOverlapDuringCrossfade() {
        val gate = CompletableDeferred<Unit>()
        val fetched = images(gate)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                Box(Modifier.size(100.dp).background(Color.Black).testTag("image")) {
                    NetImage("poster:crossfade", null, Modifier.fillMaxSize(), corner = 0.dp) {
                        Box(Modifier.fillMaxSize().background(Color.Red))
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(32)
        assertEquals(Color.Red, rule.onNodeWithTag("image").captureToImage().toPixelMap()[50, 50])
        rule.runOnIdle { gate.complete(Unit) }
        rule.waitUntil(2000) { fetched() }
        rule.mainClock.advanceTimeBy(80)
        val middle = rule.onNodeWithTag("image").captureToImage().toPixelMap()[50, 50]
        assertTrue("placeholder must remain while image fades in: $middle", middle.red > .02f && middle.green > .02f)
        rule.mainClock.advanceTimeBy(400)
        assertEquals(Color.Green, rule.onNodeWithTag("image").captureToImage().toPixelMap()[50, 50])
    }

    @Test fun memoryCacheAlsoCrossfadesAndNewUrlDoesNotShowOldImage() {
        val gate = CompletableDeferred<Unit>()
        images(gate, "poster:next")
        val currentUrl = mutableStateOf("poster:cached")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                Box(Modifier.size(100.dp).background(Color.Black).testTag("image")) {
                    NetImage(currentUrl.value, currentUrl.value, Modifier.fillMaxSize(), corner = 0.dp) {
                        Box(Modifier.fillMaxSize().background(Color.Red))
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000)
        fun center() = rule.onNodeWithTag("image").captureToImage().toPixelMap()[50, 50]
        assertEquals(Color.Green, center())
        rule.runOnIdle { currentUrl.value = ""; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithContentDescription("poster:cached").assertDoesNotExist()
        assertEquals(Color.Red, center())
        rule.runOnIdle { currentUrl.value = "poster:cached"; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(80)
        val hot = center()
        assertTrue("hot memory image must also crossfade: $hot", hot.red > .02f && hot.green > .02f)
        rule.mainClock.advanceTimeBy(400)
        assertEquals(Color.Green, center())
        rule.runOnIdle { currentUrl.value = "poster:next"; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(32)
        assertEquals("new pending URL must not expose the preceding image", Color.Red, center())
        rule.runOnIdle { gate.complete(Unit) }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(Color.Green, center())
    }

    @Test fun listImagesAppearIndependentlyAsEachOneFinishes() {
        val gate = CompletableDeferred<Unit>()
        images(gate, "poster:slow")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                Row {
                    listOf("fast", "slow").forEach { name ->
                        NetImage("poster:$name", name, Modifier.size(100.dp).testTag(name), corner = 0.dp) {
                            Box(Modifier.fillMaxSize().background(Color.Red))
                        }
                    }
                }
            }
        }
        fun pixel(name: String) = rule.onNodeWithTag(name).captureToImage().toPixelMap()[50, 50]
        rule.mainClock.advanceTimeBy(600)
        assertEquals("ready image must not wait for its neighbor", Color.Green, pixel("fast"))
        assertEquals(Color.Red, pixel("slow"))
        rule.runOnIdle { gate.complete(Unit) }
        rule.mainClock.advanceTimeBy(80)
        assertEquals(Color.Green, pixel("fast"))
        val middle = pixel("slow")
        assertTrue("late image fades independently", middle.green > .02f && middle.red > .02f)
        rule.mainClock.advanceTimeBy(400)
        assertEquals(Color.Green, pixel("slow"))
    }

    @Test fun readyOffscreenImageStartsFadingOnlyWhenScrolledIntoView() {
        val fetched = images()
        lateinit var scroll: ScrollState
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                scroll = rememberScrollState()
                Column(Modifier.size(100.dp).clipToBounds().verticalScroll(scroll)) {
                    Spacer(Modifier.height(200.dp))
                    NetImage("poster:prefetched", null, Modifier.size(100.dp).testTag("viewport-image"), corner = 0.dp) {
                        Box(Modifier.fillMaxSize().background(Color.Red))
                    }
                    Spacer(Modifier.height(200.dp))
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000)
        assertTrue("image must already be decoded offscreen", fetched())
        rule.runOnIdle { scroll.dispatchRawDelta(200f) }
        rule.mainClock.advanceTimeBy(32)
        rule.onRoot().captureToImage()
        rule.mainClock.advanceTimeBy(96)
        val middle = rule.onNodeWithTag("viewport-image").captureToImage().toPixelMap()[50, 50]
        assertTrue("ready image must start fading on viewport entry: $middle", middle.red > .4f && middle.green > .05f)
        rule.mainClock.advanceTimeBy(500)
        assertEquals(Color.Green, rule.onNodeWithTag("viewport-image").captureToImage().toPixelMap()[50, 50])
    }

    @Test fun decodedImageFadesAgainAfterFullyLeavingViewport() {
        images()
        lateinit var scroll: ScrollState
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                scroll = rememberScrollState()
                Column(Modifier.size(100.dp).clipToBounds().verticalScroll(scroll)) {
                    NetImage("poster:repeat", null, Modifier.size(100.dp).testTag("viewport-image"), corner = 0.dp) {
                        Box(Modifier.fillMaxSize().background(Color.Red))
                    }
                    Spacer(Modifier.height(400.dp))
                }
            }
        }
        fun pixel() = rule.onNodeWithTag("viewport-image").captureToImage().toPixelMap()[50, 50]
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(Color.Green, pixel())
        rule.runOnIdle { scroll.dispatchRawDelta(200f) }
        rule.mainClock.advanceTimeBy(32)
        rule.onRoot().captureToImage()
        rule.mainClock.advanceTimeBy(1000)
        rule.runOnIdle { scroll.dispatchRawDelta(-200f) }
        rule.mainClock.advanceTimeBy(32)
        rule.onRoot().captureToImage()
        rule.mainClock.advanceTimeBy(96)
        val middle = pixel()
        assertTrue("same decoded image must fade again: $middle", middle.red > .4f && middle.green > .05f)
        rule.mainClock.advanceTimeBy(500)
        assertEquals(Color.Green, pixel())
        // 仍有一小条可见时不复位，返回阅读位置不应闪回占位。
        rule.runOnIdle { scroll.dispatchRawDelta(92f) }
        rule.mainClock.advanceTimeBy(32)
        rule.onRoot().captureToImage()
        rule.runOnIdle { scroll.dispatchRawDelta(-92f) }
        rule.mainClock.advanceTimeBy(32)
        assertEquals(Color.Green, pixel())
    }

    @Test fun cachedDetailBackgroundStillFadesOnReentry() {
        images()
        val shown = mutableStateOf(true)
        val motionScale = mutableStateOf(1f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalMotionScale provides motionScale.value) {
                    Box(Modifier.size(100.dp).background(Color.Black).testTag("backdrop")) {
                        if (shown.value) NetImage("poster:backdrop", null, Modifier.fillMaxSize(),
                            corner = 0.dp, backgroundBlur = true) {
                            Box(Modifier.fillMaxSize().background(Color.Red))
                        }
                    }
                }
            }
        }
        fun center() = rule.onNodeWithTag("backdrop").captureToImage().toPixelMap()[50, 50]
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(Color.Green, center())
        rule.runOnIdle { shown.value = false; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle { shown.value = true; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(80)
        val middle = center()
        assertTrue("cached detail background must still crossfade: $middle", middle.red > .02f && middle.green > .02f)
        rule.mainClock.advanceTimeBy(500)
        assertEquals(Color.Green, center())
        rule.runOnIdle {
            shown.value = false
            motionScale.value = 0f
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle { shown.value = true; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(32)
        assertEquals("disabled animations must also disable cached background fade", Color.Green, center())
    }

    @Test fun wholeCardEntersTogetherAndDoesNotReplayAfterLazyDisposal() {
        var list: LazyListState? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                list = rememberLazyListState()
                LazyColumn(Modifier.size(200.dp).background(Color.Black).testTag("list"), state = list) {
                    item("card") {
                        Column(Modifier.posterEntrance("one", "poster:one")) {
                            Box(Modifier.size(100.dp, 60.dp).background(Color.Green))
                            Box(Modifier.size(100.dp, 30.dp).background(Color.Red))
                        }
                    }
                    item("spacer") { Spacer(Modifier.height(1200.dp)) }
                }
            }
        }
        rule.mainClock.advanceTimeBy(80)
        val middle = rule.onNodeWithTag("list").captureToImage().toPixelMap()
        val green = (0 until middle.height).filter { middle[50, it].green > .02f }
        val red = (0 until middle.height).filter { middle[50, it].red > .02f }
        assertTrue(green.isNotEmpty() && red.isNotEmpty())
        assertTrue("image and caption must move as one card", kotlin.math.abs(green.last() + 1 - red.first()) <= 1)
        rule.mainClock.advanceTimeBy(500)
        rule.runOnIdle { runBlocking { list!!.scrollToItem(1) } }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle { runBlocking { list!!.scrollToItem(0) } }
        rule.mainClock.advanceTimeBy(16)
        val returned = rule.onNodeWithTag("list").captureToImage().toPixelMap()
        assertEquals(Color.Green, returned[50, 0])
        assertEquals(Color.Red, returned[50, 75])
    }

    @Test fun coldPosterWaitsForDecodedImageThenSpringsIn() {
        val gate = CompletableDeferred<Unit>()
        val fetched = images(gate)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                Box(Modifier.fillMaxSize().padding(32.dp)) {
                    MediaCard(Item("cold", "等待图片", "Movie"), "poster:cold", {},
                        Modifier.width(100.dp).testTag("cold"))
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000)
        val waiting = rule.onNodeWithTag("cold").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText("等待图片").assertIsDisplayed()
        assertTrue("loading placeholder must remain visible at the entrance scale: $waiting", waiting.width in 93f..95f)
        rule.runOnIdle { gate.complete(Unit) }
        rule.waitUntil(2000) { fetched() }
        rule.mainClock.advanceTimeBy(80)
        val entering = rule.onNodeWithTag("cold").fetchSemanticsNode().boundsInRoot
        assertTrue("decoded image must start the card motion: $entering", entering.width > waiting.width && entering.width < 100f)
        assertTrue("card must rise with its image", entering.top < waiting.top)
        rule.mainClock.advanceTimeBy(192)
        assertTrue("spring must slightly overshoot", rule.onNodeWithTag("cold").fetchSemanticsNode().boundsInRoot.width > 100f)
        rule.mainClock.advanceTimeBy(700)
        assertEquals(100f, rule.onNodeWithTag("cold").fetchSemanticsNode().boundsInRoot.width, .01f)
    }

    @Test fun cachedPosterImageFadesWhileCardEnters() {
        images()
        val showCard = mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                Box(Modifier.fillMaxSize().padding(32.dp)) {
                    if (showCard.value) MediaCard(Item("hot", "缓存海报", "Movie"), "poster:hot", {},
                        Modifier.width(100.dp).testTag("hot"))
                    else NetImage("poster:hot", null, Modifier.size(100.dp).testTag("warm"))
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(Color.Green, rule.onNodeWithTag("warm").captureToImage().toPixelMap()[50, 50])
        rule.runOnIdle { showCard.value = true; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(80)
        val middle = rule.onNodeWithTag("hot").captureToImage().toPixelMap()[50, 50]
        assertTrue("cached card image must be partially visible during fade: $middle",
            middle.green > middle.red + .05f && middle.red > .02f)
        val first = rule.onNodeWithTag("hot").fetchSemanticsNode().boundsInRoot.width
        assertTrue("memory hits still get one card entrance", first < 100f)
        rule.mainClock.advanceTimeBy(80)
        assertTrue(rule.onNodeWithTag("hot").fetchSemanticsNode().boundsInRoot.width > first)
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(Color.Green, rule.onNodeWithTag("hot").captureToImage().toPixelMap()[50, 50])
    }

    @Test fun failedAndAbsentPostersSettleWithoutHidingCaption() {
        images(failedUrl = "poster:error")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                Column(Modifier.padding(32.dp)) {
                    MediaCard(Item("error", "失败标题", "Movie"), "poster:error", {},
                        Modifier.width(100.dp).testTag("error"))
                    MediaCard(Item("absent", "无图标题", "Movie"), null, {},
                        Modifier.width(100.dp).testTag("absent"))
                }
            }
        }
        rule.mainClock.advanceTimeBy(500)
        for (tag in listOf("error", "absent")) {
            assertEquals(100f, rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.width, .01f)
        }
        rule.onNodeWithText("失败标题").assertIsDisplayed()
        rule.onNodeWithText("无图标题").assertIsDisplayed()
    }

    @Test fun cardUrlChangeWaitsForNewImageAndZeroMotionHasNormalGeometry() {
        val gate = CompletableDeferred<Unit>()
        images(gate, "poster:next")
        val url = mutableStateOf("poster:cached")
        val motionScale = mutableStateOf(1f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalMotionScale provides motionScale.value) {
                    Box(Modifier.fillMaxSize().padding(32.dp)) {
                        MediaCard(Item("same", "换图", "Movie"), url.value, {},
                            Modifier.width(100.dp).testTag("card"))
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000)
        rule.runOnIdle { url.value = "poster:next"; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals("old success must not start new pending card", 94f,
            rule.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot.width, .01f)
        rule.runOnIdle { motionScale.value = 0f; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(32)
        assertEquals(100f, rule.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot.width, .01f)
        rule.runOnIdle { gate.complete(Unit) }
        rule.mainClock.advanceTimeBy(32)
        assertEquals(100f, rule.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot.width, .01f)
    }

    @Test fun fastScrollFinishesActiveEntranceAndNewCardsImmediately() {
        val scroll = PosterScrollMotion(1f)
        val second = mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalPosterScroll provides scroll) {
                    Column(Modifier.padding(32.dp)) {
                        Box(Modifier.size(100.dp).posterEntrance("one", "poster:one").background(Color.Green).testTag("one"))
                        if (second.value) Box(Modifier.size(100.dp).posterEntrance("two", "poster:two").background(Color.Red).testTag("two"))
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(80)
        assertTrue(rule.onNodeWithTag("one").fetchSemanticsNode().boundsInRoot.width < 100f)
        rule.runOnIdle {
            scroll.observe(Offset(64f, 0f), 1000)
            second.value = true
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(32)
        for (tag in listOf("one", "two")) assertEquals(100f,
            rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.width, .01f)
        rule.runOnIdle { scroll.settle(1150); androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(80)
        for (tag in listOf("one", "two")) assertEquals("fast-scroll cards must not restart", 100f,
            rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.width, .01f)
    }

    @Test fun firstBatchIsBoundedAndScrollSpeedHandlesBothAxes() {
        val batch = PosterEntranceBatch()
        assertEquals(listOf(0L, 25L, 50L, 0L, 25L, 50L, 0L, 25L, 50L), (1..9).map { batch.delayMillis(1000) })
        assertEquals(0L, batch.delayMillis(1000))
        val late = PosterEntranceBatch()
        late.delayMillis(1000)
        assertEquals(0L, late.delayMillis(1151))
        val scroll = PosterScrollMotion(2f)
        scroll.observe(Offset(2f, 0f), 1000)
        scroll.observe(Offset(0f, 2f), 1016)
        assertFalse(scroll.fast)
        scroll.observe(Offset(60f, 0f), 1032)
        assertTrue("horizontal velocity must be observed", scroll.fast)
        scroll.settle(1100)
        assertTrue(scroll.fast)
        scroll.observe(Offset(0f, -4f), 1120)
        scroll.settle(1270)
        assertFalse(scroll.fast)
        scroll.observe(Offset(0f, -64f), 2000)
        assertTrue("first fast vertical step must be detected", scroll.fast)
    }

    @Test fun clippedPosterDoesNotEnterUntilEnoughOfItIsVisible() {
        val x = mutableStateOf(90)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                Box(Modifier.padding(32.dp).size(100.dp).clipToBounds().background(Color.Black).testTag("viewport")) {
                    Box(Modifier.offset(x.value.dp).size(100.dp).posterEntrance("partial", "poster:partial")
                        .background(Color.Green))
                }
            }
        }
        fun brightWidth() = rule.onNodeWithTag("viewport").captureToImage().toPixelMap().let { pixels ->
            (0 until pixels.width).count { pixels[it, 50].green > .95f }
        }
        rule.mainClock.advanceTimeBy(1000)
        assertTrue("only10% visible must retain entrance scale", brightWidth() in 5..8)
        rule.runOnIdle { x.value = 75; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(32)
        val beforeMotion = brightWidth()
        rule.mainClock.advanceTimeBy(128)
        val enteringWidth = brightWidth()
        assertTrue("25% visible must start spring: $beforeMotion → $enteringWidth", enteringWidth > beforeMotion)
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(25, brightWidth())
    }

    @Test fun realLazyFlingIsObservedWithoutConsumingScroll() {
        val scroll = PosterScrollMotion(1f)
        var list: LazyListState? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalPosterScroll provides scroll) {
                    list = rememberLazyListState()
                    LazyColumn(Modifier.size(200.dp).nestedScroll(scroll).testTag("fling"), state = list) {
                        items(30) { n ->
                            Box(Modifier.size(100.dp).posterEntrance("fling-$n", "poster:$n", ready = false)
                                .background(Color.Green).testTag("card$n"))
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("fling").performTouchInput { swipeUp(durationMillis = 100) }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle {
            assertTrue("nested scroll must observe a real fling", scroll.fast)
            assertTrue("observer must not consume list movement", list!!.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)
        }
        val visible = list!!.firstVisibleItemIndex + 1
        assertEquals(100f, rule.onNodeWithTag("card$visible").fetchSemanticsNode().boundsInRoot.width, .01f)
        rule.mainClock.advanceTimeBy(1000)
    }

    @Test fun homeCardsExpandUpWithStaggerAndDoNotReplay() {
        images()
        var list: LazyListState? = null
        var compositions = 0
        var opened = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                list = rememberLazyListState()
                LazyColumn(Modifier.size(320.dp, 360.dp).background(Color.Black).testTag("viewport"), state = list) {
                    item("lead") { Spacer(Modifier.height(300.dp)) }
                    item("row") {
                        SideEffect { compositions++ }
                        LpRow("固定标题", (0..5).map { Item("row$it", "海报$it", "Movie") },
                            { "poster:${it.id}" }, { opened++ }, menu = { listOf(CardAction("行菜单") {}) },
                            homeAccount = "server" to "user", m = Modifier.testTag("strip"))
                    }
                    item("tail") { Spacer(Modifier.height(700.dp)) }
                }
            }
        }
        fun poster(n: Int) = rule.onNodeWithContentDescription("海报$n", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        rule.mainClock.advanceTimeBy(32)
        assertTrue("first measured card must already be smaller", poster(0).width < 100f)
        rule.mainClock.advanceTimeBy(1000)
        val titleLeft = rule.onNodeWithText("固定标题").fetchSemanticsNode().boundsInRoot.left
        rule.onNodeWithContentDescription("海报0", useUnmergedTree = true).assertIsNotDisplayed()
        val initialCompositions = compositions
        rule.runOnIdle { runBlocking { list!!.scrollToItem(0, 80) } }
        rule.mainClock.advanceTimeBy(32)
        val starting = poster(0)
        rule.mainClock.advanceTimeBy(64)
        val moving = poster(0)
        assertTrue("card must grow upwards in successive frames: $starting → $moving",
            moving.top < starting.top - 2f && moving.width > starting.width + 1f)
        assertEquals("no row translation may shift the card center", 68f, moving.center.x, .1f)
        assertTrue("home cards must expand with independent phases", kotlin.math.abs(moving.width - poster(1).width) > .3f)
        val animatingCompositions = compositions
        rule.mainClock.advanceTimeBy(352)
        assertEquals("main movement must settle by about450ms", 16f, poster(0).left, .4f)
        rule.mainClock.advanceTimeBy(700)
        val revealed = poster(0)
        assertEquals(16f, revealed.left, .1f)
        assertEquals(104f, revealed.width, .1f)
        assertEquals("title must not slide horizontally", titleLeft,
            rule.onNodeWithText("固定标题").fetchSemanticsNode().boundsInRoot.left, .1f)
        assertTrue("threshold may recompose at most once", compositions <= initialCompositions + 1)
        assertEquals("animation frames must not recompose the row", animatingCompositions, compositions)
        rule.onNodeWithContentDescription("海报0", useUnmergedTree = true).performTouchInput { click(center) }
        assertEquals(1, opened)
        rule.onNodeWithContentDescription("海报0", useUnmergedTree = true).performTouchInput { longClick(center) }
        rule.mainClock.advanceTimeBy(350)
        rule.onNodeWithText("行菜单").assertIsDisplayed().performClick()
        assertEquals("long press must not open the detail", 1, opened)
        rule.runOnIdle { runBlocking { list!!.scrollToItem(0, 0) } }
        rule.mainClock.advanceTimeBy(32)
        assertEquals("return scrolling must not replay", 16f, poster(0).left, .1f)
        assertEquals(104f, poster(0).width, .1f)
        rule.runOnIdle { runBlocking { list!!.scrollToItem(2) } }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithText("固定标题").assertDoesNotExist()
        rule.runOnIdle { runBlocking { list!!.scrollToItem(0, 160) } }
        rule.mainClock.advanceTimeBy(32)
        assertEquals("Lazy disposal must preserve played entrance", 16f, poster(0).left, .1f)
        rule.onNodeWithTag("strip").performTouchInput { swipeLeft(durationMillis = 300) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithContentDescription("海报0", useUnmergedTree = true).assertIsNotDisplayed()
    }

    @Test fun coldImageDoesNotDelayOrRestartHomeCardMotion() {
        val gate = CompletableDeferred<Unit>()
        val fetched = images(gate, "poster:pending")
        var list: LazyListState? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                list = rememberLazyListState()
                LazyColumn(Modifier.size(320.dp, 360.dp).background(Color.Black).testTag("viewport"), state = list) {
                    item("lead") { Spacer(Modifier.height(280.dp)) }
                    item("row") {
                        LpRow("冷图同排", listOf(Item("hot", "已加载", "Movie"), Item("pending", "等待图", "Movie")),
                            { "poster:${it.id}" }, {}, homeAccount = "server" to "user")
                    }
                    item("tail") { Spacer(Modifier.height(700.dp)) }
                }
            }
        }
        fun bounds(name: String) = rule.onNodeWithContentDescription(name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle { runBlocking { list!!.scrollToItem(0, 80) } }
        rule.mainClock.advanceTimeBy(1000)
        val ready = bounds("已加载")
        val pending = bounds("等待图")
        assertEquals("both cards must finish before the cold image arrives", ready.width, pending.width, .1f)
        val pixels = rule.onNodeWithTag("viewport").captureToImage().toPixelMap()
        val color = pixels[(ready.left + 20).toInt(), (ready.top + 10).toInt()]
        assertEquals("row must finish independent of cold image", 104f, ready.width, .1f)
        assertTrue("settled row must display the hot image at full brightness: $color", color.green > .98f)
        rule.runOnIdle { gate.complete(Unit) }
        rule.waitUntil(2000) { fetched() }
        rule.mainClock.advanceTimeBy(80)
        assertEquals("loaded image must not start a second card motion", pending, bounds("等待图"))
        rule.mainClock.advanceTimeBy(500)
        assertEquals(pending, bounds("等待图"))
    }

    @Test fun homeCardsStartDuringFastScrollAndZeroMotionFinishesThem() {
        images()
        val scroll = PosterScrollMotion(1f)
        var list: LazyListState? = null
        val motionScale = mutableStateOf(1f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalPosterScroll provides scroll, LocalMotionScale provides motionScale.value) {
                    list = rememberLazyListState()
                    LazyColumn(Modifier.size(320.dp, 360.dp), state = list) {
                        item("lead") { Spacer(Modifier.height(300.dp)) }
                        item("row") {
                            LpRow("快滚轨道", listOf(Item("one", "一张海报", "Movie")), { "poster:one" }, {},
                                homeAccount = "server" to "user")
                        }
                        item("tail") { Spacer(Modifier.height(700.dp)) }
                    }
                }
            }
        }
        fun left() = rule.onNodeWithContentDescription("一张海报", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        rule.mainClock.advanceTimeBy(1000)
        rule.runOnIdle {
            scroll.observe(Offset(0f, -64f), 1000)
            runBlocking { list!!.scrollToItem(0, 100) }
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeBy(96)
        assertTrue("visible card must animate while fast scroll is active: ${left()}", left() in 16.5f..20.5f)
        rule.runOnIdle { assertTrue(scroll.fast) }
        rule.runOnIdle { motionScale.value = 0f; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(32)
        val disabled = rule.onNodeWithContentDescription("一张海报", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(16f, disabled.left, .1f)
        assertEquals(104f, disabled.width, .1f)
        rule.runOnIdle { motionScale.value = 1f; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(32)
        assertEquals("reenabling motion must not replay a shown row", 16f, left(), .1f)
    }

    @Test fun homeCardsAppearAfterSkeletonAndRestoreAcrossNavigationAndAccount() {
        images()
        val loaded = mutableStateOf(false)
        val account = mutableStateOf("account-a" to "user-a")
        var list: LazyListState? = null
        var nav: NavController? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                val controller = rememberNavController()
                nav = controller
                NavHost(controller, Route.Home, enterTransition = { fadeIn(tween(0)) }, exitTransition = { fadeOut(tween(0)) }) {
                    posterComposable<Route.Home> {
                        list = rememberLazyListState()
                        LazyColumn(Modifier.size(320.dp, 360.dp), state = list) {
                            item("lead") { Spacer(Modifier.height(300.dp)) }
                            item("row") {
                                if (!loaded.value) LpRowSkeleton("同一轨道", thumb = false, m = Modifier.testTag("strip"))
                                else LpRow("同一轨道", listOf(Item("one", "实际海报", "Movie")), { "poster:one" }, {},
                                    homeAccount = account.value, m = Modifier.testTag("strip"))
                            }
                            item("tail") { Spacer(Modifier.height(700.dp)) }
                        }
                    }
                    posterComposable<Route.Settings> { Text("离开首页") }
                }
            }
        }
        fun strip() = rule.onNodeWithTag("strip").fetchSemanticsNode().boundsInRoot
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle { runBlocking { list!!.scrollToItem(0, 80) } }
        rule.mainClock.advanceTimeBy(96)
        assertEquals("skeleton track remains stationary", 0f, strip().left, .1f)
        fun card() = rule.onNodeWithContentDescription("实际海报", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { loaded.value = true; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(32)
        assertEquals("data arrival must not shift the track", 0f, strip().left, .1f)
        assertTrue("real card starts its own entrance", card().width < 103f)
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(0f, strip().left, .1f)
        rule.runOnIdle { nav!!.navigate(Route.Settings) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("实际海报").assertDoesNotExist()
        rule.runOnIdle { nav!!.popBackStack() }
        rule.mainClock.advanceTimeBy(32)
        assertEquals("navigation return must restore played entrance", 104f, card().width, .1f)
        rule.runOnIdle { account.value = "account-b" to "user-b"; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(32)
        assertTrue("same item on a new account gets its own entrance", card().width < 103f)
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(0f, strip().left, .1f)
    }

    @Test fun homeCardKeepsAnimatingAcrossVisibilityChanges() {
        images()
        val scroll = PosterScrollMotion(1f)
        var list: LazyListState? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalPosterScroll provides scroll) {
                    list = rememberLazyListState()
                    LazyColumn(Modifier.size(320.dp, 360.dp), state = list) {
                        item("lead") { Spacer(Modifier.height(300.dp)) }
                        item("row") {
                            LpRow("短暂离开", listOf(Item("one", "待归位海报", "Movie")), { "poster:one" }, {},
                                homeAccount = "server" to "user")
                        }
                        item("tail") { Spacer(Modifier.height(700.dp)) }
                    }
                }
            }
        }
        fun left() = rule.onNodeWithContentDescription("待归位海报", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle { scroll.observe(Offset(0f, -64f), 1000); runBlocking { list!!.scrollToItem(0, 100) } }
        rule.mainClock.advanceTimeBy(32)
        assertTrue(left() > 18f)
        rule.runOnIdle { runBlocking { list!!.scrollToItem(0, 0) } }
        rule.mainClock.advanceTimeBy(1000)
        assertEquals("leaving threshold must not park a pending card", 16f, left(), .1f)
        rule.runOnIdle { scroll.settle(1150); runBlocking { list!!.scrollToItem(0, 100) } }
        rule.mainClock.advanceTimeBy(32)
        assertEquals("returning to threshold must not replay", 16f, left(), .1f)
    }

    @Test fun realFastFlingAnimatesVisibleCardsWithoutWaitingOrReplay() {
        images()
        val scroll = PosterScrollMotion(1f)
        var list: LazyListState? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalPosterScroll provides scroll) {
                    list = rememberLazyListState()
                    LazyColumn(Modifier.size(320.dp, 360.dp).nestedScroll(scroll).testTag("fling"), state = list) {
                        items(12, key = { "row-$it" }) { n ->
                            LpRow("栏目$n", listOf(Item("item$n", "海报$n", "Movie")), { "poster:${it.id}" }, {},
                                homeAccount = "server" to "user")
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag("fling").performTouchInput { swipeUp(durationMillis = 100) }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle { runBlocking { list!!.stopScroll() }; assertTrue(scroll.fast) }
        rule.mainClock.advanceTimeBy(32)
        val swept = list!!.layoutInfo.visibleItemsInfo.first { it.offset >= 0 }.index
        fun poster() = rule.onNodeWithContentDescription("海报$swept", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("fling must reach a fresh row", swept > 1)
        val during = poster()
        rule.mainClock.advanceTimeBy(1000)
        assertEquals("visible card finishes without waiting for settle", 104f, poster().width, .1f)
        rule.runOnIdle { assertTrue(scroll.fast) }
        assertTrue("card must never park a full horizontal place away", during.center.x < 70f)
        rule.runOnIdle { runBlocking { list.scrollToItem(swept + 3) } }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithText("海报$swept").assertDoesNotExist()
        rule.runOnIdle { scroll.settle(Long.MAX_VALUE); runBlocking { list.scrollToItem(swept) } }
        rule.mainClock.advanceTimeBy(32)
        assertEquals("swept row must not animate on returning", 16f, poster().left, .1f)
        assertEquals(104f, poster().width, .1f)
    }

    @Test fun horizontalHomeScrollRevealsNewCardsAndKeepsOldCardsSettled() {
        images()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                Box(Modifier.size(320.dp, 300.dp)) {
                    LpRow("横向轨道", (0..12).map { Item("h$it", "横滑$it", "Movie") },
                        { "poster:${it.id}" }, {}, homeAccount = "server" to "user")
                }
            }
        }
        fun poster(n: Int) = rule.onNodeWithContentDescription("横滑$n", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        rule.mainClock.advanceTimeBy(1000)
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        rule.mainClock.advanceTimeBy(64)
        val entering = poster(5)
        assertTrue("horizontally entering card must start smaller: $entering", entering.width in 93f..102f)
        rule.mainClock.advanceTimeBy(700)
        assertEquals(104f, poster(5).width, .1f)
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.mainClock.advanceTimeBy(32)
        assertEquals("horizontal return must not replay", 104f, poster(0).width, .1f)
    }

    @Test fun loadedHomePostersKeepDeformingOnRepeatedHorizontalScroll() {
        val fetched = images()
        val motion = mutableStateOf(1f)
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalMotionScale provides motion.value) {
                    Box(Modifier.size(320.dp, 300.dp)) {
                        LpRow("横向轨道", (0..12).map { Item("h$it", "横滑$it", "Movie") },
                            { "poster:${it.id}" }, {}, homeAccount = "server" to "user")
                    }
                }
            }
        }
        fun height() = rule.onNodeWithContentDescription("横滑1", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.height
        fun scroll(delta: Float) {
            rule.onNode(hasScrollToIndexAction()).performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(delta, 0f) }
            rule.waitForIdle()
        }
        val normal = height()
        assertTrue("image fetch must have completed", fetched())
        val pixels = rule.onNodeWithContentDescription("横滑1", useUnmergedTree = true).captureToImage().toPixelMap()
        assertTrue("decoded image must be visible before repeated scroll",
            pixels[pixels.width / 2, pixels.height / 2].green > .95f)
        repeat(2) {
            scroll(165f)
            assertTrue("loaded poster must shrink at viewport edge on every pass", height() < normal - 3f)
            scroll(-165f)
            assertEquals("same position must restore same geometry", normal, height(), .1f)
        }
        scroll(800f)
        rule.onNodeWithContentDescription("横滑1", useUnmergedTree = true).assertDoesNotExist()
        scroll(-800f)
        scroll(165f)
        assertTrue("returning loaded poster must still deform", height() < normal - 3f)
        scroll(-165f)
        rule.runOnIdle { motion.value = 0f }
        scroll(165f)
        assertEquals("zero animation scale disables deformation", normal, height(), .1f)
    }

    /** 过滤渐隐的未选海报，只量共享overlay中全亮的绿色矩形。 */
    private fun greenBounds(): Rect {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        var left = pixels.width; var right = -1; var top = pixels.height; var bottom = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.green > .95f && color.red < .05f && color.blue < .05f) {
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("shared poster must remain drawn", right >= left)
        return Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
    }

    @Test fun selectedDuplicatePosterMovesContinuouslyAndReturnsToItsOwnCard() {
        images()
        var previewName: String? = null
        var nav: NavController? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LpTheme {
                val controller = rememberNavController()
                nav = controller
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    PosterMotionHost(controller) {
                        NavHost(controller, Route.Home,
                            enterTransition = { fadeIn(tween(350)) }, exitTransition = { fadeOut(tween(200)) },
                            popEnterTransition = { fadeIn(tween(200)) }, popExitTransition = { fadeOut(tween(350)) }) {
                            posterComposable<Route.Home> {
                                Row(Modifier.padding(start = 16.dp, top = 400.dp), horizontalArrangement = Arrangement.spacedBy(100.dp)) {
                                    for (n in 1..2) MediaCard(Item("same", "卡片$n", "Movie"), "poster:same",
                                        { controller.navigate(Route.Detail("same", "Movie")) },
                                        Modifier.width(60.dp).testTag("source$n"))
                                }
                            }
                            posterComposable<Route.Detail> {
                                previewName = detailPreview("same")?.name
                                Box(Modifier.padding(start = 100.dp, top = 60.dp)) {
                                    Box(Modifier.size(120.dp, 180.dp).sharedPoster("same").background(Color.Green).testTag("target"))
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag("source2").performTouchInput { click(center) }
        rule.mainClock.advanceTimeBy(100)
        val middle = greenBounds()
        assertEquals("卡片2", previewName)
        assertTrue("must move from selected right card toward detail: $middle", middle.left in 100f..176f)
        assertTrue("must have an intermediate vertical position: $middle", middle.top > 60f && middle.top < 400f)
        assertTrue("must resize continuously: $middle", middle.width > 60f && middle.width < 120f)
        rule.mainClock.advanceTimeBy(500)
        assertEquals(Rect(100f, 60f, 220f, 240f), greenBounds())
        rule.runOnIdle { nav!!.popBackStack() }
        rule.mainClock.advanceTimeBy(100)
        val returning = greenBounds()
        assertTrue("return must animate toward source: $returning", returning.top > 60f && returning.top < 400f)
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("source2").assertExists()
    }

    @Test fun actualDetailSharesPosterBeforeItsMetadataArrives() =
        checkDetailPreview(Item("m1", "Movie", "Movie"))

    @Test fun actualDetailShowsTitleFromSourceWithoutImage() =
        checkDetailPreview(Item("m1", "无图标题", "Movie"), withImage = false)

    @Test fun actualEpisodeUsesSourceTextWithoutInventingSeriesLink() =
        checkDetailPreview(Item("m1", "预览集名", "Episode", seriesName = "预览剧名", seasonNo = 2, episodeNo = 3))

    private fun checkDetailPreview(source: Item, withImage: Boolean = true) {
        images()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val gate = CompletableDeferred<Unit>()
        val fake = FakeCore().loggedIn().movie()
        var pending = false
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.itemDetail") {
                    pending = true
                    gate.await()
                }
                return fake.callJson(command, args, onPartial)
            }
        }
        val app = AppState(core, scope)
        try {
            runBlocking { app.boot() }
            rule.mainClock.autoAdvance = false
            rule.setContent {
                LpTheme {
                    CompositionLocalProvider(LocalApp provides app) {
                        val nav = rememberNavController()
                        PosterMotionHost(nav) {
                            NavHost(nav, Route.Home, enterTransition = { fadeIn(tween(350)) },
                                exitTransition = { fadeOut(tween(200)) }) {
                                posterComposable<Route.Home> {
                                    Box(Modifier.padding(start = 16.dp, top = 400.dp)) {
                                        MediaCard(source, if (withImage) app.imageUrl("m1", "Primary", 180) else null,
                                            { nav.navigate(Route.Detail("m1", source.type)) },
                                            Modifier.width(60.dp).testTag("source"))
                                    }
                                }
                                posterComposable<Route.Detail> { DetailPage(nav, it) }
                            }
                        }
                    }
                }
            }
            rule.mainClock.advanceTimeBy(1000)
            rule.onNodeWithTag("source").performClick()
            rule.mainClock.advanceTimeBy(100)
            assertTrue("metadata must still be pending", pending && !gate.isCompleted)
            if (withImage && !source.isEpisode) {
                val middle = greenBounds()
                assertTrue("actual detail must share its poster while metadata is pending: $middle",
                    middle.width > 60f && middle.width < 96f)
            }
            rule.mainClock.advanceTimeBy(320)
            if (source.isEpisode) {
                rule.onNodeWithText("预览剧名").assertIsDisplayed().assertHasNoClickAction()
                rule.onNodeWithText("S2E3：预览集名").assertIsDisplayed()
            } else rule.onNodeWithText(source.name).assertIsDisplayed()
            rule.runOnIdle { gate.complete(Unit) }
            rule.mainClock.advanceTimeBy(600)
            rule.onNodeWithText("沙丘 2").assertExists()
            rule.onNodeWithText(source.name, substring = true).assertDoesNotExist()
            if (source.isEpisode) rule.onNodeWithText("预览剧名").assertDoesNotExist()
        } finally {
            gate.complete(Unit)
            scope.cancel()
            PageCache.clear()
        }
    }

    @Test fun matchedPosterDisablesMotionAndDropsLinksOnAccountChange() {
        images()
        var account by mutableStateOf("first")
        var motion: PosterMotion? = null
        var preview: String? = null
        var previewItem: Item? = null
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalMotionScale provides 0f) {
                    val nav = rememberNavController()
                    PosterMotionHost(nav, "server" to account) {
                        motion = LocalPosterMotion.current
                        NavHost(nav, Route.Home) {
                            posterComposable<Route.Home> {
                                MediaCard(Item("one", "One", "Movie"), "poster:one",
                                    { nav.navigate(Route.Detail("one", "Movie")) },
                                    Modifier.width(60.dp).testTag("source"))
                            }
                            posterComposable<Route.Detail> {
                                preview = posterPreview("one")
                                previewItem = detailPreview("one")
                                Box(Modifier.size(120.dp, 180.dp).sharedPoster("one")
                                    .background(Color.Green).testTag("target"))
                            }
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("source").performClick()
        rule.onNodeWithTag("target").assertIsDisplayed()
        assertEquals(Color.Green, rule.onNodeWithTag("target").captureToImage().toPixelMap()[60, 90])
        rule.runOnIdle {
            assertEquals("poster:one", preview)
            assertEquals("One", previewItem?.name)
            assertEquals(1, motion!!.links.size)
            account = "second"
        }
        rule.runOnIdle {
            assertNull(preview)
            assertNull(previewItem)
            assertTrue(motion!!.links.isEmpty())
        }
    }

    @Test fun unmatchedPosterAndDisabledMotionStillRenderImmediately() {
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalMotionScale provides 0f) {
                    val nav = rememberNavController()
                    PosterMotionHost(nav) {
                        NavHost(nav, Route.Detail("direct", "Movie")) {
                            posterComposable<Route.Detail> {
                                Box(Modifier.size(80.dp).sharedPoster("direct").background(Color.Green).testTag("direct"))
                            }
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("direct").assertIsDisplayed()
        assertEquals(Color.Green, rule.onNodeWithTag("direct").captureToImage().toPixelMap()[40, 40])
    }
}
