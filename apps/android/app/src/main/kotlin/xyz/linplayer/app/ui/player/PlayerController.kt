package xyz.linplayer.app.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.boolOrNull
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.pages.args

/** 两个内核共用的基本控制；取流和会话上报仍由核心负责。 */
internal interface PlayerEngine {
    suspend fun seek(position: Double)
    suspend fun pause(paused: Boolean)
    suspend fun speed(speed: Double)
    suspend fun volume(volume: Float)
    fun stopOutput()
}

internal class Media3PlayerEngine(private val player: ExoPlayer) : PlayerEngine {
    override suspend fun seek(position: Double) = player.seekTo((position * 1000).toLong())
    override suspend fun pause(paused: Boolean) { player.playWhenReady = !paused }
    override suspend fun speed(speed: Double) = player.setPlaybackSpeed(speed.toFloat())
    override suspend fun volume(volume: Float) { player.volume = volume }
    override fun stopOutput() { if (!player.isReleased) player.stop() }
}

internal class MpvPlayerEngine(private val call: suspend (String, JsonObject) -> JsonElement) : PlayerEngine {
    override suspend fun seek(position: Double) { call("player.seek", args("pos" to position)) }
    override suspend fun pause(paused: Boolean) { call("player.setPause", args("paused" to paused)) }
    override suspend fun speed(speed: Double) { call("player.setSpeed", args("speed" to speed)) }
    override suspend fun volume(volume: Float) { call("player.setVolume", args("volume" to (volume * 100).toInt())) }
    // mpv 的停止与会话收尾由 player.stopPlayback 一起执行。
    override fun stopOutput() = Unit
}

/** 跨内核保留媒体轨道身份，不复用 Media3 或 mpv 的运行期轨道 ID。 */
internal data class TrackIdentity(
    val title: String?, val language: String?,
    // null表示内核未提供可信语义，不能当作普通/文本字幕。
    val forced: Boolean? = null, val bitmap: Boolean? = null,
)

internal data class PlaybackSnapshot(
    val position: Double, val paused: Boolean, val speed: Double, val volume: Float,
    val audio: TrackIdentity? = null, val subtitle: TrackIdentity? = null, val subOff: Boolean = false,
    val drm: Boolean = false,
)

/** 单次播放的模式固定；自动模式只在明确兼容错误时从 Media3 回退一次。 */
internal class PlayerController(
    private val mode: String,
    private val call: suspend (String, JsonObject) -> JsonElement,
) {
    var engine by mutableStateOf(if (mode == "auto" || mode == "exo") "exo" else "mpv")
        private set
    var switching by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var fallback: PlaybackSnapshot? = null
        private set
    private var fallbackUsed = false
    private var controls: PlayerEngine = MpvPlayerEngine(call)
    private var pendingAudio: TrackIdentity? = null
    private var pendingSubtitle: TrackIdentity? = null
    private var pendingSubOff = false
    private var transportGeneration = 0L
    private val manualTracks = mutableSetOf<String>()
    private val manualChoices = mutableMapOf<String, SessionTrackChoice>()
    var manualSubtitleOff by mutableStateOf<Boolean?>(null)
        private set
    private val restoredSessionTracks = mutableSetOf<String>()
    private var knownTracks = emptyList<JsonObject>()
    private var trackMemory: SessionTrackMemory? = null
    var seriesScope by mutableStateOf<SessionTrackScope?>(null)
        private set
    val hasSessionTracks: Boolean get() = fallback == null && trackMemory?.choicesFor(seriesScope)?.isNotEmpty() == true

    /** 详情请求前同步解绑旧上下文；换账号也失效旧在途手选的记忆回写。 */
    fun clearSeriesContext(memory: SessionTrackMemory) {
        if (trackMemory != null && trackMemory !== memory) {
            transportGeneration++
            manualChoices.clear()
            manualSubtitleOff = null
            restoredSessionTracks.clear()
        }
        seriesScope = null
        trackMemory = memory
    }

    /** 复用详情请求得到的真实剧ID，未知或换剧清理本次记忆。 */
    fun seriesContext(memory: SessionTrackMemory, key: SessionTrackScope?) {
        memory.enter(key)
        trackMemory = memory
        seriesScope = key
        manualChoices.forEach { (kind, choice) -> memory.record(key, kind, choice) }
    }

    fun observeTracks(tracks: List<JsonObject>) { knownTracks = tracks }

    private fun rememberTrack(kind: String, choice: SessionTrackChoice) {
        manualChoices[kind] = choice
        if (kind == "sub") manualSubtitleOff = choice.off
        trackMemory?.record(seriesScope, kind, choice)
    }

    /** 记录点击的Format，不等待可能晚到的selected回调。 */
    fun pickExoTrack(player: ExoPlayer, kind: String, id: String) {
        trackPicked(kind)
        if (!seekEnabled || switching || engine != "exo") return
        val type = if (kind == "audio") "audio" else "sub"
        val choice = if (type == "sub" && id == "lp:off") SessionTrackChoice(off = true) else {
            val gi = id.substringBefore(':').toIntOrNull() ?: return
            val ti = id.substringAfter(':').toIntOrNull() ?: return
            val group = player.currentTracks.groups.getOrNull(gi) ?: return
            val expected = if (type == "audio") C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
            if (group.type != expected || ti !in 0 until group.length) return
            SessionTrackChoice(formatIdentity(group.getTrackFormat(ti), expected))
        }
        exoPick(player, kind, id)
        rememberTrack(type, choice)
    }
    var mediaSourceId: String? = null
        private set
    private var resume = 0.0
    /** 仅供显示和相对输入累加；会话上报仍取真实内核位置。 */
    var seekTarget by mutableStateOf<Double?>(null)
        private set
    // seek、选轨和停播共用屏障，等待不可撤回命令回执后才能切换媒体。
    private val transportMutex = Mutex()
    private var seekRevision = 0L
    private var seekSubmitted = false
    private var seekAt = 0L
    private var seekEnabled = true
    var seekTiming: SeekTiming? = null

    fun bind(player: ExoPlayer?) {
        controls = if (player == null) MpvPlayerEngine(call) else Media3PlayerEngine(player)
    }

    fun resolved(result: JsonObject?) {
        mediaSourceId = result.str("media_source_id")
        resume = result.dbl("resume_secs") ?: 0.0
    }
    suspend fun begin() {
        seekTiming?.interrupt()
        ready = false
        seekEnabled = false
        clearSeek()
        transportGeneration++
        manualTracks.clear()
        restoredSessionTracks.clear()
        knownTracks = emptyList()
        transportMutex.withLock { }
    }
    fun started() { ready = true; seekEnabled = true }

    suspend fun stop(position: Double, isCurrent: () -> Boolean = { true }) {
        seekTiming?.interrupt()
        ready = false
        seekEnabled = false
        clearSeek()
        invalidateTracks()
        transportMutex.withLock {
            if (!isCurrent()) return@withLock
            controls.stopOutput()
            call("player.stopPlayback", args("pos" to position))
        }
    }

    /** 离页任务立即登记，新页起播等待收尾，避免旧 stop 停掉新会话。 */
    fun stopIn(scope: CoroutineScope, position: Double, onError: (Throwable) -> Unit, isCurrent: () -> Boolean = { true }, afterStop: suspend () -> Unit = {}): Job {
        seekTiming?.interrupt()
        seekEnabled = false
        clearSeek()
        invalidateTracks()
        val previous = pendingStop
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            previous?.join()
            try {
                stop(position, isCurrent)
                afterStop()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { onError(failure) }
        }.also { pendingStop = it }
    }

    companion object {
        // 进程只有一个核心播放目标；两个页面的离页收尾共用这一道屏障。
        private var pendingStop: Job? = null
        suspend fun awaitPendingStop() { pendingStop?.join() }
    }

    /** 请求串行并合并未提交目标；返回只代表命令已提交，不代表画面已到达。 */
    suspend fun seek(position: Double, isCurrent: () -> Boolean = { true }) {
        if (switching || !seekEnabled || !position.isFinite() || !isCurrent()) return
        val target = position.coerceAtLeast(0.0)
        val revision = ++seekRevision
        seekTiming?.request(revision, target, engine)
        seekTarget = target
        seekSubmitted = false
        seekAt = System.nanoTime() / 1_000_000
        try {
            transportMutex.withLock {
                if (revision != seekRevision) return@withLock
                if (!isCurrent()) { seekTiming?.interrupt(); clearSeek(); return@withLock }
                // 已交给核心的命令不能靠取消页面撤回；保留锁直到回执，避免旧 seek 晚于新起播。
                withContext(NonCancellable) { controls.seek(target) }
                coroutineContext.ensureActive()
                if (revision == seekRevision) {
                    if (isCurrent()) {
                        seekSubmitted = true
                        seekTiming?.submitted(revision)
                    } else { seekTiming?.interrupt(); clearSeek() }
                }
            }
        } catch (failure: Exception) {
            seekTiming?.failed(revision, failure is CancellationException)
            if (revision == seekRevision) clearSeek()
            throw failure
        }
    }

    suspend fun seekBy(delta: Double, position: Double, duration: Double) {
        expireSeek()
        val target = ((seekTarget ?: position) + delta)
            .coerceIn(0.0, if (duration.isFinite() && duration > 0) duration else Double.MAX_VALUE)
        seek(target)
    }

    fun observePosition(position: Double, buffering: Boolean, paused: Boolean? = null, source: SeekSampleSource = SeekSampleSource.PAGE) {
        expireSeek()
        seekTiming?.sample(position, buffering, paused, source)
        val target = seekTarget ?: return
        if (seekSubmitted && !buffering && kotlin.math.abs(position - target) <= 1.0) clearSeek()
    }

    fun expireSeek(now: Long = System.nanoTime() / 1_000_000) {
        seekTiming?.expire()
        if (seekTarget != null && now - seekAt >= 15_000) clearSeek()
    }

    private fun clearSeek() {
        seekRevision++
        seekTarget = null
        seekSubmitted = false
    }
    suspend fun pause(paused: Boolean) { if (!switching) controls.pause(paused) }
    suspend fun speed(speed: Double) { if (!switching) controls.speed(speed) }
    suspend fun volume(volume: Float) { if (!switching) controls.volume(volume) }

    fun snapshot(player: ExoPlayer, subOff: Boolean): PlaybackSnapshot {
        fun selected(type: Int): TrackIdentity? {
            for (group in player.currentTracks.groups.filter { it.type == type }) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    if (group.isTrackSelected(i)) return formatIdentity(format, type)
                }
            }
            return null
        }
        // 解码器尚未建立时 position 可能仍为零，不能丢掉核心已经解析出的续播位置。
        val position = player.currentPosition / 1000.0
        return PlaybackSnapshot(
            if (position > 0 || player.currentTracks.groups.isNotEmpty()) position else resume,
            !player.playWhenReady, player.playbackParameters.speed.toDouble(), player.volume,
            selected(C.TRACK_TYPE_AUDIO), selected(C.TRACK_TYPE_TEXT),
            subOff || C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes,
            player.currentMediaItem?.localConfiguration?.drmConfiguration != null || player.currentTracks.groups.any { group ->
                (0 until group.length).any { group.getTrackFormat(it).drmInitData != null }
            },
        )
    }

    suspend fun tryFallback(error: PlaybackException, snapshot: PlaybackSnapshot, beforeStop: () -> Unit = {}): Boolean {
        if (mode != "auto" || engine != "exo" || fallbackUsed || snapshot.drm || !isEngineCompatibilityError(error)) return false
        fallbackUsed = true
        switching = true
        ready = false
        try {
            beforeStop()
            stop(snapshot.position)
            fallback = snapshot
            pendingAudio = snapshot.audio
            pendingSubtitle = snapshot.subtitle
            pendingSubOff = snapshot.subOff
            engine = "mpv"
            return true
        } catch (e: CancellationException) {
            throw e
        } finally {
            switching = false
        }
    }

    /** 覆盖初次起播参数，零秒必须用 from_start 表达，不能回读旧续播记录。 */
    fun playbackArgs(initial: Map<String, Any>): JsonObject = args(*buildMap {
        putAll(initial)
        put("engine", engine)
        fallback?.let { state ->
            mediaSourceId?.let { put("media_source_id", it) }
            remove("from_start")
            put("resume_secs", state.position)
            if (state.position <= 0) put("from_start", true)
        }
    }.toList().toTypedArray())

    suspend fun restoreState() {
        val state = fallback ?: return
        val generation = transportGeneration
        controls.speed(state.speed)
        controls.volume(state.volume)
        controls.pause(state.paused)
        transportMutex.withLock {
            if (generation != transportGeneration || !pendingSubOff) return@withLock
            withContext(NonCancellable) {
                call("player.setTrack", args("kind" to "sub", "id" to ""))
                if (generation == transportGeneration) pendingSubOff = false
            }
        }
    }

    /** 外挂轨可能晚到；锁内重新读取意图，手选清理后不再提交恢复。 */
    suspend fun restoreTracks(tracks: List<JsonObject>) {
        val generation = transportGeneration
        suspend fun restore(kind: String) = transportMutex.withLock {
            if (!seekEnabled || generation != transportGeneration) return@withLock
            val choice = if (kind == "audio") pendingAudio else pendingSubtitle
            if (choice == null || (kind == "sub" && fallback?.subOff == true)) return@withLock
            val id = matchingTrack(choice, tracks.filter { it.str("kind") == kind })?.str("id") ?: return@withLock
            withContext(NonCancellable) {
                call("player.setTrack", args("kind" to kind, "id" to id))
                if (generation == transportGeneration && kind == "audio" && pendingAudio == choice) pendingAudio = null
                if (generation == transportGeneration && kind == "sub" && pendingSubtitle == choice) pendingSubtitle = null
            }
        }
        restore("audio")
        coroutineContext.ensureActive()
        restore("sub")
    }

    /** MPV 手选排在在途恢复后；排队期间离页或换片则失效，不复用新媒体的轨道 ID。 */
    suspend fun pickTrack(kind: String, id: String) {
        trackPicked(kind)
        if (!seekEnabled || switching || engine != "mpv") return
        val generation = transportGeneration
        val type = if (kind == "subtitle") "sub" else kind
        // 面板点击后立即关闭；已登记的手选不能因此取消排队，离页由代数和提交门失效。
        withContext(NonCancellable) {
            transportMutex.withLock {
                if (!seekEnabled || generation != transportGeneration) return@withLock
                val identity = knownTracks.firstOrNull { it.str("kind") == type && it.str("id") == id }?.let(::mpvIdentity)
                call("player.setTrack", args("kind" to type, "id" to id))
                if (generation == transportGeneration) rememberTrack(type, SessionTrackChoice(identity, type == "sub" && id.isEmpty()))
            }
        }
    }

    /** 详情预选也是恢复输入；晚于手选到达时不得再覆盖当前选择。 */
    suspend fun restoreInitialTrack(kind: String, id: String) {
        val generation = transportGeneration
        transportMutex.withLock {
            if (!seekEnabled || generation != transportGeneration || kind in manualTracks) return@withLock
            withContext(NonCancellable) { call("player.setTrack", args("kind" to kind, "id" to id)) }
        }
    }

    /** 起播后只恢复本次同剧手选；同片fallback及详情显式预选优先。 */
    suspend fun restoreSessionTracks(player: ExoPlayer?, tracks: List<JsonObject>, exclude: Set<String> = emptySet()): Boolean? {
        val generation = transportGeneration
        val memory = trackMemory ?: return null
        val key = seriesScope ?: return null
        var subtitleOff: Boolean? = null
        for (kind in listOf("audio", "sub")) {
            transportMutex.withLock {
                if (!ready || !seekEnabled || generation != transportGeneration || trackMemory !== memory || seriesScope != key ||
                    fallback != null || kind in manualTracks || kind in restoredSessionTracks || kind in exclude) return@withLock
                val choice = memory.choicesFor(key)[kind] ?: return@withLock
                val candidates = if (player == null) tracks.filter { it.str("kind") == kind }.map { it.str("id") to mpvIdentity(it) }
                else player.currentTracks.groups.flatMapIndexed { gi, group ->
                    val type = if (kind == "audio") C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
                    if (group.type != type) emptyList() else (0 until group.length).filter { group.isTrackSupported(it) }.map { ti ->
                        "$gi:$ti" to formatIdentity(group.getTrackFormat(ti), type)
                    }
                }
                val id = if (kind == "sub" && choice.off) { if (player == null) "" else "lp:off" }
                else choice.identity?.let { identity -> matchingIdentity(identity, candidates.map { it.second })?.let { candidates[it].first } }
                    ?: return@withLock
                withContext(NonCancellable) {
                    if (player == null) call("player.setTrack", args("kind" to kind, "id" to id)) else exoPick(player, kind, id)
                    if (generation == transportGeneration && trackMemory === memory && seriesScope == key) {
                        restoredSessionTracks += kind
                        if (kind == "sub") subtitleOff = choice.off
                    }
                }
            }
            coroutineContext.ensureActive()
        }
        return subtitleOff
    }

    fun trackPicked(kind: String) {
        if (kind == "audio") { pendingAudio = null; manualTracks += "audio" }
        if (kind == "sub" || kind == "subtitle") {
            pendingSubtitle = null; pendingSubOff = false; manualTracks += "sub"
        }
    }

    private fun invalidateTracks() {
        transportGeneration++
        pendingAudio = null
        pendingSubtitle = null
        pendingSubOff = false
    }

}

/** 即使内核暂时不发状态，也释放超时的显示目标。 */
@Composable
internal fun ObserveSeekTimeout(controller: PlayerController) {
    LaunchedEffect(controller, controller.seekTarget) {
        while (controller.seekTarget != null) {
            delay(250)
            controller.expireSeek()
        }
    }
}

/** 两内核共享身份约束；只分类已核实的字幕格式。 */
private fun formatIdentity(format: Format, type: Int): TrackIdentity = TrackIdentity(
    format.label, format.language,
    forced = if (type == C.TRACK_TYPE_TEXT && format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) true else null,
    bitmap = if (type != C.TRACK_TYPE_TEXT) null else when (format.sampleMimeType) {
        MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS -> true
        MimeTypes.TEXT_SSA, MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_VTT -> false
        else -> null
    },
)

private fun mpvIdentity(track: JsonObject): TrackIdentity = TrackIdentity(
    track.str("title"), track.str("lang"), if (track.str("kind") == "audio") null else track.boolOrNull("forced"),
    when (track.str("codec")) {
        "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle" -> true
        "ass", "subrip", "webvtt" -> false
        else -> null
    },
)

internal fun matchingTrack(choice: TrackIdentity, tracks: List<JsonObject>): JsonObject? =
    matchingIdentity(choice, tracks.map(::mpvIdentity))?.let(tracks::get)

private fun matchingIdentity(choice: TrackIdentity, tracks: List<TrackIdentity>): Int? {
    val title = choice.title?.takeIf { it.isNotBlank() }
    fun languageOf(value: String?): String? {
        val language = value?.lowercase()?.takeIf { it.isNotBlank() && it != "und" } ?: return null
        val base = language.substringBefore('-')
        val iso3 = if (base.length == 2) runCatching { java.util.Locale.forLanguageTag(base).isO3Language }.getOrDefault(base) else base
        return iso3 + language.removePrefix(base)
    }
    val language = languageOf(choice.language)
    if (title == null && language == null) return null
    val matches = tracks.indices.filter { i ->
        val track = tracks[i]
        (title == null || track.title == title) && (language == null || languageOf(track.language) == language) &&
            (choice.forced == null || track.forced == choice.forced) &&
            (choice.bitmap == null || track.bitmap == choice.bitmap)
    }
    // 已知语义要求目标也已知且一致；轨道顺序不能证明身份。
    return matches.singleOrNull()
}

internal fun isEngineCompatibilityError(error: PlaybackException): Boolean = when (error.errorCode) {
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
    else -> false
}
