package xyz.linplayer.app.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
internal data class TrackIdentity(val title: String?, val language: String?, val ordinal: Int)

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
    var mediaSourceId: String? = null
        private set
    private var resume = 0.0

    fun bind(player: ExoPlayer?) {
        controls = if (player == null) MpvPlayerEngine(call) else Media3PlayerEngine(player)
    }

    fun resolved(result: JsonObject?) {
        mediaSourceId = result.str("media_source_id")
        resume = result.dbl("resume_secs") ?: 0.0
    }
    fun begin() { ready = false }
    fun started() { ready = true }

    suspend fun stop(position: Double) {
        ready = false
        controls.stopOutput()
        call("player.stopPlayback", args("pos" to position))
    }

    /** 离页任务立即登记，新页起播等待收尾，避免旧 stop 停掉新会话。 */
    fun stopIn(scope: CoroutineScope, position: Double, onError: (Throwable) -> Unit, afterStop: suspend () -> Unit = {}): Job {
        val previous = pendingStop
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            previous?.join()
            try {
                stop(position)
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

    suspend fun seek(position: Double) { if (!switching) controls.seek(position) }
    suspend fun pause(paused: Boolean) { if (!switching) controls.pause(paused) }
    suspend fun speed(speed: Double) { if (!switching) controls.speed(speed) }
    suspend fun volume(volume: Float) { if (!switching) controls.volume(volume) }

    fun snapshot(player: ExoPlayer, subOff: Boolean): PlaybackSnapshot {
        fun selected(type: Int): TrackIdentity? {
            var ordinal = 0
            for (group in player.currentTracks.groups.filter { it.type == type }) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    if (group.isTrackSelected(i)) return TrackIdentity(format.label, format.language, ordinal)
                    ordinal++
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
        controls.speed(state.speed)
        controls.volume(state.volume)
        controls.pause(state.paused)
        if (state.subOff) call("player.setTrack", args("kind" to "sub", "id" to ""))
    }

    /** 外挂轨可能晚到；每条只在匹配成功后消费，不覆盖之后的手动选轨。 */
    suspend fun restoreTracks(tracks: List<JsonObject>) {
        suspend fun restore(kind: String, choice: TrackIdentity?): Boolean {
            if (choice == null) return false
            val candidates = tracks.filter { it.str("kind") == kind }
            val id = matchingTrack(choice, candidates)?.str("id") ?: return false
            call("player.setTrack", args("kind" to kind, "id" to id))
            return true
        }
        if (restore("audio", pendingAudio)) pendingAudio = null
        if (fallback?.subOff != true && restore("sub", pendingSubtitle)) pendingSubtitle = null
    }

    fun trackPicked(kind: String) {
        if (kind == "audio") pendingAudio = null
        if (kind == "sub" || kind == "subtitle") pendingSubtitle = null
    }
}

internal fun matchingTrack(choice: TrackIdentity, tracks: List<JsonObject>): JsonObject? {
    val title = choice.title?.takeIf { it.isNotBlank() }
    fun languageOf(value: String?): String? {
        val language = value?.lowercase()?.takeIf { it.isNotBlank() && it != "und" } ?: return null
        val base = language.substringBefore('-')
        val iso3 = if (base.length == 2) runCatching { java.util.Locale.forLanguageTag(base).isO3Language }.getOrDefault(base) else base
        return iso3 + language.removePrefix(base)
    }
    val language = languageOf(choice.language)
    val matches = tracks.filter {
        (title == null || it.str("title") == title) && (language == null || languageOf(it.str("lang")) == language)
    }
    if ((title != null || language != null) && matches.size == 1) return matches.single()
    // 无标签或重复标签只在原同类序号仍符合身份时恢复，不随意选第一条。
    return tracks.getOrNull(choice.ordinal)?.takeIf { it in matches }
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
