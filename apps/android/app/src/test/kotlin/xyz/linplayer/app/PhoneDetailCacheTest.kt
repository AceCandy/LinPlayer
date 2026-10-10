package xyz.linplayer.app

import android.app.Application
import android.provider.Settings
import coil3.asImage
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.DetailCache
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.tv.FakeCore
import xyz.linplayer.app.tv.FakeImages
import xyz.linplayer.app.tv.loggedIn
import xyz.linplayer.app.tv.movie
import xyz.linplayer.app.tv.series
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.pages.DetailPage
import xyz.linplayer.app.ui.theme.LpTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h873dp-mdpi", application = Application::class)
class PhoneDetailCacheTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var nav: NavController
    @After fun clean() { scope.cancel(); PageCache.clear() }

    private fun metadata(name: String) = buildJsonObject {
        put("id", "m1"); put("name", name); put("type_", "Movie")
        put("overview", "$name 简介"); put("runtime_secs", 3600); put("resume_secs", 1800)
        put("is_favorite", true); put("played", true)
    }

    private fun open(app: AppState, route: Route.Detail = Route.Detail("m1", "Movie"), fontScale: Float = 1f, installImages: Boolean = true) {
        if (installImages) FakeImages.install(ApplicationProvider.getApplicationContext())
        runBlocking { app.boot() }
        rule.setContent {
            LpTheme {
                CompositionLocalProvider(LocalApp provides app,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    val controller = rememberNavController()
                    nav = controller
                    // 单测测量详情内部渐显，排除宿主导航淡入对像素透明度的影响。
                    NavHost(controller, route, enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None }) {
                        composable<Route.Home> { Text("首页") }
                        composable<Route.Detail> { DetailPage(controller, it) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun reopen() {
        rule.runOnIdle { nav.navigate(Route.Home) { popUpTo<Route.Detail> { inclusive = true } } }
        rule.onNodeWithText("首页").assertIsDisplayed()
        rule.runOnIdle { nav.navigate(Route.Detail("m1", "Movie")) }
        rule.waitForIdle()
    }

    @Test fun waitingEpisodesUseRefreshAndSingleSeasonHasNoSelector() {
        val fake = FakeCore().loggedIn().series().apply {
            ret("emby.seriesSeasons", xyz.linplayer.app.tv.arr(xyz.linplayer.app.tv.item("s1a", "第1季", "Season")))
        }
        val gate = CompletableDeferred<Unit>()
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.seriesSeasons") gate.await()
                return fake.callJson(command, args, onPartial)
            }
        }
        open(AppState(core, scope), Route.Detail("sh", "Series"))
        rule.onNodeWithTag("detail.episodes").performScrollTo()
        rule.onNodeWithContentDescription("刷新中").assertIsDisplayed()
        rule.onNodeWithTag("detail.play.target").assertDoesNotExist()
        rule.runOnIdle { gate.complete(Unit) }
        rule.waitForIdle()
        rule.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasTestTag("detail.seasons"))
        rule.onNodeWithContentDescription("查看第1季分集").assertIsDisplayed()
        rule.onNodeWithContentDescription("选择季").assertDoesNotExist()
    }

    @Test fun seriesEntryShowsOverviewBeforeEpisodesAndWidePlayAction() {
        val fake = FakeCore().loggedIn().series()
        open(AppState(fake, scope), Route.Detail("s1", "Series"))
        val play = rule.onNodeWithTag("detail.play").fetchSemanticsNode().boundsInRoot
        assertTrue("剧集播放按钮应使用剩余行宽", play.width > 170f)
        rule.onNodeWithTag("detail.series.target").assertExists()
        rule.onNodeWithTag("detail.overview").performScrollTo()
        val overview = rule.onNodeWithTag("detail.overview").fetchSemanticsNode().boundsInRoot
        val episodes = rule.onNodeWithTag("detail.episodes").fetchSemanticsNode().boundsInRoot
        assertTrue("剧简介应在选集之前", overview.bottom <= episodes.top)
        rule.onNodeWithText("共 3 季").assertExists()
        rule.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasTestTag("detail.seasons"))
        rule.onNodeWithContentDescription("查看第2季分集").assertIsDisplayed()
    }

    @Test fun seriesPosterChangesSelectedSeasonWithoutOpeningAnotherDetail() {
        val fake = FakeCore().loggedIn().series()
        open(AppState(fake, scope), Route.Detail("s1", "Series"))
        rule.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasTestTag("detail.seasons"))
        rule.onNodeWithContentDescription("查看第2季分集").performClick()
        rule.waitForIdle()
        assertEquals("se2", fake.calls.last { it.first == "emby.seasonEpisodes" }
            .second?.get("parent_id")?.jsonPrimitive?.content)
        assertEquals(1, fake.calls.count { it.first == "emby.itemDetail" })
        val requests = fake.calls.count { it.first == "emby.seasonEpisodes" }
        rule.onNodeWithContentDescription("查看第2季分集").performClick()
        rule.waitForIdle()
        assertEquals("重复选择当前季不应重拉", requests, fake.calls.count { it.first == "emby.seasonEpisodes" })
    }

    @Test fun watchedEpisodesUseSharedStatusWithoutEpisodeCounts() {
        val fake = FakeCore().loggedIn().series().apply {
            ret("emby.seasonEpisodes", xyz.linplayer.app.tv.page(
                xyz.linplayer.app.tv.item("watched", "完整看完单集", "Episode", played = true),
                xyz.linplayer.app.tv.item("partial", "尚未看完单集", "Episode", runtime = 1800.0, resume = 300.0)))
        }
        open(AppState(fake, scope), Route.Detail("s1", "Series"))
        rule.onNodeWithTag("detail.episodes").performScrollTo()
        rule.onNode(hasContentDescription("已看完") and hasAnyAncestor(hasTestTag("detail.ep.watched")),
            useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodes(hasContentDescription("已看完") and hasAnyAncestor(hasTestTag("detail.ep.partial")),
            useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(hasContentDescription("集未观看", substring = true), useUnmergedTree = true).assertCountEquals(0)
        assertEquals(1, fake.calls.count { it.first == "emby.itemDetail" })
    }

    @Test fun movieArtworkUsesTallDetailHeader() = tallHeader("Movie")
    @Test fun episodeArtworkUsesTallDetailHeader() = tallHeader("Episode")

    private fun tallHeader(type: String) {
        val fake = FakeCore().loggedIn().movie().apply {
            ret("emby.itemDetail", buildJsonObject {
                put("id", "m1"); put("type_", type); put("name", "头图尺寸验证")
            })
        }
        open(AppState(fake, scope), Route.Detail("m1", type))
        val hero = rule.onNodeWithTag("detail.hero").fetchSemanticsNode().boundsInRoot
        assertTrue("各详情头图统一使用较高的最小占位", hero.height >= hero.width * 1.08f - 1f)
    }

    @Test fun knownMissingBackdropUsesPrimaryImage() = checkBackgroundFallback(hasBackdrop = false)
    @Test fun failedBackdropFallsBackToPrimaryImage() = checkBackgroundFallback(hasBackdrop = true)

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun checkBackgroundFallback(hasBackdrop: Boolean) {
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        coil3.SingletonImageLoader.setUnsafe(coil3.ImageLoader.Builder(ctx)
            .coroutineContext(Dispatchers.Unconfined).components {
                add(coil3.fetch.Fetcher.Factory<coil3.Uri> { uri, _, _ -> coil3.fetch.Fetcher {
                    requests.add(uri.toString())
                    if (hasBackdrop && uri.toString().contains("Backdrop")) error("测试背景图缺失")
                    val bitmap = android.graphics.Bitmap.createBitmap(40, 60, android.graphics.Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.GREEN)
                    coil3.fetch.ImageFetchResult(bitmap.asImage(), false, coil3.decode.DataSource.NETWORK)
                } })
            }.build())
        val fake = FakeCore().loggedIn().movie().apply {
            ret("emby.itemDetail", buildJsonObject {
                put("id", "m1"); put("type_", "Movie"); put("name", "海报回退")
                put("has_backdrop", hasBackdrop); put("has_primary", true)
            })
        }
        open(AppState(fake, scope), installImages = false)
        rule.waitForIdle()
        assertTrue("应加载Primary海报", requests.any { it.contains("Primary") })
        assertEquals("Backdrop请求应按元数据判定", hasBackdrop, requests.any { it.contains("Backdrop") })
        rule.onRoot().captureRoboImage("build/detail-options/fallback-$hasBackdrop.png")
    }

    @Test fun seriesReservesEpisodeLayoutBeforeSeasonsAndEpisodesArrive() = stableEpisodeLayout(1f)

    @Test fun seriesPlaceholderMatchesLargeFontEpisodeLayout() = stableEpisodeLayout(1.3f)

    private fun stableEpisodeLayout(fontScale: Float) {
        val fake = FakeCore().loggedIn().series()
        val seasonGate = CompletableDeferred<Unit>()
        val episodeGate = CompletableDeferred<Unit>()
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.seriesSeasons") seasonGate.await()
                if (command == "emby.seasonEpisodes") episodeGate.await()
                return fake.callJson(command, args, onPartial)
            }
        }
        open(AppState(core, scope), Route.Detail("s1", "Series"), fontScale)
        rule.onNodeWithTag("detail.episodes").performScrollTo()
        val initial = rule.onNodeWithTag("detail.episodes").fetchSemanticsNode().boundsInRoot
        val target = rule.onNodeWithTag("detail.actions.layout").fetchSemanticsNode().boundsInRoot
        assertTrue(initial.height > 200f)
        rule.runOnIdle { seasonGate.complete(Unit) }
        rule.waitForIdle()
        val withSeason = rule.onNodeWithTag("detail.episodes").fetchSemanticsNode().boundsInRoot
        assertEquals(initial.top, withSeason.top, .5f)
        assertEquals(initial.height, withSeason.height, .5f)
        rule.runOnIdle { episodeGate.complete(Unit) }
        rule.waitForIdle()
        val loaded = rule.onNodeWithTag("detail.episodes").fetchSemanticsNode().boundsInRoot
        val loadedTarget = rule.onNodeWithTag("detail.actions.layout").fetchSemanticsNode().boundsInRoot
        assertEquals(initial.top, loaded.top, .5f)
        assertEquals(initial.height, loaded.height, .5f)
        assertEquals(target.height, loadedTarget.height, .5f)
        rule.onNodeWithText("S2E1：信号", useUnmergedTree = true).assertExists()
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test fun episodeTextIsImmediatelyOpaqueWhileItsImageIsPending() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val imageGate = CompletableDeferred<Unit>()
        coil3.SingletonImageLoader.setUnsafe(coil3.ImageLoader.Builder(context)
            .coroutineContext(Dispatchers.Unconfined).components {
                add(object : coil3.fetch.Fetcher.Factory<coil3.Uri> {
                    override fun create(data: coil3.Uri, options: coil3.request.Options, imageLoader: coil3.ImageLoader) = coil3.fetch.Fetcher {
                        imageGate.await()
                        coil3.fetch.ImageFetchResult(android.graphics.Bitmap.createBitmap(20, 20,
                            android.graphics.Bitmap.Config.ARGB_8888).asImage(), false, coil3.decode.DataSource.NETWORK)
                    }
                })
            }.build())
        val fake = FakeCore().loggedIn().series()
        val episodeGate = CompletableDeferred<Unit>()
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.seasonEpisodes") episodeGate.await()
                return fake.callJson(command, args, onPartial)
            }
        }
        open(AppState(core, scope), Route.Detail("s1", "Series"), installImages = false)
        rule.onNodeWithTag("detail.episodes").performScrollTo()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { episodeGate.complete(Unit) }
        rule.mainClock.advanceTimeBy(32)
        fun contrast(): Float {
            val pixels = rule.onNodeWithText("S2E1：信号", useUnmergedTree = true).captureToImage().toPixelMap()
            val bg = pixels[0, 0]
            return (0 until pixels.height).maxOf { y -> (0 until pixels.width).maxOf { x ->
                val p = pixels[x, y]
                maxOf(kotlin.math.abs(p.red - bg.red), kotlin.math.abs(p.green - bg.green), kotlin.math.abs(p.blue - bg.blue))
            } }
        }
        val first = contrast()
        rule.mainClock.advanceTimeBy(500)
        val settled = contrast()
        assertTrue("first=$first settled=$settled", settled > .1f && first >= settled * .95f)
        assertFalse(imageGate.isCompleted)
        rule.runOnIdle { imageGate.complete(Unit) }
    }

    @Test fun partialEpisodeFailureKeepsCardsAndCanResumeLoading() {
        val fake = FakeCore().loggedIn().series()
        var requests = 0
        fake.on("emby.seasonEpisodes") { args ->
            requests++
            if (requests == 2) throw CoreException("E_NETWORK", "分集后页失败", true)
            val offset = args?.get("start_index")?.jsonPrimitive?.int ?: 0
            buildJsonObject {
                put("items", xyz.linplayer.app.tv.arr(xyz.linplayer.app.tv.item("partial-${offset + 1}", "保留分集 ${offset + 1}",
                    type = "Episode", season = 1, episode = offset + 1)))
                put("total", 2)
            }
        }
        open(AppState(fake, scope), Route.Detail("s1", "Series"))
        rule.onNodeWithTag("detail.episodes").performScrollTo()
        rule.onNode(hasText("S1E1：保留分集 1") and !hasAnyAncestor(hasTestTag("detail.series.target")), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("分集后页失败", substring = true).assertExists()
        rule.onNodeWithText("重试").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("分集后页失败", substring = true).assertDoesNotExist()
        assertEquals(3, requests)
        assertEquals("1", fake.calls.last { it.first == "emby.seasonEpisodes" }.second?.get("start_index").toString())
        rule.onNode(hasText("S1E1：保留分集 1") and !hasAnyAncestor(hasTestTag("detail.series.target")), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("S1E2：保留分集 2") and !hasAnyAncestor(hasTestTag("detail.series.target")), useUnmergedTree = true).assertExists()
    }

    @Test fun seasonFailureCanRetryInReservedSection() {
        val fake = FakeCore().loggedIn().series()
        val success = fake.handlers.getValue("emby.seriesSeasons")
        fake.on("emby.seriesSeasons") { throw CoreException("E_NETWORK", "选集暂不可用", true) }
        open(AppState(fake, scope), Route.Detail("s1", "Series"))
        rule.onNodeWithTag("detail.episodes").performScrollTo()
        rule.onNodeWithText("选集暂不可用", substring = true).assertExists()
        fake.on("emby.seriesSeasons", success)
        rule.onNodeWithText("重试").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("选集暂不可用", substring = true).assertDoesNotExist()
        rule.onNodeWithText("S2E1：信号", useUnmergedTree = true).assertExists()
        assertEquals(1, fake.calls.count { it.first == "emby.itemDetail" })
        assertEquals(2, fake.calls.count { it.first == "emby.seriesSeasons" })
    }

    @Test fun pendingMediaDoesNotShowInventedDefaultVersionOrEmptyTracks() {
        val fake = FakeCore().loggedIn().movie()
        val gate = CompletableDeferred<Unit>()
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.itemMedia") gate.await()
                return fake.callJson(command, args, onPartial)
            }
        }
        open(AppState(core, scope))
        rule.onAllNodesWithText("沙丘 2").onFirst().assertIsDisplayed()
        rule.onNodeWithText("默认版本").assertDoesNotExist()
        rule.onNodeWithContentDescription("版本").assertDoesNotExist()
        rule.onNodeWithContentDescription("音轨").assertDoesNotExist()
        rule.onNodeWithContentDescription("刷新中").assertIsDisplayed()
        rule.runOnIdle { gate.complete(Unit) }
        rule.onNodeWithContentDescription("版本").assertIsDisplayed()
        rule.onNodeWithContentDescription("音轨").assertIsDisplayed()
        rule.onNodeWithText("正在读取播放选项…").assertDoesNotExist()
    }

    @Test fun mediaOptionsGrowThroughAnIntermediateHeight() {
        val fake = FakeCore().loggedIn().movie()
        val gate = CompletableDeferred<Unit>()
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.itemMedia") gate.await()
                return fake.callJson(command, args, onPartial)
            }
        }
        open(AppState(core, scope))
        fun height() = rule.onNodeWithTag("detail.options").fetchSemanticsNode().boundsInRoot.height
        val before = height()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { gate.complete(Unit) }
        rule.mainClock.advanceTimeBy(96)
        val middle = height()
        rule.mainClock.advanceTimeBy(500)
        val after = height()
        assertTrue("loaded options must have multiple real rows", after > before + 80f)
        assertTrue("height must interpolate instead of jump: $before, $middle, $after",
            middle > before + 1f && middle < after - 1f)
    }

    @Test fun lateMetadataMovesContentGraduallyAndFadesTags() {
        val fake = FakeCore().loggedIn().movie()
        val metadataGate = CompletableDeferred<Unit>()
        val mediaGate = CompletableDeferred<Unit>()
        var mediaStarted = false
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.itemDetail") {
                    metadataGate.await()
                    return buildJsonObject {
                        put("id", "m1"); put("name", "渐显电影"); put("type_", "Movie")
                        put("rating", 8.7); put("premiere_date", "2024-10-09"); put("runtime_secs", 7200)
                        put("tagline", "迟到的标语"); put("genres", buildJsonArray { add("科幻") })
                    }
                }
                if (command == "emby.itemMedia") { mediaStarted = true; mediaGate.await() }
                return fake.callJson(command, args, onPartial)
            }
        }
        open(AppState(core, scope), fontScale = 1.6f)
        assertTrue("media must start before metadata finishes", mediaStarted)
        fun top() = rule.onNodeWithTag("detail.play").fetchSemanticsNode().boundsInRoot.top
        val before = top()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { metadataGate.complete(Unit) }
        rule.mainClock.advanceTimeBy(96)
        val middle = top()
        val firstContrast = tagContrast("科幻")
        rule.mainClock.advanceTimeBy(500)
        val after = top()
        val settledContrast = tagContrast("科幻")
        assertTrue("metadata must interpolate downstream position: $before → $middle → $after",
            middle > before + 1f && middle < after - 1f)
        assertTrue("tags must fade instead of appear opaque: $firstContrast → $settledContrast",
            settledContrast > .1f && firstContrast < settledContrast * .6f)
        assertFalse("metadata display must not wait for media", mediaGate.isCompleted)
        rule.runOnIdle { mediaGate.complete(Unit) }
        rule.mainClock.advanceTimeBy(500)
    }

    private fun tagContrast(text: String): Float {
        val pixels = rule.onNodeWithText(text).captureToImage().toPixelMap()
        val bg = pixels[0, 0]
        return (0 until pixels.height).maxOf { y -> (0 until pixels.width).maxOf { x ->
            val p = pixels[x, y]
            maxOf(kotlin.math.abs(p.red - bg.red), kotlin.math.abs(p.green - bg.green), kotlin.math.abs(p.blue - bg.blue))
        } }
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test fun cachedTagsAreOpaqueImmediatelyAndSameDisplayRefreshDoesNotFade() {
        val fake = FakeCore().loggedIn().movie()
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val value = buildJsonObject {
            put("id", "m1"); put("name", "缓存电影"); put("type_", "Movie")
            // 固定不可点击样式，避免缓存与联网标签的颜色差异被误判为淡入。
            put("capabilities", buildJsonObject { put("filters", false) })
            put("genres", buildJsonArray { add("科幻") })
        }
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.itemDetail") {
                    requests++
                    if (requests > 1) gate.await()
                    return buildJsonObject { value.forEach { (k, v) -> put(k, v) }; put("is_favorite", requests > 1) }
                }
                return fake.callJson(command, args, onPartial)
            }
        }
        // 固定取色输入，避免背景取色动画被误判为标签透明度。
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        coil3.SingletonImageLoader.setUnsafe(coil3.ImageLoader.Builder(ctx).components {
            add(coil3.fetch.Fetcher.Factory<coil3.Uri> { _, _, _ -> coil3.fetch.Fetcher { error("无图片夹具") } })
        }.build())
        open(AppState(core, scope), installImages = false)
        val opaque = tagContrast("科幻")
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { nav.navigate(Route.Home) { popUpTo<Route.Detail> { inclusive = true } } }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithText("首页").assertIsDisplayed()
        rule.runOnIdle { nav.navigate(Route.Detail("m1", "Movie")) }
        rule.mainClock.advanceTimeBy(32)
        assertEquals(2, requests)
        assertEquals("cache first frame must not fade", opaque, tagContrast("科幻"), .02f)
        rule.runOnIdle { gate.complete(Unit) }
        rule.mainClock.advanceTimeBy(96)
        assertEquals("unrelated favorite refresh must not replay tags", opaque, tagContrast("科幻"), .02f)
        rule.mainClock.advanceTimeBy(500)
        assertEquals(2, requests)
    }

    @Test fun disabledMotionRevealsMetadataImmediatelyAndEmptyReservationsCollapse() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val resolver = context.contentResolver
        val previous = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            val fake = FakeCore().loggedIn().movie()
            val gate = CompletableDeferred<Unit>()
            val core = object : CorePort by fake {
                override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                    if (command == "emby.itemDetail") {
                        gate.await()
                        return buildJsonObject {
                            put("id", "m1"); put("name", "无评分电影"); put("type_", "Movie")
                            put("genres", buildJsonArray { add("科幻") })
                        }
                    }
                    return fake.callJson(command, args, onPartial)
                }
            }
            open(AppState(core, scope))
            fun height(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height
            assertTrue(height("detail.ratings") > 0)
            assertTrue(height("detail.tagline") > 0)
            rule.mainClock.autoAdvance = false
            rule.runOnIdle { gate.complete(Unit) }
            rule.mainClock.advanceTimeBy(32)
            assertEquals(0f, height("detail.ratings"), .1f)
            assertEquals(0f, height("detail.tagline"), .1f)
            val immediate = tagContrast("科幻")
            assertTrue(immediate > .1f)
            rule.mainClock.advanceTimeBy(500)
            assertEquals(immediate, tagContrast("科幻"), .02f)
        } finally {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, previous)
        }
    }

    @Test fun mediaFailureCanRetryWithoutReloadingMetadata() {
        val fake = FakeCore().loggedIn().movie()
        val handler = fake.handlers.getValue("emby.itemMedia")
        fake.on("emby.itemMedia") { throw CoreException("E_NETWORK", "媒体暂不可用", true) }
        open(AppState(fake, scope))
        rule.onAllNodesWithText("沙丘 2").onFirst().assertIsDisplayed()
        rule.onNodeWithText("默认版本").assertDoesNotExist()
        rule.onNodeWithText("媒体暂不可用", substring = true).assertIsDisplayed()
        fake.on("emby.itemMedia", handler)
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithContentDescription("版本").assertIsDisplayed()
        assertEquals(2, fake.calls.count { it.first == "emby.itemMedia" })
        assertEquals(1, fake.calls.count { it.first == "emby.itemDetail" })
    }

    @Test fun emptyMediaResponseDoesNotInventDefaultVersion() {
        val fake = FakeCore().loggedIn().movie().apply { ret("emby.itemMedia", JsonArray(emptyList())) }
        open(AppState(fake, scope))
        rule.onAllNodesWithText("沙丘 2").onFirst().assertIsDisplayed()
        rule.onNodeWithContentDescription("版本").assertDoesNotExist()
        rule.onNodeWithContentDescription("音轨").assertDoesNotExist()
        rule.onNodeWithText("正在读取播放选项…").assertDoesNotExist()
    }

    @Test fun reopeningShowsMetadataWhileRefreshIsPendingThenReplacesIt() {
        val fake = FakeCore().loggedIn().movie()
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.itemDetail") {
                    requests++
                    if (requests > 1) gate.await()
                    return metadata(if (requests == 1) "缓存片名" else "最新片名")
                }
                return fake.callJson(command, args, onPartial)
            }
        }
        open(AppState(core, scope))
        rule.onNodeWithText("缓存片名").assertIsDisplayed()
        reopen()
        assertEquals(2, requests)
        rule.onNodeWithText("缓存片名").assertIsDisplayed()
        rule.onNodeWithContentDescription("收藏").assertIsNotEnabled()
        rule.onNodeWithContentDescription("标已看").assertIsNotEnabled()
        rule.onNodeWithText("继续观看", substring = true).assertDoesNotExist()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("缓存片名 简介"))
        rule.onNodeWithText("缓存片名 简介").assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.runOnIdle { gate.complete(Unit) }
        rule.onNodeWithText("最新片名").assertIsDisplayed()
        rule.onNodeWithText("缓存片名").assertDoesNotExist()
        rule.onNodeWithContentDescription("收藏").assertIsEnabled()
    }

    @Test fun networkFailureKeepsMetadataAndRetryRefreshesIt() {
        val fake = FakeCore().loggedIn().movie()
        fake.ret("emby.itemDetail", metadata("缓存片名"))
        open(AppState(fake, scope))
        fake.on("emby.itemDetail") { throw CoreException("E_NETWORK", "暂时无法连接", true) }
        reopen()
        rule.onNodeWithText("缓存片名").assertIsDisplayed()
        fake.ret("emby.itemDetail", metadata("恢复片名"))
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithText("恢复片名").assertIsDisplayed()
    }

    @Test fun newAppInstanceUsesDiskWhileNetworkIsPending() {
        val dir = temp.newFolder()
        val seed = DetailCache(dir)
        val key = seed.key("http://emby-a.invalid", "u", "m1")
        runBlocking { seed.put(key, metadata("磁盘片名"), seed.generation) }
        val fake = FakeCore().loggedIn().movie()
        val gate = CompletableDeferred<Unit>()
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.itemDetail") { gate.await(); return metadata("网络片名") }
                return fake.callJson(command, args, onPartial)
            }
        }
        open(AppState(core, scope, DetailCache(dir)))
        rule.waitUntil(5000) { rule.onAllNodesWithText("磁盘片名").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("收藏").assertIsNotEnabled()
        rule.runOnIdle { gate.complete(Unit) }
        rule.onNodeWithText("网络片名").assertIsDisplayed()
        rule.onNodeWithText("磁盘片名").assertDoesNotExist()
    }

    @Test fun fastNetworkResultWinsOverOldDiskMetadata() {
        val dir = temp.newFolder()
        val seed = DetailCache(dir)
        val key = seed.key("http://emby-a.invalid", "u", "m1")
        runBlocking { seed.put(key, metadata("旧资料"), seed.generation) }
        val fake = FakeCore().loggedIn().movie().apply { ret("emby.itemDetail", metadata("新资料")) }
        val cache = DetailCache(dir)
        open(AppState(fake, scope, cache))
        rule.waitUntil(5000) { cache.peek(key)?.get("name") == JsonPrimitive("新资料") }
        rule.onNodeWithText("新资料").assertIsDisplayed()
        rule.onNodeWithText("旧资料").assertDoesNotExist()
        rule.waitUntil(5000) {
            runBlocking { DetailCache(dir).load(key, 0) }?.get("name") == JsonPrimitive("新资料")
        }
        rule.onNodeWithText("新资料").assertIsDisplayed()
    }

    @Test fun authorizationFailureRemovesCachedMetadata() = rejectsCachedMetadata("E_AUTH")
    @Test fun missingResourceRemovesCachedMetadata() = rejectsCachedMetadata("E_NOTFOUND")

    private fun rejectsCachedMetadata(code: String) {
        val fake = FakeCore().loggedIn().movie().apply { ret("emby.itemDetail", metadata("私有资料")) }
        val app = AppState(fake, scope)
        open(app)
        fake.on("emby.itemDetail") { throw CoreException(code, "详情已不可用", false) }
        reopen()
        rule.onNodeWithText("私有资料").assertDoesNotExist()
        rule.onNodeWithText("详情已不可用", substring = true).assertIsDisplayed()
        assertNull(app.detailCache.peek(app.detailCache.key("http://emby-a.invalid", "u", "m1")))
    }

    @Test fun accountSwitchDiscardsLateResponseAndKeepsNewAccountVisible() {
        val fake = FakeCore().loggedIn().movie()
        val gate = CompletableDeferred<Unit>()
        var pending = false
        var returned = false
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.itemDetail") {
                    if (args?.get("user_id") == JsonPrimitive("u")) {
                        pending = true
                        withContext(NonCancellable) { gate.await() }
                        returned = true
                        return metadata("旧账号资料")
                    }
                    return metadata("新账号资料")
                }
                return fake.callJson(command, args, onPartial)
            }
        }
        val app = AppState(core, scope)
        open(app)
        assertTrue(pending)
        fake.ret("emby.currentSession", buildJsonObject {
            put("server", "http://emby-a.invalid"); put("user_id", "other"); put("token", "fixture")
        })
        rule.runOnIdle { runBlocking { app.refreshSession() } }
        rule.onNodeWithText("新账号资料").assertIsDisplayed()
        rule.runOnIdle { gate.complete(Unit) }
        rule.waitForIdle()
        assertTrue("late response must actually return", returned)
        rule.onNodeWithText("旧账号资料").assertDoesNotExist()
        rule.onNodeWithText("新账号资料").assertIsDisplayed()
        assertNull(app.detailCache.peek(app.detailCache.key("http://emby-a.invalid", "u", "m1")))
    }

    @Test fun leavingPagePreventsLateResponseFromPublishingCache() {
        val fake = FakeCore().loggedIn().movie()
        val gate = CompletableDeferred<Unit>()
        var returned = false
        val core = object : CorePort by fake {
            override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
                if (command == "emby.itemDetail") {
                    withContext(NonCancellable) { gate.await() }
                    returned = true
                    return metadata("离页后资料")
                }
                return fake.callJson(command, args, onPartial)
            }
        }
        val app = AppState(core, scope)
        open(app)
        rule.runOnIdle { nav.navigate(Route.Home) { popUpTo<Route.Detail> { inclusive = true } } }
        rule.onNodeWithText("首页").assertIsDisplayed()
        rule.runOnIdle { gate.complete(Unit) }
        rule.waitForIdle()
        assertTrue(returned)
        assertNull(app.detailCache.peek(app.detailCache.key("http://emby-a.invalid", "u", "m1")))
    }
}
