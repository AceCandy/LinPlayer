package xyz.linplayer.app

import android.app.Application
import android.net.Uri
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.*
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.tv.*
import xyz.linplayer.app.ui.pages.PhoneLiuyingFeed
import xyz.linplayer.app.ui.player.liuying.*
import org.robolectric.util.ReflectionHelpers
import androidx.media3.common.Player
import androidx.media3.common.util.ListenerSet
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import xyz.linplayer.app.ui.theme.LpTheme

/** 原生入口只消费入库候选；能力缺失与空库不能退回网页。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h873dp-mdpi", sdk = [36], application = Application::class)
class PhoneLiuyingTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: AppState
    private var showing by mutableStateOf(true)
    private lateinit var session: LiuyingPlaybackSession
    private val imageRequests = Collections.synchronizedList(mutableListOf<String>())
    private val mediaServer = ServerSocket(0)
    private val mediaConnections = Collections.synchronizedList(mutableListOf<Socket>())
    init {
        thread(isDaemon = true) {
            while (!mediaServer.isClosed) runCatching { mediaConnections += mediaServer.accept() }
        }
    }

    @After fun clean() {
        if (::session.isInitialized) session.close()
        PageCache.clear(); scope.cancel(); if (::app.isInitialized) app.bg.cancel()
        mediaServer.close()
        synchronized(mediaConnections) { mediaConnections.forEach { it.close() } }
    }

    private fun open(enabled: Boolean = true, populated: Boolean = false, more: Boolean = false, fontScale: Float = 1f, dark: Boolean = true,
        wrap: (FakeCore) -> CorePort = { it }, configure: FakeCore.() -> Unit = {}): FakeCore {
        PageCache.clear()
        val core = FakeCore().loggedIn().player().apply {
            ret("emby.views", arr(buildJsonObject { put("id", "adult"); put("is_adult", true) }))
            ret("emby.extCapabilities", buildJsonObject { put("liuying", buildJsonObject { put("enabled", enabled) }) })
            ret("emby.extLiuYingLabels", arr(
                buildJsonObject { put("name", "tg-3398168133"); put("title", "抖奶"); put("kind", "channel"); put("count", 9) },
                buildJsonObject { put("name", "side_breast_exposure"); put("title", "露左右"); put("kind", "exposure"); put("count", 4) },
            ))
            on("emby.extLiuYingItems") { a -> buildJsonObject {
                val first = (a.long("offset") ?: 0) == 0L
                put("items", if (populated && first) arr(*listOf("ly-work-1", "ly-work-2").map { id ->
                    buildJsonObject { put("item_id", id); put("title", id) }
                }.toTypedArray()) else arr())
                put("has_more", more && first)
            } }
            on("emby.itemDetail") { a -> buildJsonObject { put("id", a.str("item_id")); put("type_", "Movie"); put("name", "入库视频") } }
            ret("player.liuyingOpen", buildJsonObject { put("opened", true) })
            ret("player.liuyingStop", JsonNull)
            ret("player.liuyingCancel", JsonNull)
            ret("player.liuyingPrepare", buildJsonObject { put("skipped", true) })
            on("player.liuyingPlay") { buildJsonObject {
                put("playback_id", "test-${calls.count { it.first == "player.liuyingPlay" }}")
                put("play_url", "http://127.0.0.1:${mediaServer.localPort}/fixture.mp4"); put("media_source_id", "test")
            } }
        }
        core.configure()
        app = AppState(wrap(core), scope)
        runBlocking { app.enablePrivacyFiltering(); app.boot(); app.enterPrivateSpace() }
        FakeImages.install(RuntimeEnvironment.getApplication()) { imageRequests += it }
        rule.mainClock.autoAdvance = false
        rule.setContent { LpTheme(darkOverride = dark, privateSpace = true) { CompositionLocalProvider(LocalApp provides app,
            LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            if (showing) {
                session = rememberLiuyingSession()
                PhoneLiuyingFeed({ showing = false }, session)
            }
        } } }
        advance(rule, 1000)
        if (enabled && populated) assertNotNull("启动错误：${session.error}", session.slots.active)
        return core
    }

    /** 仅注入Media3采样与真实监听器，不把状态注入当作解码/首帧证据。 */
    private fun sample(seconds: Double, eof: Boolean = false, bufferedSeconds: Double = 0.0) {
        val player = session.slots.active!!.player
        val old = ReflectionHelpers.getField<Any>(player, "playbackInfo")
        val child = androidx.media3.exoplayer.source.SinglePeriodTimeline(3_660_000_000L, true, false, false, null,
            player.currentMediaItem!!)
        val oldTimeline = ReflectionHelpers.getField<androidx.media3.common.Timeline>(old, "timeline")
        val constructor = oldTimeline.javaClass.getDeclaredConstructor(
            Array<androidx.media3.common.Timeline>::class.java, Array<Any>::class.java,
            androidx.media3.exoplayer.source.ShuffleOrder::class.java).apply { isAccessible = true }
        val timeline = constructor.newInstance(arrayOf(child), ReflectionHelpers.getField<Array<Any>>(oldTimeline, "uids"),
            androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(1)) as androidx.media3.common.Timeline
        val copy = old.javaClass.getDeclaredMethod("copyWithTimeline", androidx.media3.common.Timeline::class.java)
            .apply { isAccessible = true }
        val info = copy.invoke(old, timeline)
        ReflectionHelpers.setField(player, "playbackInfo", info)
        val period = androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))
        ReflectionHelpers.setField(info, "timeline", timeline)
        ReflectionHelpers.setField(info, "periodId", period)
        ReflectionHelpers.setField(info, "loadingMediaPeriodId", period)
        ReflectionHelpers.setField(info, "positionUs", (seconds * 1_000_000).toLong())
        ReflectionHelpers.setField(info, "bufferedPositionUs", ((seconds + bufferedSeconds) * 1_000_000).toLong())
        ReflectionHelpers.setField(info, "playbackState", if (eof) Player.STATE_ENDED else Player.STATE_READY)
        runBlocking { session.tick() }
        if (eof) {
            val listeners: ListenerSet<Player.Listener> = ReflectionHelpers.getField(player, "listeners")
            listeners.sendEvent(Player.EVENT_PLAYBACK_STATE_CHANGED) { it.onPlaybackStateChanged(Player.STATE_ENDED) }
        }
    }

    private fun openFilters() {
        rule.onNodeWithTag("liuying.filter").performClick(); advance(rule, 300)
    }

    private fun closePopup() {
        rule.onNode(isPopup()).performTouchInput { click(Offset(width / 2f, 8f)) }; advance(rule, 300)
    }

    private fun moveVideo(next: Boolean, wait: Long = 600) {
        openFilters()
        rule.onNodeWithTag(if (next) "liuying.next" else "liuying.previous").performScrollTo().performClick()
        advance(rule, wait)
        rule.onNodeWithText("完成").performClick(); advance(rule, 300)
    }

    @Test fun shortTapOnlyOpensFavoritesAndLongPressOnlySwitchesFeed() {
        val core = open(populated = true)
        rule.onNodeWithTag("liuying.bankai").performTouchInput { click() }; advance(rule, 400)
        rule.onNodeWithTag("liuying.favoriteSheet").assertIsDisplayed()
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
        assertEquals("normal", core.calls.last { it.first == "emby.extLiuYingItems" }.second.str("feed"))
        closePopup()
        rule.onNodeWithTag("liuying.bankai").performTouchInput { longClick() }; advance(rule, 400)
        rule.onNodeWithTag("liuying.favoriteSheet").assertDoesNotExist()
        assertEquals("adult", core.calls.last { it.first == "emby.extLiuYingItems" }.second.str("feed"))
        rule.onNodeWithTag("liuying.filter").assertDoesNotExist()
    }

    @Test fun tappingPictureTogglesPauseAndShowsOnlyCentralPlayIndicator() {
        val core = open(populated = true)
        rule.onNodeWithTag("liuying.paused").assertDoesNotExist()
        rule.onNodeWithText("上下滑动切换").assertDoesNotExist()
        rule.onNodeWithText("收藏").assertDoesNotExist()
        rule.onNodeWithTag("liuying.gesture").performTouchInput { click(center) }; advance(rule, 300)
        assertFalse(session.userWantsPlay)
        rule.onNodeWithTag("liuying.paused").assertIsDisplayed()
        rule.onNodeWithTag("liuying.gesture").performTouchInput { click(center) }; advance(rule, 300)
        assertTrue(session.userWantsPlay)
        rule.onNodeWithTag("liuying.paused").assertDoesNotExist()
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
        assertFalse(core.calls.any { it.first == "player.setPause" })
    }

    @Test fun speedIsIndependentAndSurvivesNextVideoAndReentry() {
        var saved = 1.25
        val core = open(populated = true) {
            on("player.getPlaybackPrefs") { buildJsonObject {
                put("default_speed", 3.0); put("liuying_speed", saved); put("sub_enabled", true)
            } }
            ret("player.setSpeed", JsonNull)
            on("player.setPlaybackPrefs") { a ->
                assertNull(a?.get("default_speed"))
                saved = a.dbl("liuying_speed")!!
                JsonNull
            }
        }
        rule.onNodeWithText("1.25×").assertIsDisplayed()
        rule.onNodeWithTag("liuying.speed").performTouchInput { click() }; advance(rule, 300)
        rule.onNodeWithTag("liuying.speed.2.0").performClick(); advance(rule, 400)
        assertEquals(2.0, saved, 0.0)
        assertEquals(2f, session.slots.active!!.player.playbackParameters.speed)
        rule.onNodeWithText("2.0×").assertIsDisplayed()
        moveVideo(true)
        rule.onNodeWithText("2.0×").assertIsDisplayed()
        rule.runOnIdle { showing = false; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }; advance(rule, 300)
        rule.runOnIdle { showing = true; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }; advance(rule, 800)
        rule.onNodeWithText("2.0×").assertIsDisplayed()
        assertFalse(core.calls.any { it.first == "player.play" || it.first == "player.setSpeed" })
        assertFalse(core.calls.any { it.first == "player.setPause" })
    }

    @Test fun failedSpeedSaveRestoresActualSpeedWithoutChangingDisplay() {
        val core = open(populated = true) {
            ret("player.setSpeed", JsonNull)
            on("player.setPlaybackPrefs") { throw xyz.linplayer.app.core.CoreException("E_INTERNAL", "保存失败", false) }
        }
        rule.onNodeWithTag("liuying.speed").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.speed.2.0").performClick(); advance(rule, 300)
        rule.onNodeWithText("1.0×").assertIsDisplayed()
        assertEquals(1f, session.slots.active!!.player.playbackParameters.speed)
    }

    @Test fun nextVideoWaitsForPendingFeedSpeedSave() {
        val save = CompletableDeferred<Unit>()
        var saved = 1.0
        val core = open(populated = true, wrap = { fake -> object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "player.setPlaybackPrefs") save.await()
                return fake.callJson(command, args, onPartial)
            }
        } }) {
            on("player.getPlaybackPrefs") { buildJsonObject { put("default_speed", 3.0); put("liuying_speed", saved) } }
            ret("player.setSpeed", JsonNull)
            on("player.setPlaybackPrefs") { a -> saved = a.dbl("liuying_speed")!!; JsonNull }
        }
        rule.onNodeWithTag("liuying.speed").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.speed.2.0").performClick(); advance(rule, 300)
        moveVideo(true)
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
        rule.runOnIdle { save.complete(Unit) }; advance(rule, 800)
        rule.onNodeWithText("2.0×").assertIsDisplayed()
        assertEquals(2, core.calls.count { it.first == "player.liuyingPlay" })
        assertEquals(2f, session.slots.active!!.player.playbackParameters.speed)
    }

    @Test fun backgroundDuringSpeedSaveDefersPlayUntilLatestTargetCanResume() = verifyBackgroundDuringSwitch(true)

    @Test fun lockedScreenDuringStopDefersPlayUntilLatestTargetCanResume() = verifyBackgroundDuringSwitch(false)

    private fun verifyBackgroundDuringSwitch(waitForSpeed: Boolean) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blockedCommand = if (waitForSpeed) "player.setPlaybackPrefs" else "player.liuyingStop"
        val core = open(populated = true, wrap = { fake -> object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == blockedCommand && !started.isCompleted) {
                    started.complete(Unit); release.await()
                }
                return fake.callJson(command, args, onPartial)
            }
        } }) { ret("player.setPlaybackPrefs", JsonNull) }
        try {
            assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
            rule.runOnIdle {
                if (waitForSpeed) session.changeSpeed(2.0)
                session.select(LiuyingSelection("blocked", "blocked", 1), emptyList())
            }; advance(rule, 300)
            assertTrue(started.isCompleted)
            rule.runOnIdle {
                session.environment(resumed = !waitForSpeed, screenUnlocked = waitForSpeed)
            }; advance(rule, 100)
            assertFalse(session.foreground && session.unlocked)
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 800)
            assertFalse(session.foreground && session.unlocked)
            assertEquals(core.calls.filter { it.first == "player.liuyingPlay" }.map { it.second.str("item_id") }.toString(),
                1, core.calls.count { it.first == "player.liuyingPlay" })
            rule.runOnIdle {
                session.select(LiuyingSelection("skipped", "skipped", 1), emptyList())
                session.select(LiuyingSelection("latest", "latest", 1), emptyList())
            }; advance(rule, 300)
            assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
            rule.runOnIdle { session.environment(true, true) }; advance(rule, 800)
            assertEquals(listOf("ly-work-1", "latest"),
                core.calls.filter { it.first == "player.liuyingPlay" }.map { it.second.str("item_id") })
        } finally { release.complete(Unit) }
    }

    @Test fun favoriteListSelectsExactVideoLoopsAndSwipesIntoNormalRandomFeed() {
        val core = open(populated = true, more = true)
        openFilters()
        rule.onNodeWithTag("liuying.label.tg-3398168133").performScrollTo().performClick(); advance(rule, 300)
        rule.onNodeWithText("完成").performClick(); advance(rule, 300)
        core.on("emby.extLiuYingItems") { a ->
            val offset = a.long("offset")!!.toInt()
            buildJsonObject {
                val duplicate = if (!a.bool("favorite") && offset == 0) listOf(buildJsonObject {
                    put("item_id", "ly-work-14"); put("title", "当前收藏"); put("favorite", true)
                }) else emptyList()
                put("items", arr(*(duplicate + (1..2).map { buildJsonObject {
                    val favorite = a.bool("favorite")
                    put("item_id", if (favorite) "ly-work-${offset + it + 10}" else "random-${offset + it}")
                    put("title", if (favorite) "收藏${offset + it}" else "随机${offset + it}")
                    put("favorite", favorite)
                } }).toTypedArray())); put("has_more", offset == 0)
            }
        }
        rule.onNodeWithTag("liuying.bankai").performClick(); advance(rule, 400)
        val query = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        assertEquals("", query.str("channel")); assertEquals("", query.str("exposure")); assertTrue(query.bool("favorite"))
        // 收藏列表末尾进入视口即自动续页。
        rule.onNodeWithTag("liuying.favoriteList").performScrollToIndex(2); advance(rule, 300)
        val favoriteCount = core.calls.count { it.first == "emby.extLiuYingItems" && it.second.bool("favorite") }
        rule.onNode(isPopup()).captureRoboImage("build/liuying-mobile/favorite-list-synthetic.png")
        rule.onNodeWithTag("liuying.favoriteItem.ly-work-14").performScrollTo().performClick(); advance(rule, 600)
        rule.onNodeWithTag("liuying.favoriteSheet").assertDoesNotExist()
        assertEquals("ly-work-14", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        rule.onNodeWithText("循环播放").assertExists()
        val normalQuery = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        assertFalse(normalQuery.bool("favorite"))
        assertEquals("", normalQuery.str("channel")); assertEquals("", normalQuery.str("exposure"))
        assertEquals("normal", normalQuery.str("feed")); assertEquals("dance", normalQuery.str("tab"))
        assertEquals(0L, normalQuery.long("offset"))
        assertNotEquals(query.str("seed"), normalQuery.str("seed"))
        val played = core.calls.count { it.first == "player.liuyingPlay" }
        rule.runOnIdle { sample(3660.0, eof = true) }; advance(rule, 800)
        assertEquals("收藏点选后片尾只能循环当前", played, core.calls.count { it.first == "player.liuyingPlay" })
        rule.onNodeWithTag("liuying.gesture").performTouchInput { swipeUp() }; advance(rule, 800)
        assertEquals("random-1", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        assertTrue(core.calls.any { it.first == "emby.extLiuYingItems" &&
            !it.second.bool("favorite") && it.second.long("offset") == 3L })
        assertEquals(favoriteCount, core.calls.count { it.first == "emby.extLiuYingItems" && it.second.bool("favorite") })
        rule.onNodeWithText("循环播放").assertExists()
        assertFalse(core.calls.any { it.first == "player.liuyingPlay" && it.second.str("item_id") == "ly-work-13" })
    }

    @Test fun favoriteHandoffSkipsDuplicateOnlyPageAndKeepsRawOffset() = verifyDuplicateOnlyHandoff(true)
    @Test fun favoriteHandoffWithNoOtherVideoStopsFetching() = verifyDuplicateOnlyHandoff(false)

    private fun verifyDuplicateOnlyHandoff(more: Boolean) {
        val core = open(populated = true)
        core.on("emby.extLiuYingItems") { a ->
            val offset = a.long("offset")!!.toInt()
            buildJsonObject {
                put("items", arr(buildJsonObject {
                    put("item_id", if (offset == 0) "ly-work-2" else "random-other")
                    put("title", if (offset == 0) "当前收藏" else "其它视频")
                    put("favorite", offset == 0)
                }))
                put("has_more", !a.bool("favorite") && offset == 0 && more)
            }
        }
        rule.onNodeWithTag("liuying.bankai").performClick(); advance(rule, 300)
        val before = core.calls.size
        rule.onNodeWithTag("liuying.favoriteItem.ly-work-2").performScrollTo().performClick(); advance(rule, 600)
        assertEquals("ly-work-2", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        val queries = core.calls.drop(before).filter { it.first == "emby.extLiuYingItems" }
        assertEquals(if (more) listOf(0L, 1L) else listOf(0L), queries.map { it.second.long("offset") })
        assertEquals(1, queries.map { it.second.str("seed") }.distinct().size)
        val plays = core.calls.count { it.first == "player.liuyingPlay" }
        rule.onNodeWithTag("liuying.gesture").performTouchInput { swipeUp() }; advance(rule, 800)
        if (more) {
            assertEquals("random-other", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        } else {
            assertEquals(plays, core.calls.count { it.first == "player.liuyingPlay" })
            assertEquals(1, core.calls.drop(before).count { it.first == "emby.extLiuYingItems" })
            rule.onNodeWithText("暂无可播放的入库资源").assertExists()
        }
        rule.onNodeWithText("循环播放").assertExists()
    }

    @Test fun favoriteGridUsesNormalColumnsCoversAndOnlyTitles() = verifyFavoriteGrid(true)
    @Test fun lightFavoriteGridKeepsDarkSheetReadable() = verifyFavoriteGrid(false)

    @Test fun missingFavoriteCoverStillAllowsExactPlayback() {
        val core = open(populated = true)
        val context = RuntimeEnvironment.getApplication()
        coil3.SingletonImageLoader.setUnsafe(coil3.ImageLoader.Builder(context)
            .coroutineContext(Dispatchers.Unconfined).components {
                add(object : coil3.fetch.Fetcher.Factory<coil3.Uri> {
                    override fun create(data: coil3.Uri, options: coil3.request.Options, imageLoader: coil3.ImageLoader) =
                        coil3.fetch.Fetcher { error("fixture missing cover") }
                })
            }.build())
        rule.onNodeWithTag("liuying.bankai").performClick(); advance(rule, 500)
        // 面板在 Popup 窗口里，截一次图让它真正绘制，图片请求才会开始并走到失败
        rule.onNodeWithTag("liuying.favoriteList").captureToImage(); advance(rule, 500)
        rule.onAllNodesWithText("暂无封面").assertCountEquals(2)
        rule.onNodeWithTag("liuying.favoriteItem.ly-work-2").performScrollTo().performClick(); advance(rule, 600)
        assertEquals("ly-work-2", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        rule.onNodeWithText("循环播放").assertExists()
    }

    private fun verifyFavoriteGrid(dark: Boolean) {
        val columns = 3
        val core = open(populated = true, dark = dark)
        core.on("emby.extLiuYingItems") { buildJsonObject {
            put("items", arr(*(1..8).map { index -> buildJsonObject {
                put("item_id", "favorite-$index"); put("title", "收藏标题$index")
                put("author", "不显示作者"); put("favorite", true)
            } }.toTypedArray()))
            put("has_more", false)
        } }
        rule.onNodeWithTag("liuying.bankai").performClick(); advance(rule, 500)
        rule.onNodeWithText("收藏的流影").assertDoesNotExist()
        rule.onNodeWithText("完成").assertDoesNotExist()
        rule.onNodeWithTag("liuying.returnAll").assertDoesNotExist()
        rule.onNodeWithText("全部频道与衣着 · 点选后只在收藏范围内播放").assertDoesNotExist()
        rule.onNodeWithText("@不显示作者").assertDoesNotExist()
        val bounds = (1..columns + 1).map {
            rule.onNodeWithTag("liuying.favoriteItem.favorite-$it").getUnclippedBoundsInRoot()
        }
        bounds.take(columns).forEach { assertEquals(bounds[0].top, it.top) }
        assertTrue(bounds.last().top > bounds[0].top)
        val imageHeight = (bounds[0].right - bounds[0].left).value * 1.5f *
            RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val expectedHeight = listOf(120, 220, 330, 480, 720).firstOrNull { it >= imageHeight.roundToInt() } ?: 1080
        (1..columns + 1).forEach { index ->
            assertTrue("封面经过既有图片代理加载", imageRequests.any { request ->
                val uri = Uri.parse(request)
                Uri.parse(uri.getQueryParameter("src")).path == "/Items/favorite-$index/Images/Primary" &&
                    uri.getQueryParameter("h") == expectedHeight.toString()
            })
        }
        rule.onNodeWithTag("liuying.favoriteList").performScrollToIndex(7); advance(rule, 400)
        rule.onNodeWithTag("liuying.favoriteItem.favorite-8").assertIsDisplayed()
        rule.onNodeWithTag("liuying.favoriteList").performScrollToIndex(0); advance(rule, 300)
        rule.onNode(isPopup()).captureRoboImage("build/liuying-mobile/favorite-grid-${if (dark) "dark" else "light"}-synthetic.png")
        closePopup()
        rule.onNodeWithTag("liuying.favoriteSheet").assertDoesNotExist()
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
    }

    @Test fun favoriteListFailureRetriesSamePageAndTabSwitchStaysInCurrentFeed() {
        val core = open(populated = true)
        var fail = true
        core.on("emby.extLiuYingItems") { a ->
            if (fail) throw xyz.linplayer.app.core.CoreException("E_NETWORK", "收藏加载失败", true)
            buildJsonObject {
                put("items", arr(buildJsonObject { put("item_id", "ly-work-9"); put("title", "已收藏") }))
                put("has_more", false)
            }
        }
        rule.onNodeWithTag("liuying.bankai").performClick(); advance(rule, 300)
        val failed = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        rule.runOnIdle { fail = false }
        rule.onNodeWithTag("liuying.favoriteRetry").performScrollTo().performClick(); advance(rule, 300)
        assertEquals(failed, core.calls.last { it.first == "emby.extLiuYingItems" }.second)
        rule.onNodeWithTag("liuying.favoriteTab.live").performClick(); advance(rule, 300)
        val live = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        assertEquals("normal", live.str("feed")); assertEquals("live", live.str("tab")); assertTrue(live.bool("favorite"))
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
        rule.onNodeWithTag("liuying.favoriteItem.ly-work-9").performScrollTo().performClick(); advance(rule, 600)
        rule.onNodeWithTag("liuying.filter").assertDoesNotExist()
    }

    @Test fun favoriteEmptySheetClosesWithoutPlaybackAndAdultScopeStaysSeparate() {
        val core = open(populated = true)
        core.ret("emby.extLiuYingItems", buildJsonObject { put("items", arr()); put("has_more", false) })
        rule.onNodeWithTag("liuying.bankai").performClick(); advance(rule, 300)
        rule.onNodeWithText("暂无收藏视频").assertIsDisplayed()
        rule.onNode(isPopup()).performTouchInput { swipeUp() }; advance(rule, 300)
        rule.runOnIdle {
            val manager = Shadow.extract<ShadowWindowManagerImpl>(
                RuntimeEnvironment.getApplication().getSystemService(WindowManager::class.java))
            manager.views.last().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            manager.views.last().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        advance(rule, 300)
        rule.onNodeWithTag("liuying.favoriteSheet").assertDoesNotExist()
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
        assertFalse(core.calls.any { it.first == "player.liuyingStop" || it.first == "player.setPause" })
        rule.onNodeWithTag("liuying.bankai").performTouchInput { longClick() }; advance(rule, 400)
        rule.onNodeWithTag("liuying.bankai").performClick(); advance(rule, 300)
        val query = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        assertEquals("adult", query.str("feed")); assertEquals("", query.str("tab")); assertTrue(query.bool("favorite"))
        rule.onNodeWithTag("liuying.favoriteTab.dance").assertDoesNotExist()
        rule.onNode(isPopup()).performTouchInput { click(Offset(width / 2f, 8f)) }; advance(rule, 300)
        rule.onNodeWithTag("liuying.favoriteSheet").assertDoesNotExist()
        assertTrue(showing)
    }

    @Test fun lateFavoriteTabResponseCannotReplaceCurrentList() {
        val old = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val core = open(populated = true, wrap = { fake -> object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.extLiuYingItems" && args.bool("favorite") && args.str("tab") == "dance") {
                    started.complete(Unit)
                    return withContext(NonCancellable) {
                        old.await()
                        buildJsonObject { put("items", arr(buildJsonObject {
                            put("item_id", "ly-work-old"); put("title", "过期舞蹈收藏")
                        })); put("has_more", false) }
                    }
                }
                return fake.callJson(command, args, onPartial)
            }
        } })
        rule.onNodeWithTag("liuying.bankai").performClick(); advance(rule, 300)
        assertTrue("旧收藏请求确实已挂起", started.isCompleted)
        rule.onNodeWithTag("liuying.favoriteTab.live").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.favoriteItem.ly-work-1").assertIsDisplayed()
        rule.runOnIdle { old.complete(Unit) }; advance(rule, 500)
        rule.onNodeWithText("过期舞蹈收藏").assertDoesNotExist()
        rule.onNodeWithTag("liuying.favoriteItem.ly-work-1").assertIsDisplayed()
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-port-mdpi")
    fun liveFavoriteSelectionLoopsAndSwipeReturnsToRandomVideos() = verifyFavoriteRandomHandoff(false)
    @Test
    @Config(qualifiers = "w873dp-h393dp-land-mdpi")
    fun adultFavoriteSelectionLoopsAndSwipeReturnsToRandomVideos() = verifyFavoriteRandomHandoff(true)

    private fun selectUnfilteredFeed(adult: Boolean) {
        if (adult) rule.onNodeWithTag("liuying.bankai").performTouchInput { longClick() }
        else rule.onNodeWithText("直播").performClick()
        advance(rule, 600)
        rule.onNodeWithTag("liuying.filter").assertDoesNotExist()
    }

    private fun verifyFavoriteRandomHandoff(adult: Boolean) {
        val core = open(populated = true, fontScale = 1.3f)
        selectUnfilteredFeed(adult)
        rule.onNodeWithTag("liuying.bankai").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.favoriteItem.ly-work-2").performScrollTo().performClick(); advance(rule, 600)
        assertEquals("ly-work-2", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        rule.onNodeWithText("循环播放").assertExists()
        rule.onNodeWithTag("liuying.gesture").performTouchInput { swipeUp() }; advance(rule, 800)
        rule.onNodeWithTag("liuying.favoriteSheet").assertDoesNotExist()
        val query = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        assertFalse(query.bool("favorite"))
        assertEquals(if (adult) "adult" else "normal", query.str("feed"))
        assertEquals(if (adult) "" else "live", query.str("tab"))
        assertEquals("", query.str("channel")); assertEquals("", query.str("exposure"))
        assertEquals("ly-work-1", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        rule.onNodeWithTag("liuying.filter").assertDoesNotExist()
        assertTrue(showing)
    }

    @Test fun liveEmptyPageCanSwipeBack() = verifyEmptyPageSwipeBack(false)
    @Test fun adultEmptyPageCanSwipeBack() = verifyEmptyPageSwipeBack(true)

    private fun verifyEmptyPageSwipeBack(adult: Boolean) {
        val core = open(populated = true, more = true)
        selectUnfilteredFeed(adult)
        core.ret("emby.extLiuYingItems", buildJsonObject { put("items", arr()); put("has_more", false) })
        repeat(2) {
            rule.onNodeWithTag("liuying.gesture").performTouchInput {
                swipe(Offset(width * .4f, height * .65f), Offset(width * .4f, height * .3f))
            }; advance(rule, 600)
        }
        rule.onNodeWithText("暂无可播放的入库资源").assertIsDisplayed()
        val requests = core.calls.count { it.first == "emby.extLiuYingItems" }
        rule.onNodeWithTag("liuying.gesture").performTouchInput { click(center) }; advance(rule, 300)
        rule.onNodeWithTag("liuying.gesture").performTouchInput {
            swipe(Offset(width * .4f, height * .65f), Offset(width * .4f, height * .3f))
        }; advance(rule, 300)
        rule.onNodeWithText("暂无可播放的入库资源").assertIsDisplayed()
        rule.onNodeWithTag("liuying.gesture").performTouchInput {
            swipe(Offset(width * .4f, height * .3f), Offset(width * .4f, height * .65f))
        }; advance(rule, 600)
        assertEquals("ly-work-2", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        assertEquals(requests, core.calls.count { it.first == "emby.extLiuYingItems" })
        assertFalse(core.calls.any { it.first == "player.seek" || it.first == "player.setPause" })
    }

    @Test fun missingCapabilityDoesNotRequestVideosOrLoadWebPage() {
        val core = open(enabled = false)
        rule.onNodeWithText("服务器未提供流影能力").assertIsDisplayed()
        rule.onNodeWithTag("liuying.web").assertDoesNotExist()
        assertFalse(core.calls.any { it.first == "emby.extLiuYingItems" || it.first == "player.liuyingPlay" })
        assertEquals(1, core.calls.count { it.first == "emby.extCapabilities" })
        assertNull(core.calls.single { it.first == "emby.extCapabilities" }.second?.get("refresh"))
    }

    @Test fun filterSheetStartsHiddenAndOpeningDoesNotRestartPlayback() {
        val core = open(populated = true)
        rule.onNodeWithTag("liuying.sheet").assertDoesNotExist()
        rule.onNodeWithTag("liuying.label.tg-3398168133").assertDoesNotExist()
        rule.onNodeWithTag("liuying.filter").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.sheet").assertIsDisplayed()
        rule.onNodeWithText("完成").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.sheet").assertDoesNotExist()
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
        assertFalse(core.calls.any { it.first == "player.liuyingStop" })
    }

    @Test fun sheetBackAndOutsideCloseWithoutExitingOrRestarting() {
        val core = open(populated = true)
        openFilters()
        rule.onNodeWithText("筛选视频").performTouchInput { click() }; advance(rule, 300)
        rule.onNodeWithTag("liuying.sheet").assertIsDisplayed()
        rule.runOnIdle {
            val manager = Shadow.extract<ShadowWindowManagerImpl>(
                RuntimeEnvironment.getApplication().getSystemService(WindowManager::class.java))
            manager.views.last().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            manager.views.last().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        advance(rule, 300)
        rule.onNodeWithTag("liuying.sheet").assertDoesNotExist()
        assertTrue(showing)
        openFilters()
        rule.onNode(isPopup()).performTouchInput { click(Offset(width / 2f, 8f)) }; advance(rule, 300)
        rule.onNodeWithTag("liuying.sheet").assertDoesNotExist()
        assertTrue(showing)
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
        assertFalse(core.calls.any { it.first == "player.liuyingStop" })
    }

    /** 真实流影入口和播放器组合；宿主占位背景不是解码画面，截图仅验证完整控件接线。 */
    private fun nativeLayout(name: String, fontScale: Float = 1f) {
        val core = open(populated = true, fontScale = fontScale)
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { sample(1.0) }; advance(rule, 300)
        rule.runOnIdle { sample(1.25) }; advance(rule, 300)
        for (tag in listOf("favorite", "filter", "mode", "sound", "progress"))
            rule.onNodeWithTag("liuying.$tag").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/liuying-mobile/$name-no-decoded-frame.png")
        openFilters()
        rule.onNodeWithTag("liuying.next").performScrollTo().performTouchInput { click() }; advance(rule, 600)
        rule.onNodeWithTag("liuying.sheet").assertIsDisplayed()
        assertEquals("ly-work-2", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        assertEquals(2, core.calls.count { it.first == "emby.extLiuYingItems" })
        rule.onNodeWithText("完成").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.sheet").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w873dp-h393dp-land-mdpi")
    fun actualLandscapePageKeepsAllActionsAndSheetNextReachable() = nativeLayout("native-landscape")

    @Test
    @Config(qualifiers = "w320dp-h640dp-port-mdpi")
    fun actualSmallPageLargeTextKeepsAllActionsReachable() = nativeLayout("native-small-large-text", 1.3f)

    @Test fun favoritesAndClearRemainUsableAcrossFilterReloads() {
        val core = open(populated = true)
        openFilters()
        rule.onNodeWithTag("liuying.label.tg-3398168133").performScrollTo().performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.label.tg-3398168133").assertIsSelected()
        rule.onNodeWithTag("liuying.favorites").performScrollTo().performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.favorites").assertIsSelected()
        assertTrue(core.calls.last { it.first == "emby.extLiuYingItems" }.second.bool("favorite"))
        rule.onNodeWithTag("liuying.clear").performScrollTo().performClick(); advance(rule, 300)
        val query = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        assertEquals("", query.str("channel")); assertEquals("", query.str("exposure"))
        assertFalse(query.bool("favorite"))
        rule.onNodeWithTag("liuying.favorites").assertIsNotSelected()
        rule.onNodeWithTag("liuying.sheet").assertIsDisplayed()
    }

    @Test fun sheetDraggingDoesNotSeekOrSwitchAndProgressOnlySeeks() {
        val core = open(populated = true)
        rule.runOnIdle { sample(30.0) }; advance(rule, 300)
        rule.runOnIdle { sample(30.25) }; advance(rule, 300)
        val position = session.slots.active!!.player.currentPosition
        openFilters()
        rule.onNodeWithTag("liuying.actions").performTouchInput { swipeUp(); swipeDown() }; advance(rule, 300)
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
        assertEquals(position, session.slots.active!!.player.currentPosition)
        rule.onNodeWithText("完成").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.progress").performTouchInput {
            swipe(Offset(width * .2f, height / 2f), Offset(width * .7f, height / 2f))
        }; advance(rule, 400)
        assertTrue(session.slots.active!!.player.currentPosition > position)
        assertFalse(core.calls.any { it.first == "player.seek" })
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
    }

    @Test fun emptyLibraryAndFeedSwitchStayNativeAndUseCachedCapability() {
        val core = open()
        rule.onNodeWithText("暂无可播放的入库资源").assertIsDisplayed()
        assertEquals("dance", core.calls.last { it.first == "emby.extLiuYingItems" }.second.str("tab"))
        rule.onNodeWithTag("liuying.bankai").performTouchInput { longClick() }; advance(rule, 400)
        val args = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        assertEquals("adult", args.str("feed")); assertEquals("", args.str("tab"))
        assertEquals("", args.str("channel")); assertEquals("", args.str("exposure"))
        assertEquals(1, core.calls.count { it.first == "emby.extCapabilities" })
        assertFalse(core.calls.any { it.first == "player.liuyingPlay" })
    }

    @Test fun channelAndExposureIntersectAndSourceSwitchClearsBoth() {
        val core = open()
        openFilters()
        rule.onNodeWithTag("liuying.label.tg-3398168133").performScrollTo().performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.sheet").assertIsDisplayed()
        rule.onNodeWithTag("liuying.label.side_breast_exposure").performScrollTo().performClick(); advance(rule, 300)
        val combined = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        assertEquals("tg-3398168133", combined.str("channel"))
        assertEquals("side_breast_exposure", combined.str("exposure"))
        assertEquals(3, core.calls.count { it.first == "emby.extLiuYingItems" })
        rule.onNodeWithText("完成").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.bankai").performTouchInput { longClick() }; advance(rule, 300)
        val adult = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        assertEquals("", adult.str("channel")); assertEquals("", adult.str("exposure"))
        assertEquals(4, core.calls.count { it.first == "emby.extLiuYingItems" })
    }

    @Test fun favoriteChangesOnlyAfterServerSuccess() {
        val core = open(populated = true)
        core.on("emby.setFavorite") { throw xyz.linplayer.app.core.CoreException("E_NETWORK", "收藏失败", true) }
        rule.onNodeWithTag("liuying.favorite").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.favorite").assertIsNotSelected()
        core.ret("emby.setFavorite", JsonNull)
        rule.onNodeWithTag("liuying.favorite").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.favorite").assertIsSelected()
        val write = core.calls.last { it.first == "emby.setFavorite" }.second
        assertEquals("ly-work-1", write.str("item_id")); assertTrue(write.bool("fav"))
        assertEquals(2, core.calls.count { it.first == "emby.setFavorite" })
    }

    @Test fun completedVideoAdvancesOnceThroughExistingStopBarrier() {
        val core = open(populated = true)
        rule.runOnIdle { sample(3658.0) }; advance(rule, 300)
        rule.runOnIdle { sample(3658.25) }; advance(rule, 300)
        rule.runOnIdle { sample(3660.0, eof = true) }; advance(rule, 800)
        assertEquals(2, core.calls.count { it.first == "player.liuyingPlay" })
        assertEquals("ly-work-2", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        val play = core.calls.indexOfLast { it.first == "player.liuyingPlay" }
        assertTrue(core.calls.indexOfLast { it.first == "player.liuyingStop" } in 0 until play)
        rule.runOnIdle { core.events.tryEmit(xyz.linplayer.app.core.CoreEvent("player.status", buildJsonObject {
            put("item_id", "ly-work-1"); put("position", 3660.0); put("duration", 3660.0); put("eof", true)
        })) }
        advance(rule, 500)
        assertEquals(2, core.calls.count { it.first == "player.liuyingPlay" })
    }

    @Test fun verticalSwipeMovesWithinSelectedFeedAndCanReturn() {
        val core = open(populated = true)
        rule.onNodeWithTag("liuying.gesture").performTouchInput {
            swipe(Offset(width * .45f, height * .3f), Offset(width * .45f, height * .65f))
        }; advance(rule, 300)
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
        rule.onNodeWithTag("liuying.gesture").performTouchInput {
            swipe(Offset(width * .45f, height * .65f), Offset(width * .45f, height * .3f))
        }; advance(rule, 800)
        assertEquals("ly-work-2", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        rule.onNodeWithTag("liuying.gesture").performTouchInput {
            swipe(Offset(width * .45f, height * .3f), Offset(width * .45f, height * .65f))
        }; advance(rule, 800)
        assertEquals("ly-work-1", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        assertEquals(2, core.calls.count { it.first == "emby.extLiuYingItems" })
        assertFalse(core.calls.any { it.first == "player.setVolume" })
    }

    private fun swipeVideo(next: Boolean = true) {
        rule.onNodeWithTag("liuying.gesture").performTouchInput {
            val start = Offset(width * .45f, height * .65f)
            val end = Offset(width * .45f, height * .3f)
            if (next) swipe(start, end) else swipe(end, start)
        }; advance(rule, 800)
    }

    @Test fun firstPlaybackFetchesNextBatchWithoutApproachingPageEnd() {
        val core = open {
            on("emby.extLiuYingItems") { a -> buildJsonObject {
                val offset = a.long("offset") ?: 0
                put("items", arr(*(0 until 12).map { n -> buildJsonObject {
                    put("item_id", "early-${offset + n}"); put("title", "early-${offset + n}")
                } }.toTypedArray()))
                put("has_more", true)
            } }
        }
        advance(rule, 500)
        assertEquals("early-0", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        assertEquals(listOf(0L, 12L), core.calls.filter { it.first == "emby.extLiuYingItems" }.map { it.second.long("offset") })
        advance(rule, 2000)
        assertEquals(2, core.calls.count { it.first == "emby.extLiuYingItems" })
    }

    @Test fun samplingAndPauseNeverSendLiuyingProgressButStillStop() {
        val core = open(populated = true)
        rule.runOnIdle {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(11))
            scope.launch { session.tick() }
        }
        advance(rule, 300)
        rule.runOnIdle {
            session.togglePause()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(11))
            scope.launch { session.tick() }
        }
        advance(rule, 300)
        assertFalse(core.calls.any { it.first == "player.liuyingProgress" })
        rule.runOnIdle { showing = false }; advance(rule, 500)
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
        assertEquals(1, core.calls.count { it.first == "player.liuyingStop" })
    }

    @Test fun twoSeenItemsAndThreeFutureItemsSurviveDirectionChangeWithoutReprepare() {
        val core = open {
            on("emby.extLiuYingItems") { a -> buildJsonObject {
                put("items", if (a.long("offset") == 0L) arr(*(0 until 12).map { n ->
                    buildJsonObject { put("item_id", "window-$n"); put("title", "window-$n") }
                }.toTypedArray()) else arr())
                put("has_more", true)
            } }
            on("player.liuyingPrepare") { a -> buildJsonObject {
                put("request_id", a.str("request_id")); put("can_preload", true)
                put("play_url", "http://127.0.0.1:${mediaServer.localPort}/${a.str("item_id")}.mp4")
                put("media_source_id", "test")
            } }
            on("player.liuyingPlay") { a -> buildJsonObject {
                put("playback_id", "test-${calls.count { it.first == "player.liuyingPlay" }}")
                put("consumed_request_id", a.str("request_id"))
                put("play_url", "http://127.0.0.1:${mediaServer.localPort}/${a.str("item_id")}.mp4")
                put("media_source_id", "test")
            } }
            on("player.liuyingStop") { a -> buildJsonObject {
                put("stopped", true); put("retained", !a.str("retain_request_id").isNullOrEmpty())
            } }
        }
        swipeVideo(); swipeVideo()
        val before = core.calls.count { it.first == "player.liuyingPrepare" }
        val seen = core.calls.filter { it.first == "player.liuyingStop" }.associate {
            it.second.str("item_id") to it.second.str("retain_request_id")
        }
        val prepared: Map<String, LiuyingMedia> = ReflectionHelpers.getField(session, "prepared")
        assertEquals(setOf("window-0", "window-1", "window-3", "window-4", "window-5"), prepared.values.map { it.item }.toSet())
        swipeVideo(false); swipeVideo(false)
        val replay = core.calls.last { it.first == "player.liuyingPlay" }.second
        assertEquals("window-0", replay.str("item_id"))
        assertEquals(seen["window-0"], replay.str("request_id"))
        assertEquals(before, core.calls.count { it.first == "player.liuyingPrepare" })
        assertEquals(0L, session.slots.active!!.player.currentPosition)
        assertFalse(core.calls.any { it.first == "player.liuyingProgress" })
    }

    @Test fun warmStartsNearBeginningWithThreeSecondsBufferedAndYieldsWhenLow() {
        val core = open(populated = true) {
            on("player.liuyingPrepare") { a -> buildJsonObject {
                put("request_id", a.str("request_id")); put("can_preload", true)
                put("play_url", "http://127.0.0.1:${mediaServer.localPort}/${a.str("item_id")}.mp4")
                put("media_source_id", "test")
            } }
            ret("player.liuyingWarm", buildJsonObject { put("matched", true) })
        }
        rule.runOnIdle { sample(1.0, bufferedSeconds = 3.0) }
        advance(rule, 100)
        assertTrue(core.calls.any { it.first == "player.liuyingWarm" && it.second.bool("enabled") })
        rule.runOnIdle { sample(1.5, bufferedSeconds = .5) }
        advance(rule, 100)
        assertFalse(core.calls.last { it.first == "player.liuyingWarm" }.second.bool("enabled"))
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
    }

    @Test fun lateCancelledPreparationIsRemovedBeforeLatestSelectionPlays() {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var held = false
        val core = open(populated = true, wrap = { fake -> object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "player.liuyingPrepare" && !held) {
                    held = true
                    return withContext(NonCancellable) {
                        started.complete(Unit); release.await()
                        buildJsonObject {
                            put("request_id", args.str("request_id")); put("can_preload", true)
                            put("play_url", "http://127.0.0.1:${mediaServer.localPort}/late.mp4")
                            put("media_source_id", "test")
                        }
                    }
                }
                return fake.callJson(command, args, onPartial)
            }
        } })
        try {
            assertTrue(started.isCompleted)
            rule.runOnIdle {
                session.select(LiuyingSelection("latest", "latest", 1), emptyList())
            }; advance(rule, 200)
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 600)
            val played = core.calls.last { it.first == "player.liuyingPlay" }
            assertEquals("latest", played.second.str("item_id"))
            assertEquals("", played.second.str("request_id"))
            val cancel = core.calls.indexOfLast { it.first == "player.liuyingCancel" && !it.second.str("request_id").isNullOrEmpty() }
            assertTrue(cancel in 0 until core.calls.indexOfLast { it.first == "player.liuyingPlay" })
            val prepared: Map<String, LiuyingMedia> = ReflectionHelpers.getField(session, "prepared")
            assertTrue(prepared.isEmpty())
        } finally { release.complete(Unit) }
    }

    @Test fun manualEndSwipeInLoopModeKeepsFiltersAndRetainsNewRoundInFullWindow() {
        val rounds = linkedMapOf<String, Int>()
        val core = open(populated = true) {
            on("emby.extLiuYingItems") { a -> buildJsonObject {
                val round = rounds.getOrPut(a.str("seed").orEmpty()) { rounds.size + 1 }
                put("items", arr(buildJsonObject { put("item_id", "round-$round"); put("title", "round-$round") }))
                put("has_more", false)
            } }
        }
        assertEquals(2, core.calls.count { it.first == "emby.extLiuYingItems" })
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
        openFilters()
        rule.onNodeWithTag("liuying.label.tg-3398168133").performScrollTo().performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.label.side_breast_exposure").performScrollTo().performClick(); advance(rule, 300)
        rule.onNodeWithText("完成").performClick(); advance(rule, 300)
        rule.onNodeWithTag("liuying.mode").performClick(); advance(rule, 300)
        val initial = core.calls.last { it.first == "emby.extLiuYingItems" }.second!!
        val count = core.calls.count { it.first == "emby.extLiuYingItems" }
        val firstRound = rounds.size - 1
        repeat(3) { index ->
            val oldSeed = core.calls.last { it.first == "emby.extLiuYingItems" }.second.str("seed")
            swipeVideo()
            assertEquals(count + index + 1, core.calls.count { it.first == "emby.extLiuYingItems" })
            val next = core.calls.last { it.first == "emby.extLiuYingItems" }.second!!
            assertNotEquals(oldSeed, next.str("seed"))
            assertEquals(initial.filterKeys { it != "seed" }, next.filterKeys { it != "seed" })
            assertEquals(0L, next.long("offset"))
            assertEquals("round-${firstRound + index + 1}", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        }
        swipeVideo(false)
        assertEquals("round-${firstRound + 2}", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        assertEquals(count + 3, core.calls.count { it.first == "emby.extLiuYingItems" })
        rule.onNodeWithText("循环播放").assertIsDisplayed()
    }

    @Test fun failedEndSwipeRetriesSameRoundAndEmptyResultStillAllowsSwipeBack() {
        var firstSeed: String? = null
        val core = open(populated = true) {
            on("emby.extLiuYingItems") { a ->
                if (firstSeed == null) firstSeed = a.str("seed")
                if (a.str("seed") != firstSeed) throw xyz.linplayer.app.core.CoreException("E_NETWORK", "新轮次失败", true)
                buildJsonObject {
                    put("items", arr(*listOf("ly-work-1", "ly-work-2").map { id -> buildJsonObject {
                        put("item_id", id); put("title", id)
                    } }.toTypedArray())); put("has_more", false)
                }
            }
        }
        swipeVideo()
        swipeVideo()
        rule.onNodeWithText("新轮次失败").assertIsDisplayed()
        val failed = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        val requests = core.calls.count { it.first == "emby.extLiuYingItems" }
        advance(rule, 2000)
        assertEquals(requests, core.calls.count { it.first == "emby.extLiuYingItems" })
        core.ret("emby.extLiuYingItems", buildJsonObject { put("items", arr()); put("has_more", false) })
        rule.onNodeWithText("重试").performClick(); advance(rule, 800)
        assertEquals(failed, core.calls.last { it.first == "emby.extLiuYingItems" }.second)
        rule.onNodeWithText("暂无可播放的入库资源").assertIsDisplayed()
        swipeVideo(false)
        assertEquals("ly-work-2", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        assertEquals(requests + 1, core.calls.count { it.first == "emby.extLiuYingItems" })
    }

    @Test fun itemControlsFollowTheDraggedPageButNavigationStaysFixed() {
        val core = open(populated = true)
        val details = rule.onNodeWithTag("liuying.details").fetchSemanticsNode().boundsInRoot
        val navigation = rule.onNodeWithTag("liuying.controls").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("liuying.gesture").performTouchInput {
            down(Offset(width * .4f, height * .65f))
            moveTo(Offset(width * .4f, height * .42f), 240)
        }
        advance(rule, 80)
        val moved = rule.onNodeWithTag("liuying.details").fetchSemanticsNode().boundsInRoot
        assertTrue("条目控件应随当前视频页一起移动", moved.top < details.top - 100)
        assertEquals(navigation, rule.onNodeWithTag("liuying.controls").fetchSemanticsNode().boundsInRoot)
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
        rule.onNodeWithTag("liuying.gesture").performTouchInput { up() }; advance(rule, 800)
        rule.onNodeWithText("ly-work-2").assertIsDisplayed()
    }

    @Test fun latePageResponseKeepsTheLatestSettledVideo() {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val core = open(populated = true, more = true, wrap = { fake -> object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.extLiuYingItems" && args.long("offset") == 2L) {
                    started.complete(Unit); release.await()
                    return buildJsonObject {
                        put("items", arr(buildJsonObject { put("item_id", "third"); put("title", "第三条") }))
                        put("has_more", false)
                    }
                }
                return fake.callJson(command, args, onPartial)
            }
        } })
        try {
            assertTrue(started.isCompleted)
            moveVideo(true)
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 600)
            rule.onNodeWithText("ly-work-2").assertIsDisplayed()
            assertEquals(listOf("ly-work-1", "ly-work-2"),
                core.calls.filter { it.first == "player.liuyingPlay" }.map { it.second.str("item_id") })
            moveVideo(true)
            assertEquals("third", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        } finally { release.complete(Unit) }
    }

    @Test fun removingCurrentFavoriteKeepsItUntilLeavingAndDoesNotSkipNext() {
        val core = open(populated = true) {
            on("emby.extLiuYingItems") { buildJsonObject {
                put("items", arr(*listOf("first", "second", "third").map { id -> buildJsonObject {
                    put("item_id", id); put("title", id); put("favorite", true)
                } }.toTypedArray()))
                put("has_more", false)
            } }
            ret("emby.setFavorite", JsonNull)
        }
        openFilters()
        rule.onNodeWithTag("liuying.favorites").performScrollTo().performClick(); advance(rule, 500)
        rule.onNodeWithText("完成").performClick(); advance(rule, 300)
        val before = core.calls.count { it.first == "player.liuyingPlay" }
        rule.onNodeWithTag("liuying.favorite").performClick(); advance(rule, 300)
        rule.onNodeWithText("first").assertIsDisplayed()
        rule.onNodeWithTag("liuying.favorite").assertIsNotSelected()
        assertEquals(before, core.calls.count { it.first == "player.liuyingPlay" })
        moveVideo(true)
        rule.onNodeWithText("second").assertIsDisplayed()
        assertEquals("second", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        moveVideo(true)
        assertEquals("third", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        moveVideo(false)
        assertEquals("second", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
    }

    @Test fun entryAutoPlaysAndManualNextStopsPreviousBeforeStarting() {
        val core = open(populated = true)
        assertEquals("ly-work-1", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        assertFalse(core.calls.any { it.first == "player.play" })
        moveVideo(true, 800)
        assertEquals("ly-work-2", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        val play = core.calls.indexOfLast { it.first == "player.liuyingPlay" }
        assertTrue(core.calls.indexOfLast { it.first == "player.liuyingStop" } in 0 until play)
        assertEquals(2, core.calls.count { it.first == "emby.extLiuYingItems" })
        rule.runOnIdle { showing = false }; advance(rule, 400)
        assertEquals(2, core.calls.count { it.first == "player.liuyingStop" })
    }

    @Test fun loopReplaysCurrentInsteadOfAdvancingAndLabelsOnlyAppearInSheet() {
        val core = open(populated = true)
        rule.onNodeWithTag("liuying.mode").performClick(); advance(rule, 300)
        rule.onNodeWithText("循环播放").assertExists()
        rule.runOnIdle { sample(3658.0) }; advance(rule, 300)
        rule.runOnIdle { sample(3658.25) }; advance(rule, 300)
        rule.runOnIdle { sample(3660.0, eof = true) }; advance(rule, 800)
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
        assertEquals("ly-work-1", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        assertEquals(2, core.calls.count { it.first == "emby.extLiuYingItems" })
        rule.onNodeWithTag("liuying.next").assertDoesNotExist()
        openFilters()
        rule.onNodeWithTag("liuying.next").performScrollTo()
        rule.onNodeWithTag("liuying.next").assertExists()
        rule.onNodeWithTag("liuying.label.tg-3398168133").performScrollTo(); advance(rule, 300)
        rule.onNodeWithText("9 部").assertIsDisplayed()
    }

    @Test fun pageBoundaryKeepsSeedAndPreviousPageAndFailedRequestRetriesSamePage() {
        var fail = true
        val core = open(populated = true, more = true) {
            on("emby.extLiuYingItems") { a ->
                val offset = a.long("offset") ?: 0
                if (offset > 0 && fail) throw xyz.linplayer.app.core.CoreException("E_NETWORK", "分页失败", true)
                buildJsonObject {
                    put("items", if (offset == 0L) arr(
                        buildJsonObject { put("item_id", "ly-work-1"); put("title", "第一条") },
                        buildJsonObject { put("item_id", "ly-work-2"); put("title", "第二条") })
                    else arr(buildJsonObject { put("item_id", "ly-work-3"); put("title", "第三条") }))
                    put("has_more", offset == 0L); put("offset", offset)
                }
            }
        }
        moveVideo(true, 500)
        moveVideo(true, 500)
        val failed = core.calls.last { it.first == "emby.extLiuYingItems" }.second
        assertEquals(2, failed.long("offset")!!.toInt())
        val seed = failed.str("seed")
        assertEquals(core.calls.first { it.first == "emby.extLiuYingItems" }.second.str("seed"), seed)
        val beforeRetry = core.calls.count { it.first == "emby.extLiuYingItems" }
        rule.runOnIdle { fail = false }
        rule.onNodeWithTag("liuying.pageRetry").performClick(); advance(rule, 800)
        assertEquals(beforeRetry + 2, core.calls.count { it.first == "emby.extLiuYingItems" })
        rule.onNodeWithText("第三条").assertExists()
        assertEquals(seed, core.calls.filter { it.first == "emby.extLiuYingItems" }[beforeRetry].second.str("seed"))
        assertEquals("ly-work-3", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        moveVideo(false)
        assertEquals("ly-work-2", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        assertEquals(beforeRetry + 2, core.calls.count { it.first == "emby.extLiuYingItems" })
    }

    @Test fun muteSurvivesVideoSwitchAndFeedControlsRender() {
        val core = open(populated = true)
        core.ret("player.setVolume", JsonNull)
        rule.runOnIdle { sample(1.0) }; advance(rule, 300)
        rule.runOnIdle { sample(1.25) }; advance(rule, 300)
        rule.onRoot().captureRoboImage("build/liuying-mobile/phone-feed.png")
        rule.onNodeWithText("静音").performClick(); advance(rule, 300)
        assertTrue(session.muted)
        assertEquals(0f, session.slots.active!!.player.volume)
        moveVideo(true)
        rule.onNodeWithText("开声音").assertExists()
        assertTrue(session.muted)
        assertEquals(0f, session.slots.active!!.player.volume)
        assertFalse(core.calls.any { it.first == "player.setVolume" })
    }

    @Test fun emptyNextPageCanReturnToRetainedVideo() {
        val core = open(populated = true, more = true)
        core.ret("emby.extLiuYingItems", buildJsonObject {
            put("items", arr()); put("has_more", false)
        })
        moveVideo(true, 500)
        moveVideo(true, 500)
        rule.onNodeWithText("暂无可播放的入库资源").assertExists()
        openFilters()
        rule.onNodeWithTag("liuying.previous").performScrollTo().assertIsEnabled().performClick(); advance(rule, 600)
        assertEquals("ly-work-2", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
        assertEquals(2, core.calls.count { it.first == "emby.extLiuYingItems" })
    }

    @Test fun horizontalGestureSeeksWithoutChangingVideo() {
        val core = open(populated = true)
        rule.runOnIdle { sample(30.0) }; advance(rule, 300)
        rule.runOnIdle { sample(30.25) }; advance(rule, 300)
        val before = session.slots.active!!.player.currentPosition
        rule.onNodeWithTag("liuying.gesture").performTouchInput {
            swipe(Offset(width * .08f, height * .45f), Offset(width * .55f, height * .45f))
        }
        advance(rule, 400)
        assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
        assertTrue(session.slots.active!!.player.currentPosition > before)
        assertFalse(core.calls.any { it.first == "player.seek" })
        assertFalse(core.calls.any { it.first == "player.setVolume" })
    }

    @Test fun fiftySelectionsDuringSlowStopCommitOnlyLatestTarget() {
        val release = CompletableDeferred<Unit>()
        var stops = 0
        val core = open(populated = true, wrap = { fake -> object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "player.liuyingStop" && ++stops == 1) release.await()
                return fake.callJson(command, args, onPartial)
            }
        } })
        try {
            rule.runOnIdle {
                repeat(50) { n -> session.select(LiuyingSelection("fast-$n", "fast-$n", 1), emptyList()) }
            }
            advance(rule, 10_000)
            assertEquals(1, stops)
            assertEquals(1, core.calls.count { it.first == "player.liuyingPlay" })
            assertFalse(session.slots.active!!.player.playWhenReady)
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 600)
            assertEquals(listOf("ly-work-1", "fast-49"),
                core.calls.filter { it.first == "player.liuyingPlay" }.map { it.second.str("item_id") })
        } finally { release.complete(Unit) }
    }

    @Test fun leavingWhilePlayIsInFlightWaitsForReceiptAndStopsExactSession() {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val core = open(populated = true, wrap = { fake -> object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "player.liuyingPlay" && args.str("item_id") == "late") return withContext(NonCancellable) {
                    started.complete(Unit); release.await()
                    fake.callJson(command, args, onPartial)
                }
                return fake.callJson(command, args, onPartial)
            }
        } })
        try {
            rule.runOnIdle { session.select(LiuyingSelection("late", "late", 1), emptyList()) }; advance(rule, 300)
            assertTrue(started.isCompleted)
            val player = session.slots.active!!.player
            rule.runOnIdle { showing = false }; advance(rule, 300)
            assertTrue(player.isReleased)
            assertNull(session.slots.active); assertNull(session.slots.spare)
            rule.runOnIdle { release.complete(Unit) }; advance(rule, 600)
            val stopped = core.calls.last { it.first == "player.liuyingStop" }.second
            assertEquals("late", stopped.str("item_id"))
            assertEquals("test-2", stopped.str("playback_id"))
            assertFalse(core.calls.any { it.first == "player.play" || it.first == "player.stopPlayback" })
        } finally { release.complete(Unit) }
    }

    @Test fun manualPauseSurvivesBackgroundScreenAndFocusEvents() {
        open(populated = true)
        rule.runOnIdle {
            session.togglePause()
            session.environment(false, false)
            session.focusChanged(false)
            session.environment(true, true)
            session.focusChanged(true)
        }
        assertFalse(session.userWantsPlay)
        assertFalse(session.slots.active!!.player.playWhenReady)
        rule.runOnIdle {
            session.togglePause()
            session.focusChanged(false, permanent = true)
            session.environment(false, false)
            session.focusChanged(true)
            session.environment(true, true)
        }
        assertFalse(session.userWantsPlay)
        assertFalse(session.slots.active!!.player.playWhenReady)
    }

    @Test fun registeredScreenAndFocusEventsDoNotOverrideManualPauseOrReviveDisposedSession() {
        val core = open(populated = true)
        val context = RuntimeEnvironment.getApplication()
        val audio = Shadow.extract<ShadowAudioManager>(context.getSystemService(AudioManager::class.java))
        val focus = audio.lastAudioFocusRequest.listener
        fun broadcast(action: String) {
            rule.runOnIdle { context.sendBroadcast(Intent(action)) }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            advance(rule, 300)
        }
        assertTrue(session.foreground && session.unlocked)
        broadcast(Intent.ACTION_SCREEN_OFF)
        assertFalse(session.foreground)
        assertFalse(session.slots.active!!.player.playWhenReady)
        broadcast(Intent.ACTION_USER_PRESENT)
        assertTrue(session.foreground && session.unlocked)
        rule.runOnIdle { focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) }
        assertTrue(session.userWantsPlay)
        assertFalse(session.slots.active!!.player.playWhenReady)
        rule.runOnIdle { session.togglePause() }
        broadcast(Intent.ACTION_SCREEN_OFF)
        rule.runOnIdle { focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN) }
        broadcast(Intent.ACTION_USER_PRESENT)
        assertFalse(session.userWantsPlay)
        broadcast(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        val plays = core.calls.count { it.first == "player.liuyingPlay" }
        rule.runOnIdle { showing = false }; advance(rule, 300)
        rule.runOnIdle { focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN) }
        broadcast(Intent.ACTION_USER_PRESENT)
        assertNull(session.slots.active)
        assertEquals(plays, core.calls.count { it.first == "player.liuyingPlay" })
    }

    @Test fun explicitNextAfterPermanentFocusLossRequestsFocusAgain() {
        val core = open(populated = true)
        var requests = 0
        val original = session.onNeedFocus
        rule.runOnIdle {
            session.focusChanged(false, permanent = true)
            session.onNeedFocus = { requests++; original() }
        }
        moveVideo(true)
        assertTrue(session.userWantsPlay)
        assertEquals(1, requests)
        assertEquals("ly-work-2", core.calls.last { it.first == "player.liuyingPlay" }.second.str("item_id"))
    }

    @Test fun ordinaryEnginePreferencesNeverChangeTheLiuyingPlaybackPath() {
        val old = UiPrefs.engine.value
        try {
            UiPrefs.engine.value = "mpv"
            val core = open(populated = true)
            for (engine in listOf("exo", "auto")) {
                val oldOwner = session.owner
                rule.runOnIdle { showing = false; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
                advance(rule, 300)
                rule.runOnIdle {
                    UiPrefs.engine.value = engine; showing = true
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                }
                advance(rule, 800)
                assertNotEquals(oldOwner, session.owner)
                assertNotNull("重进错误：${session.error}", session.slots.active)
                assertEquals(engine, UiPrefs.engine.value)
            }
            assertEquals(3, core.calls.count { it.first == "player.liuyingPlay" })
            assertFalse(core.calls.any { it.first == "player.play" || it.first == "player.playFile" })
        } finally { UiPrefs.engine.value = old }
    }
}
