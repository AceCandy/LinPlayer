package xyz.linplayer.app.tv

import android.app.Activity
import android.graphics.BitmapFactory
import android.view.WindowManager
import kotlin.coroutines.coroutineContext
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.plugin.PluginPlayer
import xyz.linplayer.app.ui.pages.Version
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.player.DanmakuLayer
import xyz.linplayer.app.ui.player.DanmakuStyle
import xyz.linplayer.app.ui.player.ExoPreload
import xyz.linplayer.app.ui.player.ExoSurface
import xyz.linplayer.app.ui.player.PlaybackService
import xyz.linplayer.app.ui.player.Libass
import xyz.linplayer.app.ui.player.ObserveSeekTimeout
import xyz.linplayer.app.ui.player.SessionTrackMemory
import xyz.linplayer.app.ui.player.sessionTrackScope
import xyz.linplayer.app.ui.player.playbackSeason
import xyz.linplayer.app.ui.player.PlayerController
import xyz.linplayer.app.ui.player.VideoFit
import xyz.linplayer.app.ui.player.VideoSurface
import xyz.linplayer.app.ui.player.advancedNaturally
import xyz.linplayer.app.ui.player.failureDiag
import xyz.linplayer.app.ui.player.load
import xyz.linplayer.app.ui.player.loadEmbeddedFonts
import xyz.linplayer.app.ui.player.nextPreloadAllowed
import xyz.linplayer.app.ui.player.rememberExoPlayer
import xyz.linplayer.app.ui.player.sampleNetSpeed
import xyz.linplayer.app.ui.player.startDanmakuFor
import xyz.linplayer.app.ui.plugin.PluginKeys
import xyz.linplayer.app.ui.plugin.rememberPluginKeyTaken

/** OSD 自动收起。= Media3 默认值,别自己发明【继承 PC A-23】。 */
private const val OsdHideMs = 5000L
/** 进度条游标松手多久才真的 seek(§8.3)。 */
/** 跳过片头按钮停留多久(§8.5)。 */
private const val SkipShowMs = 8000L

/** 播放页的全部可变状态。拆成一个对象是为了 OSD / 面板两个文件共用,不是为了复用。 */
internal class PlayerUi {
    var position by mutableDoubleStateOf(0.0)
    var duration by mutableDoubleStateOf(0.0)
    var paused by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    /** ☠ 「时间真的往前走了」才撤黑幕,不是 `position > 0`。 */
    var everMoved by mutableStateOf(false)
    var buffered by mutableDoubleStateOf(0.0)
    var speed by mutableDoubleStateOf(1.0)
    var osd by mutableStateOf(true)
    var failed by mutableStateOf<String?>(null)
    var netSpeed by mutableStateOf("")
    var title by mutableStateOf("")
    /** 分集才有:`S1E3：集名`,与手机播放页同一格式。 */
    var episodeLabel by mutableStateOf("")
    val spec: String get() {
        val v = versions.firstOrNull { it.id == mediaSourceId } ?: return ""
        return listOfNotNull(resLabel(v).takeIf { it != "—" },
            v.of("Audio").firstOrNull()?.let { a -> listOfNotNull(codecName(a.codec), a.layout).joinToString(" ") }).joinToString(" · ")
    }
    var chapters by mutableStateOf<List<Pair<Double, String>>>(emptyList())
    var intro by mutableStateOf<ClosedFloatingPointRange<Double>?>(null)
    var outro by mutableStateOf<ClosedFloatingPointRange<Double>?>(null)
    var skipAuto by mutableStateOf(false)
    var skipWhat by mutableStateOf<String?>(null)
    var preview by mutableStateOf<Double?>(null)
    var thumb by mutableStateOf<ImageBitmap?>(null)
    var hold by mutableStateOf(false)
    var flash by mutableStateOf<Boolean?>(null)
    var seasonId by mutableStateOf<String?>(null)
    var seriesId by mutableStateOf<String?>(null)
    var episodes by mutableStateOf<List<Item>>(emptyList())
    var tracks by mutableStateOf<List<JsonObject>>(emptyList())
    var fit by mutableStateOf(VideoFit.Source)
    var versions by mutableStateOf<List<Version>>(emptyList())
    var mediaSourceId by mutableStateOf<String?>(null)
    val hasEpisodes get() = seasonId != null || seriesId != null

    fun next(cur: String) = episodes.indexOfFirst { it.id == cur }.let { i -> if (i >= 0) episodes.getOrNull(i + 1) else null }
    fun prev(cur: String) = episodes.indexOfFirst { it.id == cur }.let { i -> if (i > 0) episodes.getOrNull(i - 1) else null }
}

internal fun isOk(k: Key) = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter || k == Key.ButtonA

/**
 * 播放页(UI_TV.md §8)。
 *
 * ☠ **这一层和它上面的层不许有铺满的不透明底色**:SurfaceView 是在窗口上抠洞透出画面的,
 *   Compose 刷一块不透明背景 = 有声音、没画面、一条错都不报。黑底交给 Activity 的 windowBackground。
 * ☠ **根节点必须可聚焦并持有焦点**:OSD 收起时树上没有别的可聚焦件,不这么做「整屏黑着按什么都没反应」。
 * ★ 换集 / 换版本 / 换内核 / 换线路都在**这一页里**换目标,先等 stopPlayback 回来再起下一个 ——
 *   出栈再入栈的话,旧页的停播和新页的起播在核心层 worker 上并发,停播可能落在起播后面。
 */
@Composable
fun TvPlayerPage(r: TvRoute.Player, onExit: ((String?) -> Unit)? = null, onCompleted: (() -> Unit)? = null,
    feedOverlay: (@Composable (xyz.linplayer.app.ui.player.FeedPlayback) -> Unit)? = null,
    feedVolume: Float? = null, onFeedVolume: (Float) -> Unit = {},
    onSourceSwitch: ((xyz.linplayer.app.ui.pages.SourceSwitchTarget) -> Unit)? = null) {
    val app = LocalApp.current
    val session by app.session.collectAsState()
    val trackMemory = remember(session?.server, session?.userId) { SessionTrackMemory() }
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val overlay = rememberOverlay()
    val mem = LocalFocusMemory.current
    val ui = remember { PlayerUi() }
    val pluginPanels = xyz.linplayer.app.ui.plugin.rememberPlayerSurfaces("panel")
    var feedMuted by remember { mutableStateOf(feedVolume == 0f) }

    /* 壁纸的三个暂停条件之一(D443)。取**页面在不在**而不是插件的
       `player.start` / `player.end`:那两条事件只在核心层的插件运行时里发,
       过不了 FFI;而 ExoPlayer 那一路连 `player.status` 都不发。 */
    DisposableEffect(Unit) {
        xyz.linplayer.app.ui.plugin.WallpaperGate.set(xyz.linplayer.app.ui.plugin.WallpaperGate.PLAYER, true)
        onDispose { xyz.linplayer.app.ui.plugin.WallpaperGate.set(xyz.linplayer.app.ui.plugin.WallpaperGate.PLAYER, false) }
    }
    var target by remember { mutableStateOf(r) }
    var sourceSwitch by remember(target) { mutableStateOf(false) }
    var sourceExit by remember { mutableStateOf<xyz.linplayer.app.ui.pages.SourceSwitchTarget?>(null) }
    val initialMode = remember { r.engine ?: UiPrefs.engine.value }
    LaunchedEffect(target.itemId) {
        ui.intro = null; ui.outro = null
        ui.title = target.title
        ui.episodeLabel = ""
        ui.versions = emptyList()
    }
    val initialSubtitle = remember(target.subtitle) {
        target.subtitle?.let { kotlinx.serialization.json.Json.decodeFromString<xyz.linplayer.app.ui.player.InitialSubtitle>(it) }
    }
    var attempt by remember { mutableIntStateOf(0) }
    var autoRetried by remember(target) { mutableStateOf(false) }
    var adoptedPlayback by remember { mutableStateOf<JsonObject?>(null) }
    var switching by remember { mutableStateOf(false) }
    val controller = remember(target) {
        PlayerController(adoptedPlayback.str("engine") ?: if (target.localEntry || target.download || target.src != null) "mpv" else target.engine ?: initialMode,
            stopCall = { arguments, partial -> app.call("player.stopPlayback", arguments, partial) }) { command, arguments ->
            app.call(command, arguments)
        }.also { c -> adoptedPlayback?.takeIf { it.str("item_id") == target.itemId }?.let(c::resolved) }
    }
    val engine = controller.engine
    var finished by remember(controller) { mutableStateOf(false) }
    var episodeResult by remember(controller) { mutableStateOf<Result<List<Item>>?>(null) }
    var episodeRetry by remember(controller) { mutableIntStateOf(0) }
    val latestController by rememberUpdatedState(controller)
    var trackPrefs by remember { mutableStateOf<xyz.linplayer.app.ui.player.TrackPrefs?>(null) }
    var bufferTargetBytes by remember { mutableStateOf(0L) }
    var subOffPref by remember { mutableStateOf(false) }
    var preferredSpeed by remember { mutableDoubleStateOf(1.0) }
    var speedBusy by remember { mutableStateOf(false) }
    var speedWrite by remember { mutableStateOf<Job?>(null) }
    var pendingSpeed by remember { mutableStateOf<Double?>(null) }
    var temporarySpeedWrites by remember(controller) { mutableIntStateOf(0) }
    LaunchedEffect(controller, controller.manualSubtitleOff) {
        controller.manualSubtitleOff?.let { subOffPref = it }
    }
    LaunchedEffect(Unit) {
        PlayerController.awaitLocalStop()
        val p = runCatching { app.call("prefs.getPrefs") }.getOrNull().obj()
        bufferTargetBytes = p.long("buffer_target_bytes") ?: 0L
        preferredSpeed = (p.dbl(if (feedOverlay == null) "default_speed" else "liuying_speed") ?: 1.0).coerceIn(.25, 4.0)
        trackPrefs = xyz.linplayer.app.ui.player.TrackPrefs(
            subLang = p.str("sub_lang"), audioLang = p.str("audio_lang"),
            subRegex = p.str("sub_regex") ?: "", audioRegex = p.str("audio_regex") ?: "",
            subEnabled = initialSubtitle?.let { it.index != null } ?: (p?.get("sub_enabled")?.let { p.bool("sub_enabled") } ?: true),
        )
        subOffPref = p?.get("sub_enabled")?.let { !p.bool("sub_enabled") } ?: false
    }
    val exo = rememberExoPlayer(engine == "exo" && trackPrefs != null, trackPrefs, bufferTargetBytes, initialSubtitle != null, preload = true)
    val latestExo by rememberUpdatedState(exo)
    val renderedFirstFrame = xyz.linplayer.app.ui.player.rememberRenderedFirstFrame(exo, controller to attempt)
    SideEffect { controller.bind(exo) }
    ObserveSeekTimeout(controller)
    MatchTvRefreshRate(app, controller, exo, attempt,
        smoothOverlay = DanmakuStyle.enabled.value && DanmakuStyle.layout.value?.items?.isNotEmpty() == true,
        temporarySpeed = ui.hold || temporarySpeedWrites > 0)
    val root = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }

    // 与手机共用准备/预热命令；实际内核跨集后才接管会话，不重复起停。
    var preparedFor by remember { mutableStateOf<String?>(null) }
    var preparedRequest by remember { mutableStateOf<String?>(null) }
    var preparedResult by remember { mutableStateOf<JsonObject?>(null) }
    var prepareJob by remember { mutableStateOf<Job?>(null) }
    var selectedPrepared by remember { mutableStateOf<String?>(null) }
    var queuedRequest by remember { mutableStateOf<String?>(null) }
    var queuedItem by remember { mutableStateOf<Item?>(null) }
    var queueHandoff by remember { mutableStateOf<Job?>(null) }
    var queueAttempt by remember(controller) { mutableStateOf<String?>(null) }
    fun cancelPrepared(request: String?) {
        request ?: return
        app.bg.launch { runCatching { app.call("player.cancelPrepared", args("request_id" to request)) } }
    }
    fun cancelPreparation() {
        cancelPrepared(queuedRequest)
        queuedRequest = null; queuedItem = null
        prepareJob?.cancel()
        cancelPrepared(preparedRequest)
        cancelPrepared(selectedPrepared)
        selectedPrepared = null; preparedFor = null; preparedRequest = null; preparedResult = null
        ExoPreload.of(latestExo)?.cancel()
    }
    fun prepareEpisode(id: String) {
        if (preparedFor == id) return
        cancelPreparation()
        val request = java.util.UUID.randomUUID().toString()
        preparedFor = id
        preparedRequest = request
        prepareJob = scope.launch {
            val result = runCatching { app.call("player.prepareNext", args("item_id" to id, "request_id" to request)).obj() }
            coroutineContext.ensureActive()
            if (preparedRequest == request) preparedResult = result.getOrNull()
        }
    }

    fun stopPlayback() {
        if (stopped) return
        stopped = true
        cancelPreparation()
        PlaybackService.stop(ctx)
        ExoPreload.of(latestExo)?.deactivate()
        xyz.linplayer.app.ui.player.DanmakuSession.invalidate()
        Libass.reset()
        val pos = if (ui.duration > 0 && ui.position >= ui.duration - 2) ui.duration else ui.position
        latestController.stopIn(app.bg, pos, app::report, positionAtStop = {
            if (ui.duration > 0 && ui.position >= ui.duration - 2) ui.duration else ui.position
        }, beforeStop = {
            speedWrite?.join()
            runCatching { app.call("companion.setNowPlaying", args("title" to "")) }
        })
    }
    val leave: () -> Unit = {
        leaving = true
    }
    LaunchedEffect(leaving, sourceExit) {
        if (!leaving && sourceExit == null) return@LaunchedEffect
        leaving = true
        queueHandoff?.join()
        stopPlayback()
        val next = sourceExit
        if (next != null) {
            PlayerController.awaitPendingStop()
            if (!app.privateSpace.value) onSourceSwitch?.invoke(next)
        } else if (onExit != null) onExit(target.itemId.takeIf { !target.localEntry && !target.download && target.src == null })
        else nav.pop()
    }

    suspend fun switchTo(next: TvRoute.Player) {
        if (switching || controller.switching || leaving || latestController !== controller) return
        switching = true
        try {
            speedWrite?.join()
            if (leaving) return
            PlaybackService.volumeBeforeSwitch()?.let { runCatching { controller.volume(it) } }
            if (!target.localEntry && !target.download && target.src == null && next.src == null &&
                next.versionId == null && next.itemId != target.itemId) prepareEpisode(next.itemId)
            else cancelPreparation()
            if (queuedRequest != null) cancelPreparation()
            PlaybackService.stop(ctx)
            controller.stop(ui.position, handoff = true)
            coroutineContext.ensureActive()
            if (leaving) return
            // 只接管已就绪的预取结果;没就绪或换的不是预取那一集就撤掉
            val adopt = preparedResult.str("request_id")?.takeIf {
                next.src == null && next.versionId == null && next.itemId == preparedFor && next.itemId != target.itemId
            }
            if (adopt != null) {
                prepareJob?.cancel()
                selectedPrepared = adopt; preparedFor = null; preparedRequest = null; preparedResult = null
            } else cancelPreparation()
            overlay.close()
            target = if (next.itemId != target.itemId || next.versionId != target.versionId)
                next.copy(subtitle = null) else next
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { app.report(failure); ui.failed = failure.message ?: "切换播放失败" }
        finally { switching = false }
    }

    fun doSeek(t: Double) {
        if (switching) return
        if (queuedRequest != null) cancelPreparation()
        val to = t.coerceIn(0.0, if (ui.duration > 0) ui.duration else Double.MAX_VALUE)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { runCatching { controller.seek(to) } }
    }
    fun doSeekBy(delta: Double) {
        if (switching) return
        if (queuedRequest != null) cancelPreparation()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { runCatching { controller.seekBy(delta, ui.position, ui.duration) } }
    }
    fun doPause(want: Boolean) {
        PlaybackService.onUserPause(want)
        scope.launch { runCatching { controller.pause(want) } }
    }
    fun togglePause() {
        val want = !ui.paused
        doPause(want)
        ui.flash = !want
    }
    LaunchedEffect(ui.flash) {
        if (ui.flash != null) { delay(600); ui.flash = null }
    }
    fun doSpeed(v: Double) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            temporarySpeedWrites++
            try { runCatching { controller.speed(v) }.onFailure(app::report) }
            finally { temporarySpeedWrites-- }
        }
    }

    fun saveSpeed(v: Double) {
        if (!controller.ready || controller.switching || switching || leaving || stopped || !v.isFinite() || v !in .25..4.0) return
        pendingSpeed = v
        if (speedBusy) return
        speedBusy = true
        // 保存不随离页取消；内核操作仍受当前会话和停止屏障保护。
        speedWrite = app.bg.launch(start = CoroutineStart.UNDISPATCHED) {
            val active = { latestController === controller && controller.ready && !leaving && !stopped }
            try {
                while (pendingSpeed != null) {
                    val next = pendingSpeed!!
                    pendingSpeed = null
                    val before = ui.speed
                    try {
                        if (active()) {
                            if (!controller.speed(next, active) && active()) break
                            if (active()) ui.speed = next
                        }
                        // 已接受的选择离页后仍落库，停止后的旧内核不再应用倍率。
                        app.call("player.setPlaybackPrefs", args((if (feedOverlay == null) "default_speed" else "liuying_speed") to next))
                        preferredSpeed = next
                    } catch (error: Exception) {
                        if (active()) {
                            if (runCatching { controller.speed(before, active) }.onFailure(app::report).getOrDefault(false) && active())
                                ui.speed = before
                        }
                        app.report(error)
                    }
                }
            } finally { pendingSpeed = null; speedBusy = false }
        }
    }
    var skippedIntro by remember(target) { mutableStateOf(false) }
    var skippedOutro by remember(target) { mutableStateOf(false) }
    var markerWriting by remember(controller) { mutableStateOf(false) }
    var markerReloading by remember(controller) { mutableStateOf(false) }
    var markerRevision by remember(controller) { mutableIntStateOf(0) }
    val ctl = PlayerCtl(::doSeek, ::doPause, ::saveSpeed, trackPicked = controller::trackPicked,
        pickTrack = controller::pickTrack, pickExoTrack = controller::pickExoTrack, observeTracks = controller::observeTracks,
        markersSaved = { if (!leaving && latestController === controller) { markerReloading = true; markerRevision++ } },
        markersWriting = { if (latestController === controller) markerWriting = it },
        switchEngine = {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (switching || leaving) return@launch
                switching = true
                try {
                    speedWrite?.join()
                    cancelPreparation()
                    PlaybackService.volumeBeforeSwitch()?.let { controller.volume(it) }
                    controller.switchEngine(exo, subOffPref, isCurrent = { !leaving && latestController === controller },
                        beforeStop = { overlay.close(); PlaybackService.stop(ctx) })
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    app.report(error)
                    if (!controller.ready) ui.failed = "切换播放内核失败，请返回后重试"
                } finally { switching = false }
            }
        }) { scope.launch { switchTo(it) } }

    // ---------------------------------------------------------------- 起播
    val playbackPrefs = trackPrefs
    LaunchedEffect(target, attempt, engine, playbackPrefs) {
        if (playbackPrefs == null) return@LaunchedEffect
        if (leaving || stopped) return@LaunchedEffect
        controller.begin()
        subOffPref = controller.fallback?.subOff ?: initialSubtitle?.let { it.index == null } ?: (trackPrefs?.subEnabled == false)
        ui.tracks = emptyList()
        ui.preview = null
        finished = false
        val adopted = adoptedPlayback?.takeIf { it.str("item_id") == target.itemId }
        if (adopted != null) {
            adoptedPlayback = null
            controller.resolved(adopted)
            controller.started()
            ui.mediaSourceId = adopted.str("media_source_id")
            ui.buffering = exo?.playbackState == androidx.media3.common.Player.STATE_BUFFERING
            if (exo != null) launch { loadEmbeddedFonts(adopted.str("play_url") ?: return@launch) }
            runCatching { PlaybackService.start(ctx, app, exo, target.title, controller) }.onFailure(app::report)
            runCatching { app.call("companion.setNowPlaying", args("title" to target.title)) }
            launch { startDanmakuFor(app, target.itemId) }
            return@LaunchedEffect
        }
        xyz.linplayer.app.ui.player.DanmakuSession.invalidate()
        ui.mediaSourceId = null
        ui.everMoved = false; ui.buffering = true; ui.position = 0.0; ui.duration = 0.0
        ui.buffered = 0.0
        ui.failed = null; ui.skipWhat = null; ui.osd = true
        PlayerController.awaitLocalStop()
        coroutineContext.ensureActive()
        // ☠ 换片连 libass 的事件缓存一起清,和手机端同一顺序
        ExoPreload.of(exo)?.deactivate()
        Libass.reset()
        val prepared = selectedPrepared
        selectedPrepared = null
        runCatching {
            if (feedOverlay != null) feedVolume?.let { controller.volume(it) }
            when {
                target.download -> app.call("player.playLocal", args("id" to target.itemId))
                // 数据源:取流、解析、请求头都在核心层(source.playItem)
                target.src != null -> app.call("source.playItem", kotlinx.serialization.json.Json.parseToJsonElement(target.src!!) as kotlinx.serialization.json.JsonObject)
                target.localEntry -> app.call("source.play", args("entry_id" to target.itemId, "entry_name" to target.title,
                    "resume_secs" to (target.resumeAt ?: 0.0)))
                else -> {
                    // 没按过版本确认就不传 media_source_id(§11.2):否则核心层的版本正则整个被跳过
                    val a = buildMap<String, Any> {
                        put("item_id", target.itemId)
                        put("engine", engine)
                        if (feedOverlay != null) put("speed_scope", "liuying")
                        prepared?.let { put("prepared_id", it) }
                        target.versionId?.let { put("media_source_id", it) }
                        if (target.fromStart) put("from_start", true)
                        target.resumeAt?.let { put("resume_secs", it) }
                    }
                    val res = app.call("player.play", controller.playbackArgs(a)).obj()
                    coroutineContext.ensureActive()
                    controller.resolved(res)
                    ui.mediaSourceId = res.str("media_source_id")
                    val url = res.str("play_url")
                    ui.speed = controller.fallback?.speed ?: preferredSpeed
                    if (exo != null && url != null) {
                        exo.setPlaybackSpeed(preferredSpeed.toFloat())
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, subOffPref).build()
                        exo.load(url, res.dbl("resume_secs") ?: 0.0, res?.get("external_subs"))
                        launch { loadEmbeddedFonts(url) }
                    }
                    controller.restoreState()
                    controller.fallback?.let {
                        subOffPref = it.subOff
                        ui.speed = it.speed
                        app.call("player.setAspectRatio", args("ratio" to ui.fit.mpvRatio))
                        if (ui.hold) doSpeed(UiPrefs.tvHoldSpeed.value)
                    }
                }
            }
            controller.started()
            // 音频焦点、系统媒体控制和进度上报交给与手机端同一个服务;TV 离开前台照旧停播
            runCatching { PlaybackService.start(ctx, app, exo, target.title, controller) }.onFailure { app.report(it) }
        }.onFailure { e ->
            cancelPrepared(prepared)
            if (e is kotlinx.coroutines.CancellationException) throw e
            // 可重试的错(服务端 500、网络抖一下)先自己重来一次:多数时候第二次就起来了,不必让用户看失败页
            if (!autoRetried && (e as? CoreException)?.retryable == true) { autoRetried = true; attempt++; return@LaunchedEffect }
            // 第二行写核心层原话(§8.6),给人看的那句在第一行
            ui.failed = e.message ?: "起播失败"
            if (target.src != null && !app.privateSpace.value) sourceSwitch = true
        }
        runCatching { app.call("companion.setNowPlaying", args("title" to target.title)) }
        if (!target.localEntry && !target.download && target.src == null) launch { startDanmakuFor(app, target.itemId) }
    }

    // 剧集上下文、规格行、章节 / 片头片尾
    LaunchedEffect(controller, trackMemory, episodeRetry) {
        controller.clearSeriesContext(trackMemory)
        episodeResult = null
        ui.episodes = emptyList(); ui.seasonId = null; ui.seriesId = null
        if (target.localEntry || target.download || target.src != null) {
            controller.seriesContext(trackMemory, null)
            episodeResult = Result.success(emptyList())
            return@LaunchedEffect
        }
        val loaded = runCatching { app.call("emby.itemDetail", args("item_id" to target.itemId)).obj() }
        val d = loaded.getOrNull()
        coroutineContext.ensureActive()
        controller.seriesContext(trackMemory, sessionTrackScope(session, d, target.itemId))
        ui.seasonId = d.str("season_id"); ui.seriesId = d.str("series_id")
        if (d.str("type_") == "Episode") {
            ui.title = d.str("series_name") ?: target.title
            val number = listOfNotNull(d.long("season_no")?.let { "S$it" }, d.long("episode_no")?.let { "E$it" }).joinToString("")
            ui.episodeLabel = listOf(number, d.str("name") ?: target.title).filter { it.isNotBlank() }.joinToString("：")
        }
        launch {
            val result = runCatching { playbackSeason(app, loaded.getOrThrow(), target.itemId) }
            coroutineContext.ensureActive()
            ui.episodes = result.getOrDefault(emptyList())
            episodeResult = result
        }
        val versions = Version.list(runCatching { app.call("emby.itemMedia", args("item_id" to target.itemId)) }.getOrNull())
        coroutineContext.ensureActive()
        ui.versions = versions
    }
    LaunchedEffect(controller, finished, episodeResult, leaving, queuedRequest, switching) {
        if (!finished || leaving || queuedRequest != null || switching) return@LaunchedEffect
        if (onCompleted != null) { onCompleted(); return@LaunchedEffect }
        val result = episodeResult ?: return@LaunchedEffect
        if (result.isFailure) {
            ui.failed = "无法加载下一集：${result.exceptionOrNull()?.message ?: "分集加载失败"}"
        } else ui.next(target.itemId)?.let { n -> switchTo(TvRoute.Player(n.id, cardTitleOf(n))) } ?: leave()
    }
    LaunchedEffect(controller, ui.duration, markerRevision) {
        if (ui.duration <= 0 || target.localEntry || target.download || target.src != null) return@LaunchedEffect
        markerReloading = true
        val c = runCatching { app.call("player.chapterInfo", args("item_id" to target.itemId, "runtime_secs" to ui.duration)) }.getOrNull().obj()
        coroutineContext.ensureActive()
        ui.chapters = c?.get("chapters").arr().mapNotNull { it.obj() }.mapNotNull { o -> o.dbl("start_secs")?.let { it to (o.str("name") ?: "") } }
        fun range(k: String) = c?.get(k).obj()?.let { o -> val s = o.dbl("start"); val e = o.dbl("end"); if (s != null && e != null && e > s) s..e else null }
        val nextIntro = range("intro"); val nextOutro = range("outro")
        if (markerRevision > 0) {
            if (ui.intro != nextIntro) skippedIntro = false
            if (ui.outro != nextOutro) skippedOutro = false
        }
        ui.intro = nextIntro; ui.outro = nextOutro; ui.skipAuto = c.bool("skip_auto")
        markerReloading = false
        if (c == null && markerRevision > 0) app.toast("标记已保存，刷新失败，请重新打开播放页")
        if (markerRevision > 0) {
            ui.skipWhat = null
            if (ui.intro?.contains(ui.position) == true) { skippedIntro = true; ui.skipWhat = "intro" }
            if (ui.outro?.contains(ui.position) == true) { skippedOutro = true; ui.skipWhat = "outro" }
        }
    }

    // ---------------------------------------------------------------- 状态来源
    LaunchedEffect(engine, target) {
        if (exo != null) return@LaunchedEffect
        app.core.events.collect { ev ->
            if (ev.name != "player.status") return@collect
            if (!controller.ready) return@collect
            val o = ev.data.obj()
            if (!target.localEntry && !target.download && target.src == null && o.str("item_id")?.let { it != target.itemId } == true) return@collect
            val p = o.dbl("position") ?: 0.0
            if (advancedNaturally(ui.position, p)) ui.everMoved = true
            ui.position = p
            o.dbl("duration")?.takeIf { it > 0 }?.let { ui.duration = it }
            ui.paused = o.bool("paused")
            ui.buffering = o.bool("buffering")
            if (!speedBusy && !ui.hold && temporarySpeedWrites == 0)
                o.dbl("speed")?.takeIf { it.isFinite() && it in .25..4.0 }?.let { ui.speed = it }
            controller.observePosition(p, ui.buffering)
            // ☠ keep-open 下 END_FILE 永远不发,判播完读 eof;**只收尾一次**
            if (o.bool("eof") && !finished && ui.failed == null) {
                if (ui.everMoved && ui.duration > 0 && p >= ui.duration - 5) finished = true
                else ui.failed = if (ui.everMoved) "播放提前结束，请检查网络或片源" else failureDiag(app)
            }
        }
    }
    // 补帧跑不动 / 这一集不该补:核心层撤档后发这条,原因原样给用户
    LaunchedEffect(engine, controller) {
        if (exo != null) return@LaunchedEffect
        app.core.events.collect { ev ->
            if (ev.name != "player.interpReverted") return@collect
            app.toast(ev.data.obj().str("note") ?: "补帧已关闭", xyz.linplayer.app.data.ToastKind.Error)
        }
    }
    LaunchedEffect(exo, target, attempt) {
        val e = exo ?: return@LaunchedEffect
        while (true) {
            if (!controller.ready) { delay(250); continue }
            val p = e.currentPosition / 1000.0
            if (advancedNaturally(ui.position, p)) ui.everMoved = true
            ui.position = p
            e.duration.takeIf { it > 0 }?.let { ui.duration = it / 1000.0 }
            ui.paused = !e.playWhenReady
            ui.buffering = e.playbackState == androidx.media3.common.Player.STATE_BUFFERING
            if (!speedBusy && !ui.hold && temporarySpeedWrites == 0) ui.speed = e.playbackParameters.speed.toDouble()
            controller.observePosition(p, ui.buffering)
            e.playerError?.let { err ->
                try {
                    cancelPreparation()
                    val state = controller.snapshot(e, subOffPref)
                    // 临时倍率不能成为新内核的正常倍率；松键可能发生在停止屏障内。
                    val normalState = if (ui.hold || temporarySpeedWrites > 0) state.copy(speed = ui.speed) else state
                    if (controller.tryFallback(err, normalState) { PlaybackService.stop(ctx) }) {
                        overlay.close()
                        app.toast("Media3 无法播放，已切换到 MPV")
                        return@LaunchedEffect
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) { app.report(failure) }
                ui.failed = xyz.linplayer.app.ui.player.playbackAdvice(err)
                return@LaunchedEffect
            }
            if (e.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                if (ui.everMoved && ui.duration > 0 && p >= ui.duration - 5) finished = true
                else ui.failed = "播放提前结束，请检查网络或片源"
                return@LaunchedEffect
            }
            delay(250)
        }
    }
    // 黑幕兜底:4 秒且不在缓冲 → 撤;12 秒无条件撤(状态事件发不出来时 buffering 停在初值 true)
    LaunchedEffect(target, engine) { delay(4000); if (!ui.buffering) ui.everMoved = true }
    LaunchedEffect(target, engine) { delay(12_000); ui.everMoved = true }
    LaunchedEffect(Unit) { sampleNetSpeed { ui.netSpeed = it } }
    val nextItem = if (onCompleted == null) ui.next(target.itemId) else null
    val prepareSoon = controller.ready && !ui.buffering && !leaving && !switching && !controller.switching && nextItem != null &&
        !target.localEntry && !target.download && target.src == null &&
        ui.duration > 0 && ui.position >= (ui.duration - 60).coerceAtLeast(0.0)
    // 片尾「接下来」胶囊与手机共用时间窗，确认键仅在胶囊聚焦时切集。
    val nextUp = nextItem?.takeIf {
        xyz.linplayer.app.ui.player.nextUpForOutro(ui.position, ui.duration, ui.outro) && !markerReloading && !markerWriting && controller.ready && ui.everMoved &&
            ui.failed == null && !leaving && !overlay.isOpen
    }
    fun playNext() { ui.next(target.itemId)?.let { n -> scope.launch { switchTo(TvRoute.Player(n.id, cardTitleOf(n))) } } }
    val endPrompt = nextUp != null || (!markerReloading && !markerWriting && ui.skipWhat == "outro" && ui.outro?.contains(ui.position) == true &&
        controller.ready && ui.everMoved && ui.failed == null && !leaving && !overlay.isOpen)
    fun performEndPrompt() { if (nextUp != null) playNext() else if (endPrompt) skipNow(ui, ::doSeek, ::playNext) }
    LaunchedEffect(controller, nextItem?.id, prepareSoon) {
        if (prepareSoon && queuedRequest == null) prepareEpisode(nextItem.id)
    }
    var warmingVideo by remember(controller) { mutableStateOf(false) }
    val allowVideoWarm = prepareSoon && nextPreloadAllowed(warmingVideo, ui.buffering, ui.position, ui.duration, ui.buffered, ui.speed)
    val latestAllowVideoWarm by rememberUpdatedState(allowVideoWarm)
    LaunchedEffect(controller, exo) {
        // 串行发送启停,避免慢的「开始」响应越过后来的「暂停」
        snapshotFlow { preparedResult.str("request_id") to latestAllowVideoWarm }.collect { (request, enabled) ->
            warmingVideo = enabled
            if (request != null && request != queuedRequest) {
                if (enabled) preparedResult.str("play_url")?.let { url -> ExoPreload.of(exo)?.prepare(url, preparedResult?.get("external_subs")) }
                else if (!switching) ExoPreload.of(exo)?.cancel()
                runCatching { app.call("player.warmPrepared", args("request_id" to request, "enabled" to enabled)) }
                coroutineContext.ensureActive()
            }
        }
    }
    fun adoptQueued(result: JsonObject, item: Item, actualEngine: String) {
        adoptedPlayback = JsonObject(result + args("engine" to actualEngine))
        // 离页收尾可能还持有旧控制器，先同步实际播放身份。
        latestController.resolved(result)
        selectedPrepared = null
        preparedFor = null; preparedRequest = null; preparedResult = null
        queuedRequest = null; queuedItem = null
        overlay.close()
        ui.position = if (actualEngine == "exo") latestExo?.currentPosition?.div(1000.0) ?: 0.0 else result.dbl("resume_secs") ?: 0.0
        ui.duration = if (actualEngine == "exo") latestExo?.duration?.takeIf { it > 0 }?.div(1000.0) ?: 0.0 else 0.0
        ui.buffered = 0.0
        ui.failed = null; ui.skipWhat = null
        ui.chapters = emptyList(); ui.intro = null; ui.outro = null
        target = target.copy(itemId = item.id, title = cardTitleOf(item), versionId = null, fromStart = false,
            resumeAt = null, audioIndex = null, subIndex = null, subOff = false, subtitle = null)
    }
    LaunchedEffect(Unit) {
        app.core.events.collect { event ->
            val result = event.data.obj() ?: return@collect
            if (event.name == "player.queueCanceled" && result.str("request_id") == queuedRequest) {
                cancelPreparation()
                return@collect
            }
            if (event.name != "player.queueAdvanced" || leaving || stopped || switching) return@collect
            val item = queuedItem ?: return@collect
            if (latestExo == null && result.str("request_id") == queuedRequest && result.str("item_id") == item.id)
                adoptQueued(result, item, "mpv")
        }
    }
    // 只在当前集已经缓冲到尾部时排队，准备/排队失败仍走正常完播切集。
    val queueSoon = prepareSoon && ui.duration - ui.position <= 10.0 && ui.buffered >= ui.duration - 1.0
    LaunchedEffect(controller, queueSoon, preparedResult) {
        val request = preparedResult.str("request_id") ?: return@LaunchedEffect
        val item = nextItem ?: return@LaunchedEffect
        if (!queueSoon || queueAttempt == request || switching || leaving) return@LaunchedEffect
        queueAttempt = request
        queuedRequest = request; queuedItem = item
        val result = try {
            app.call("player.queuePrepared", args("request_id" to request,
                "current_item_id" to target.itemId, "engine" to engine)).obj() ?: error("下一集未进入播放队列")
        } catch (failure: Exception) {
            if (queuedRequest == request) cancelPreparation() else cancelPrepared(request)
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return@LaunchedEffect
        }
        if (latestController !== controller || leaving || stopped || switching || preparedRequest != request) {
            if (queuedRequest == request) cancelPreparation() else cancelPrepared(request)
            return@LaunchedEffect
        }
        if (exo != null) {
            val owner = ExoPreload.of(exo)
            val accepted = owner?.queue(result.str("play_url") ?: "", result["external_subs"]) {
                if (queuedRequest != request || leaving || stopped || switching || latestController !== controller) return@queue
                queuedRequest = null; preparedRequest = null; preparedResult = null; queuedItem = null
                val previousDuration = ui.duration
                ui.position = exo.currentPosition / 1000.0
                ui.duration = exo.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0
                switching = true
                queueHandoff = PlayerController.handoffIn(app.bg) {
                    try {
                        controller.begin()
                        val adopted = app.call("player.advanceQueued", args("request_id" to request, "pos" to previousDuration)).obj()
                            ?: error("下一集会话接管失败")
                        controller.resolved(adopted)
                        owner.trimPrevious()
                        adoptQueued(adopted, item, "exo")
                    } catch (failure: Exception) {
                        exo.pause()
                        ui.failed = "下一集会话接管失败"
                        app.report(failure)
                    } finally { switching = false }
                }
            } == true
            if (!accepted) cancelPreparation()
        }
    }
    DisposableEffect(session?.server, session?.userId, session?.token) {
        onDispose { cancelPreparation() }
    }
    // 缓冲末端在 OSD 亮着或片尾预取时要:事件里没有这个字段,命令版才有
    LaunchedEffect(ui.osd, exo, prepareSoon) {
        while (ui.osd || prepareSoon) {
            ui.buffered = if (exo != null) exo.bufferedPosition / 1000.0
            else runCatching { app.call("player.status") }.getOrNull().obj().dbl("buffered") ?: ui.buffered
            delay(1000)
        }
    }
    // 起播成功后轨表**轮询到稳定**:每 700ms 一次,约 11 秒兜底;不在「第一次非空」时就停(音轨先出来、字幕永远进不了面板)
    val tracksReady = controller.ready
    LaunchedEffect(target, engine, tracksReady, controller.seriesScope) {
        if (exo != null || !tracksReady) return@LaunchedEffect
        var stable = 0
        var applied = false
        repeat(16) {
            delay(700)
            val list = runCatching { app.call("player.tracks") }.getOrNull().arr().mapNotNull { it.obj() }
            if (list.size == ui.tracks.size && list.isNotEmpty()) stable++ else stable = 0
            ui.tracks = list
            controller.observeTracks(list)
            if (stable >= 2 && !applied) {
                applied = true
                applyPickedTracks(controller, target, list)
            }
            if (controller.ready && stable >= 2) runCatching {
                controller.restoreTracks(list)
                initialSubtitle?.let { controller.restoreInitialSubtitle(null, list, it)?.let { off -> subOffPref = off } }
                controller.restoreSessionTracks(null, list, buildSet {
                    if (target.audioIndex != null) add("audio")
                    if (target.subIndex != null || target.subOff || initialSubtitle != null) add("sub")
                })?.let { subOffPref = it }
            }.onFailure { app.report(it) }
        }
    }
    val sessionTracks = controller.hasSessionTracks
    LaunchedEffect(controller, tracksReady, controller.seriesScope, sessionTracks, exo) {
        if (!tracksReady || (!sessionTracks && initialSubtitle == null && controller.fallback == null)) return@LaunchedEffect
        repeat(16) {
            delay(700)
            runCatching {
                // 换内核后 Media3 轨表晚到,按身份补回切换前的音轨 / 字幕
                if (exo != null) controller.restoreExoTracks(exo)
                if (exo != null) initialSubtitle?.let {
                    controller.restoreInitialSubtitle(exo, emptyList(), it)?.let { off -> subOffPref = off }
                }
                controller.restoreSessionTracks(exo, emptyList(), buildSet {
                    if (target.audioIndex != null) add("audio")
                    if (target.subIndex != null || target.subOff || initialSubtitle != null) add("sub")
                })?.let { subOffPref = it }
            }.onFailure { app.report(it) }
        }
    }
    // 跳过片头 / 片尾:每片各跳一次;倒回去看片头不许被再踹走
    LaunchedEffect(ui.position.toLong(), ui.intro, ui.outro, overlay.isOpen) {
        if (!controller.ready || switching || leaving || markerWriting || markerReloading || overlay.isOpen) return@LaunchedEffect
        val p = ui.position
        val intro = ui.intro
        val outro = ui.outro
        when {
            intro != null && !skippedIntro && p >= intro.start && p < intro.endInclusive - 1 -> {
                skippedIntro = true
                if (ui.skipAuto) { doSeek(intro.endInclusive); app.toast("已跳过片头") }
                else ui.skipWhat = "intro"
            }
            // 片尾只在后面还有内容时才跳【继承 PC A-18】:片尾就是片子最后一段时,跳过去等于直接播完
            outro != null && !skippedOutro && p >= outro.start && p < outro.endInclusive - 1 &&
                (outro.endInclusive < ui.duration || ui.next(target.itemId) != null) -> {
                skippedOutro = true
                if (ui.skipAuto) { skipOutro(ui, ::doSeek, ::playNext); app.toast("已跳过片尾") }
                else ui.skipWhat = "outro"
            }
        }
    }
    // 按钮停留时长单独计时:上面那个效应随位置每秒重启,倒计时放在里面会被反复取消,按钮永远不消失
    LaunchedEffect(ui.skipWhat) { if (ui.skipWhat == "intro") { delay(SkipShowMs); ui.skipWhat = null } }
    LaunchedEffect(ui.position.toLong(), ui.intro, ui.outro) {
        if (ui.skipWhat == "intro" && ui.intro?.contains(ui.position) != true || ui.skipWhat == "outro" && ui.outro?.contains(ui.position) != true) ui.skipWhat = null
    }

    // ---------------------------------------------------------------- OSD 显隐与焦点
    var poke by remember { mutableIntStateOf(0) }
    var focusNextUpOnWake by remember(target) { mutableStateOf(false) }
    var revealNextUpKey by remember(target) { mutableStateOf(false) }
    var pendingNextUpConfirm by remember(target) { mutableStateOf(false) }
    LaunchedEffect(ui.osd, overlay.isOpen, ui.paused, poke) {
        if (!ui.osd || overlay.isOpen || ui.paused) return@LaunchedEffect
        delay(OsdHideMs)
        ui.osd = false
    }
    fun focusPlayback(): Boolean = if (ui.osd && ui.duration > 0)
        mem.requesters["osd.bar"]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
        else runCatching { root.requestFocus() }.getOrDefault(false)
    LaunchedEffect(ui.duration > 0) {
        if (!ui.osd || overlay.isOpen) return@LaunchedEffect
        if (ui.duration <= 0 && mem.key == "osd.bar") root.requestFocus()
        else if (ui.duration > 0 && rootFocused) {
            withFrameNanos { }
            if (rootFocused) focusPlayback()
        }
    }
    LaunchedEffect(ui.skipWhat, endPrompt, nextUp != null, overlay.isOpen) {
        if (!ui.osd || overlay.isOpen) return@LaunchedEffect
        val previous = mem.key
        if (previous == "osd.next" && nextUp != null) {
            // 下一集卡接替已聚焦的小按钮；不打断其它控件上的操作。
            withFrameNanos { }
            if (mem.key == previous) mem.requesters["player.nextUp"]?.requestFocus()
        } else if (previous == "osd.skip" && ui.skipWhat == null || previous == "player.nextUp" && !endPrompt) {
            withFrameNanos { }
            if (mem.key == previous) focusPlayback()
        }
    }
    var fromPanel by remember { mutableStateOf(false) }
    LaunchedEffect(overlay.isOpen) { if (overlay.isOpen) fromPanel = true }
    LaunchedEffect(ui.osd, overlay.isOpen, ui.failed) {
        if (feedOverlay != null) return@LaunchedEffect
        if (overlay.isOpen || ui.failed != null) return@LaunchedEffect
        if (ui.osd && focusNextUpOnWake && endPrompt) {
            repeat(4) {
                withFrameNanos { }
                if (mem.requesters["player.nextUp"]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true) {
                    focusNextUpOnWake = false
                }
            }
            return@LaunchedEffect
        }
        repeat(8) {
            withFrameNanos { }
            // 半隐下一集被方向键或确认键唤醒时，保持焦点在卡片上，避免先落进度条。
            val ok = if (!ui.osd) runCatching { root.requestFocus() }.getOrDefault(false)
                else if (fromPanel) { fromPanel = false; true }
                else focusPlayback()
            if (ok) {
                if (focusNextUpOnWake) focusNextUpOnWake = false
                return@LaunchedEffect
            }
        }
    }
    // 预览游标的缩略图:没有本地字节时核心层回 available=false,只显示时间
    LaunchedEffect(ui.preview?.let { (it / 5).toLong() }) {
        val p = ui.preview ?: run { ui.thumb = null; return@LaunchedEffect }
        if (exo != null) return@LaunchedEffect
        delay(250)
        val o = runCatching { app.call("player.thumbnail", args("position" to p)) }.getOrNull().obj()
        ui.thumb = if (o.bool("available")) o.str("jpeg")?.let { b64 ->
            runCatching {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        } else null
    }

    DisposableEffect(ui.paused) {
        val w = activity?.window
        if (!ui.paused) w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // 主页键离开应用 → 暂停 + 落库 + 停止(TV 不做后台播放)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller, engine) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) { doPause(true); leave() } }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    DisposableEffect(Unit) { onDispose { stopPlayback() } }

    // ---------------------------------------------------------------- 返回键(§4.4 播放页那一列)
    // 初值不能是 0:开机两秒内 uptime 本身就小于 2000,第一下返回会被当成「再按一次」
    var backArmedAt by remember { mutableStateOf(Long.MIN_VALUE / 2) }
    BackHandler(enabled = ui.failed != null) { leave() }
    BackHandler(enabled = ui.failed == null) {
        if (feedOverlay != null) { leave(); return@BackHandler }
        val now = android.os.SystemClock.uptimeMillis()
        when {
            now - backArmedAt < 2000 -> leave()
            ui.osd -> ui.osd = false
            else -> { ui.osd = true; backArmedAt = now; app.toast("再按一次返回退出播放") }
        }
    }

    /* 插件的 `player.openPanel` / `setOsdVisible`(D67 D162)。和手机端同一个 host 口子,
       翻的是 TV 自己那套面板名 —— 两端面板不是一套,合成一套只会两边都错。 */
    DisposableEffect(exo, target.itemId, pluginPanels) {
        PluginPlayer.host = object : PluginPlayer.Host {
            override fun openPanel(which: String) {
                val kind = PluginPlayer.tvKind(which)
                ui.osd = true
                openPanel(kind, app, scope, overlay, ui, target, engine, exo, ctl, plugins = pluginPanels)
            }
            override fun setOsdVisible(visible: Boolean) { ui.osd = visible }
        }
        onDispose { PluginPlayer.host = null }
    }

    // ---------------------------------------------------------------- 按键
    var holdJob by remember { mutableStateOf<Job?>(null) }
    fun defaultKey(e: KeyEvent): Boolean {
        if (feedOverlay != null) {
            if (e.key == Key.MediaPlayPause) {
                if (e.type == KeyEventType.KeyDown) doPause(!ui.paused)
                return true
            }
            return false
        }
        poke++
        val down = e.type == KeyEventType.KeyDown
        when (e.key) {
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                if (down) {
                    val want = if (e.key == Key.MediaPlay) false else if (e.key == Key.MediaPause) true else !ui.paused
                    doPause(want)
                    // 播放/暂停媒体键**不唤出 OSD**,屏幕中央闪一次图标
                    if (!ui.osd) ui.flash = !want
                }
                return true
            }
            Key.MediaFastForward -> { if (down) doSeekBy(UiPrefs.tvSeekStep.value.toDouble()); return true }
            Key.MediaRewind -> { if (down) doSeekBy(-UiPrefs.tvSeekStep.value.toDouble()); return true }
            Key.MediaNext -> { if (down) ui.next(target.itemId)?.let { n -> scope.launch { switchTo(TvRoute.Player(n.id, cardTitleOf(n))) } }; return true }
            Key.MediaPrevious -> { if (down) ui.prev(target.itemId)?.let { n -> scope.launch { switchTo(TvRoute.Player(n.id, cardTitleOf(n))) } }; return true }
            Key.MediaStop -> { if (down) leave(); return true }
        }
        if (overlay.isOpen || ui.failed != null) return false
        if (e.key == Key.Menu) {
            if (!down) { ui.osd = true; openPanel("more", app, scope, overlay, ui, target, engine, exo, ctl, plugins = pluginPanels) }
            return true
        }
        if (isOk(e.key)) {
            if (revealNextUpKey) {
                if (!down) {
                    revealNextUpKey = false
                }
                return true
            }
            // 卡片从半隐态移动到按钮上方的几帧内，确认键不能落到根节点暂停。
            if (pendingNextUpConfirm) {
                if (!down) {
                    pendingNextUpConfirm = false
                    if (endPrompt) performEndPrompt()
                }
                return true
            }
            if (focusNextUpOnWake && endPrompt) {
                if (down && e.nativeKeyEvent.repeatCount == 0) pendingNextUpConfirm = true
                return true
            }
            if (!ui.osd && endPrompt) {
                if (down) {
                    revealNextUpKey = true
                    focusNextUpOnWake = true
                    ui.osd = true
                }
                return true
            }
            if (ui.osd && endPrompt && mem.key == "player.nextUp") return false
            // 长按确认 = 临时倍速,松开恢复;短按在松手时才算
            if (down && e.nativeKeyEvent.repeatCount == 0) {
                holdJob = if (ui.preview == null) scope.launch {
                    delay(600)
                    ui.hold = true
                    doSpeed(UiPrefs.tvHoldSpeed.value)
                } else null
            }
            if (down) return !ui.osd || rootFocused || ui.hold
            if (!ui.osd || rootFocused) {
                togglePause()
                return true
            }
            return false
        }
        val dir = e.key == Key.DirectionUp || e.key == Key.DirectionDown || e.key == Key.DirectionLeft || e.key == Key.DirectionRight
        // OSD 收起时方向键**只唤醒**,不移动焦点:否则第一次按键就误触
        if (dir && !ui.osd) {
            if (down) {
                if (endPrompt && (e.key == Key.DirectionUp || e.key == Key.DirectionDown)) focusNextUpOnWake = true
                ui.osd = true
            }
            return true
        }
        if (dir && rootFocused && down) {
            if (ui.duration > 0) focusPlayback()
            else mem.requesters[if (ui.next(target.itemId) != null && nextUp == null) "osd.next" else "osd.speed"]?.requestFocus()
            return true
        }
        return false
    }

    /* 插件覆盖层开着时这一下键先归插件(D563)。没开着就一句都不问 —— 判断在
       PluginKeys.gate 里,遥控器上每按一下都要过它。 */
    val taken = rememberPluginKeyTaken()
    fun onKey(e: KeyEvent): Boolean {
        // 开始于播放页的长按必须先收尾，不能被后来打开的面板吞掉松键。
        if (isOk(e.key) && e.type == KeyEventType.KeyUp) {
            val consumed = ui.hold || (holdJob != null && (overlay.isOpen || ui.failed != null))
            holdJob?.cancel()
            holdJob = null
            if (ui.hold) { ui.hold = false; doSpeed(ui.speed) }
            if (consumed) { taken.remove(e.key); return true }
        }
        return PluginKeys.gate(app, scope, taken, e, ::defaultKey)
    }

    // 插件的两个挂载点(SPEC 9.5)。拉不到是空表,播放页照放
    val pluginOverlays = xyz.linplayer.app.ui.plugin.rememberPlayerSurfaces("overlay")

    Box(Modifier.fillMaxSize().onPreviewKeyEvent(::onKey).testTag("player.root").focusRequester(root).onFocusChanged { rootFocused = it.isFocused }.focusable()) {
        if (exo != null) ExoSurface(exo, subOff = subOffPref, fit = ui.fit, m = Modifier.fillMaxSize())
        else VideoSurface(app.core, Modifier.fillMaxSize())
        if (DanmakuStyle.enabled.value) DanmakuLayer(DanmakuStyle.layout.value, ui.position, ui.paused, ui.speed, Modifier.fillMaxSize(), buffering = ui.buffering, app = app,
            opacity = DanmakuStyle.opacity.doubleValue)

        /* 插件覆盖层。摆在 OSD **之前**:盖住控制条等于把暂停和进度条一起弄没,
           接管控制条是 `osd[]` 贡献点的事。不声明 interactive 的那批连焦点都进不去。 */
        xyz.linplayer.app.ui.plugin.PlayerOverlays(pluginOverlays, interactive = false)
        xyz.linplayer.app.ui.plugin.PlayerOverlays(pluginOverlays, interactive = true)
        val failed = ui.failed
        val controlsVisible = failed == null && feedOverlay == null && ui.osd && !overlay.isOpen
        if (failed != null) PlayerFailure(failed, retried = autoRetried, finished = finished, onRetry = {
            if (finished) { ui.failed = null; episodeResult = null; episodeRetry++ } else attempt++
        }, onSwitchSource = if (target.src != null && !app.privateSpace.value && !finished)
            ({ sourceSwitch = true }) else null)
        else if (!ui.everMoved && !renderedFirstFrame) Curtain(ui, showSpeed = !controlsVisible)
        else if (ui.buffering) BufferingHint(ui, showSpeed = !controlsVisible)
        if (failed == null && feedOverlay == null) {
            if (controlsVisible) Osd(ui, target, engine, root,
                onSeek = ::doSeek, seekPosition = controller.seekTarget ?: ui.position, onPause = ::togglePause,
                nextUpVisible = nextUp != null,
                onNext = { ui.next(target.itemId)?.let { n -> scope.launch { switchTo(TvRoute.Player(n.id, cardTitleOf(n))) } } },
                onPanel = { which ->
                    openPanel(which, app, scope, overlay, ui, target, engine, exo, ctl,
                        pluginPanels.firstOrNull { xyz.linplayer.app.ui.plugin.pluginPanelKind(it) == which }, pluginPanels)
                },
            )
            PlayerTopStatus(ui, controlsVisible,
                showIntro = ui.skipWhat == "intro" && !markerReloading && !markerWriting && !endPrompt && !overlay.isOpen,
                playbackFocus = root, onSkip = { skipNow(ui, ::doSeek, ::playNext) })
            // 控件收起时底部细进度条,与手机一致
            xyz.linplayer.app.ui.player.PassiveProgress(ui.position, ui.duration,
                visible = !ui.osd && !overlay.isOpen && controller.ready && ui.everMoved,
                m = Modifier.align(Alignment.BottomCenter))
            if (endPrompt) NextUpChip(ui, nextUp, root, ::performEndPrompt)
            Moments(ui)
        }
        OverlayHost(overlay)
        feedOverlay?.invoke(xyz.linplayer.app.ui.player.FeedPlayback(
            controller.seekTarget ?: ui.position, ui.duration, ui.paused, feedMuted,
            controller.ready && failed == null && !leaving,
            { doPause(!ui.paused) }, ::doSeek,
            { feedMuted = !feedMuted
                val volume = if (feedMuted) 0f else 1f
                onFeedVolume(volume)
                PlaybackService.onUserVolume()
                scope.launch { runCatching { controller.volume(volume) }.onFailure(app::report) } },
            ui.speed, ::saveSpeed, speedBusy,
        ))
    }
    LaunchedEffect(Unit) { if (feedOverlay == null) runCatching { root.requestFocus() } }
    if (sourceSwitch) target.src?.let { raw ->
        CompositionLocalProvider(LocalTvMode provides true) {
            xyz.linplayer.app.ui.pages.SourcePlayFailed(raw, ui.position, onSwitch = { next ->
                if (!leaving && !app.privateSpace.value) {
                    if (next is xyz.linplayer.app.ui.pages.SourceSwitchTarget.Line)
                        switchTo(TvRoute.Player(next.route.itemId, next.route.title, src = next.route.src))
                    else {
                        check(onSourceSwitch != null) { "当前播放入口无法跨源跳转" }
                        sourceExit = next
                    }
                }
            }, onClose = { sourceSwitch = false })
        }
    }
}

private fun skipNow(ui: PlayerUi, seek: (Double) -> Unit, playNext: () -> Unit) {
    when (ui.skipWhat) {
        "intro" -> ui.intro?.let { seek(it.endInclusive) }
        "outro" -> skipOutro(ui, seek, playNext)
    }
    ui.skipWhat = null
}

/** 片尾后面还有正片就跳过去,片尾就是结尾则直接播下一集(同手机)。 */
private fun skipOutro(ui: PlayerUi, seek: (Double) -> Unit, playNext: () -> Unit) {
    val o = ui.outro ?: return
    if (o.endInclusive < ui.duration) seek(o.endInclusive) else playNext()
}

/**
 * 详情页选过的音轨 / 字幕:轨表稳定后按 **ff_index**(= Emby 的 MediaStream.Index)对上 mpv 的轨 id。
 * `picked` 为空时**什么都不设**,交给核心层的偏好。
 */
private suspend fun applyPickedTracks(controller: PlayerController, target: TvRoute.Player, tracks: List<JsonObject>) {
    fun idOf(kind: String, index: Long?) = tracks.firstOrNull { it.str("kind") == kind && it.long("ff_index") == index }?.str("id")
    target.audioIndex?.let { idOf("audio", it) }?.let { runCatching { controller.restoreInitialTrack("audio", it) } }
    if (target.subOff) runCatching { controller.restoreInitialTrack("sub", "") }
    else target.subIndex?.let { idOf("sub", it) }?.let { runCatching { controller.restoreInitialTrack("sub", it) } }
}
