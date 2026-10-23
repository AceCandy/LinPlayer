package xyz.linplayer.app.ui.player.liuying

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.media3.common.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.*
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.player.FeedPlayback
import xyz.linplayer.app.ui.player.PlayerController
import java.util.UUID

internal data class LiuyingSelection(val key: String, val item: String, val generation: Int)

/** 最新目标邮箱与正式提交分离；慢停止期间只保留最新页，不为每次手势排协程。 */
internal class LiuyingPlaybackSession(
    private val app: AppState,
    val slots: LiuyingMediaSlots,
) {
    val owner = UUID.randomUUID().toString()
    private val identity = app.session.value
    private val space = app.spaceRevision.value
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val preparationSignal = Channel<Unit>(Channel.CONFLATED)
    private var desired by mutableStateOf<LiuyingSelection?>(null)
    private var selected by mutableStateOf<LiuyingSelection?>(null)
    private var playback by mutableStateOf<String?>(null)
    private var stopPosition: Double? = null
    private var playedGeneration = 1
    private var generation = 1
    private var future: List<LiuyingSelection> = emptyList()
    private var previous: List<LiuyingSelection> = emptyList()
    private var backwards = false
    private var currentMedia: LiuyingMedia? = null
    private val prepared = linkedMapOf<String, LiuyingMedia>()
    private var preparation: Job? = null
    private var opened = false
    private var retrying = false
    private var switching = false
    private var memoryLimited = false
    private var leaving = false
    private var warming: String? = null
    private var warmAllowed = false
    private var saveSpeed: Job? = null
    private var pauseWarm: Job? = null
    private var warmRevision = 0L
    private val call = app.liuyingCaller()
    var error by mutableStateOf<String?>(null)
        private set
    var position by mutableDoubleStateOf(0.0)
        private set
    var duration by mutableDoubleStateOf(0.0)
        private set
    var buffering by mutableStateOf(true)
        private set
    var speed by mutableDoubleStateOf(1.0)
        private set
    var speedBusy by mutableStateOf(false)
        private set
    var muted by mutableStateOf(false)
        private set
    var userWantsPlay by mutableStateOf(true)
        private set
    var foreground by mutableStateOf(false)
        private set
    var unlocked by mutableStateOf(false)
        private set
    private var focus = false
    private var focusBlocked = false
    private var dragging = false
    var onNeedFocus: () -> Unit = {}
    var onEnded: () -> Unit = {}
    var onPlayingChanged: (Boolean) -> Unit = {}
    val startedKey: String? get() = selected?.takeIf { it == desired && playback != null }?.key

    private fun neighbours() = (if (backwards) previous + future else future + previous).distinctBy { it.key }
    private suspend fun prunePrepared() {
        val keep = (neighbours().map { it.key } + listOfNotNull(desired?.key)).toSet()
        for (key in prepared.keys.toList()) if (key !in keep) {
            val removed = prepared.remove(key) ?: continue
            slots.discard(removed.request)
            command("Cancel", args("request_id" to removed.request))
        }
        slots.retain(prepared.values.map { it.request }.toSet())
    }

    private fun valid() = !leaving && app.session.value == identity &&
        app.spaceRevision.value == space && app.privateSpace.value
    private fun parameters(gen: Int = generation) = args("owner_id" to owner, "generation" to gen)
    private suspend fun command(name: String, extra: JsonObject = args(), gen: Int = generation) =
        call("player.liuying$name", JsonObject(parameters(gen) + extra))

    private val worker = app.bg.launch(start = CoroutineStart.UNDISPATCHED) {
        PlayerController.awaitPendingStop()
        for (ignored in signal) {
            try {
                if (!opened) {
                    val prefs = app.call("player.getPlaybackPrefs").obj()
                    speed = (prefs.dbl("liuying_speed") ?: 1.0).coerceIn(.25, 4.0)
                    if (!valid()) break
                    command("Open", gen = 1)
                    opened = true
                    preparationSignal.trySend(Unit)
                }
                while (valid()) {
                    val target = desired
                    if (target == null) {
                        switching = true
                        preparation?.cancelAndJoin()
                        prunePrepared()
                        stopCurrent()
                        break
                    }
                    if (target == selected && playback != null && !retrying) break
                    if (!foreground || !unlocked) break
                    retrying = false
                    switching = true
                    saveSpeed?.join()
                    preparation?.cancelAndJoin()
                    prunePrepared()
                    stopCurrent()
                    // 保存和停止可能挂起，提交新播放前重新核验前台与解锁状态。
                    if (!valid() || !foreground || !unlocked) break
                    if (desired != target) continue
                    generation = target.generation
                    val cached = prepared[target.key]
                    val media = cached?.takeIf { SystemClock.elapsedRealtime() - it.createdAt < 110_000 }
                    if (cached != null && media == null) {
                        prepared.remove(target.key); slots.discard(cached.request)
                        command("Cancel", args("request_id" to cached.request))
                    }
                    if (!valid() || !foreground || !unlocked) break
                    if (desired != target) continue
                    val result = command("Play", args("item_id" to target.item, "request_id" to media?.request.orEmpty())).obj()
                        ?: error("流影未返回播放结果")
                    playback = result.str("playback_id")?.takeIf { it.isNotEmpty() } ?: error("流影未返回会话标识")
                    stopPosition = null
                    selected = target; playedGeneration = target.generation
                    if (!valid() || desired != target) continue
                    val finalMedia = LiuyingMedia(
                        result.str("consumed_request_id")?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString(),
                        target.item, result.str("play_url") ?: error("没有可播放地址"),
                        result.str("media_source_id").orEmpty(), result["external_subs"], true, SystemClock.elapsedRealtime(),
                    )
                    val reusable = media?.takeIf { it.request == finalMedia.request && it.url == finalMedia.url &&
                        it.source == finalMedia.source && it.subtitles == finalMedia.subtitles }
                    currentMedia = reusable ?: finalMedia
                    slots.promote(currentMedia!!, speed.toFloat(), muted)
                    prepared.remove(target.key)
                    position = 0.0; duration = 0.0; error = null
                    applyGate()
                    switching = false
                    preparationSignal.trySend(Unit)
                    break
                }
            } catch (cancelled: CancellationException) {
                if (valid()) error = "流影请求已失效，请重试"
                else break
            } catch (failure: Exception) {
                if (valid()) error = failure.message ?: "流影播放失败"
            } finally {
                switching = false
            }
        }
    }
    private val preparationWorker = app.bg.launch {
        for (ignored in preparationSignal) {
            if (!valid() || !opened) continue
            preparation?.join()
            preparation = launch {
                if (switching || selected != desired || playback == null) return@launch
                val wanted = neighbours()
                val gen = generation
                prunePrepared()
                if (!foreground || !unlocked) return@launch
                for ((index, target) in wanted.withIndex()) {
                    ensureActive()
                    if (target.generation != gen) continue
                    val existing = prepared[target.key]
                    if (existing != null && SystemClock.elapsedRealtime() - existing.createdAt < 110_000) {
                        slots.prepare(existing, index + 1); continue
                    }
                    if (existing != null) {
                        prepared.remove(target.key); slots.discard(existing.request)
                        command("Cancel", args("request_id" to existing.request), gen)
                    }
                    val request = UUID.randomUUID().toString()
                    var adopted = false
                    try {
                        val value = command("Prepare", args("request_id" to request, "item_id" to target.item), gen).obj()
                        ensureActive()
                        if (!valid() || generation != gen || value.bool("skipped")) continue
                        val media = LiuyingMedia(request, target.item, value.str("play_url") ?: continue,
                            value.str("media_source_id").orEmpty(), value?.get("external_subs"),
                            value.bool("can_preload"), SystemClock.elapsedRealtime())
                        prepared[target.key] = media
                        slots.prepare(media, index + 1); adopted = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* 准备失败只降低命中率，不打断当前片。 */ }
                    finally {
                        if (!adopted) withContext(NonCancellable) {
                            runCatching { command("Cancel", args("request_id" to request), gen) }
                        }
                    }
                }
            }
            preparation?.join()
        }
    }

    init {
        slots.onSurfaceChanged = ::applyGate
        slots.onError = { if (valid()) { error = it.message ?: "Media3 无法播放此视频"; applyGate() } }
        slots.onEnded = { if (valid() && selected == desired) onEnded() }
    }

    fun select(target: LiuyingSelection?, next: List<LiuyingSelection>, before: List<LiuyingSelection> = emptyList()) {
        if (!valid()) return
        if (desired == target && future == next.take(if (memoryLimited) 0 else 3) &&
            previous == before.take(if (memoryLimited) 0 else 2)) return
        val changed = desired != target
        val rangeChanged = target != null && target.generation != generation
        if (changed) backwards = previous.any { it == target }
        desired = target
        future = if (memoryLimited) emptyList() else next.take(3)
        previous = if (memoryLimited) emptyList() else before.take(2)
        if (selected != target) {
            slots.silence()
            error = null; buffering = true; position = 0.0; duration = 0.0
            if (changed && target != null) {
                userWantsPlay = true
                focusBlocked = false
                if (foreground && unlocked) onNeedFocus()
            }
        }
        if (rangeChanged) {
            generation = target.generation
            prepared.values.forEach { slots.discard(it.request) }
            prepared.clear()
        }
        preparation?.cancel()
        warmRevision++
        slots.warm(null, false)
        signal.trySend(Unit); preparationSignal.trySend(Unit)
    }

    private suspend fun stopCurrent() {
        val id = playback ?: return
        val old = selected ?: return
        slots.silence()
        val pos = stopPosition ?: (slots.active?.player?.currentPosition?.div(1000.0) ?: 0.0).coerceAtLeast(0.0)
        stopPosition = pos
        val media = currentMedia?.takeIf {
            valid() && !memoryLimited && old.generation == generation && old in neighbours() &&
                SystemClock.elapsedRealtime() - it.createdAt < 110_000
        }
        slots.detachCurrent(retain = media != null)
        val result = command("Stop", args("playback_id" to id, "item_id" to old.item, "pos" to pos,
            "handoff" to true, "retain_request_id" to media?.request.orEmpty()), playedGeneration).obj()
        if (media != null) {
            if (result.bool("retained") && valid() && old.generation == generation && old in neighbours()) {
                prepared[old.key] = media
            } else {
                slots.discard(media.request)
                if (result.bool("retained")) command("Cancel", args("request_id" to media.request), playedGeneration)
            }
        }
        playback = null; selected = null; stopPosition = null; currentMedia = null
    }

    fun environment(resumed: Boolean, screenUnlocked: Boolean) {
        if (foreground == resumed && unlocked == screenUnlocked) return
        foreground = resumed; unlocked = screenUnlocked
        warmRevision++
        if (!resumed || !screenUnlocked) {
            preparation?.cancel(); slots.releaseSpare()
            slots.warm(null, false)
            val old = warming
            warming = null
            if (old != null && pauseWarm?.isActive != true) pauseWarm = app.bg.launch {
                runCatching { command("Warm", args("request_id" to old, "enabled" to false, "depth" to 1)) }
            }
        } else {
            if (userWantsPlay && !focusBlocked) onNeedFocus()
            signal.trySend(Unit)
            preparationSignal.trySend(Unit)
        }
        applyGate()
    }
    fun focusChanged(granted: Boolean, permanent: Boolean = false) {
        focus = granted
        warmRevision++
        if (!granted) slots.warm(null, false)
        if (permanent) { focusBlocked = true; userWantsPlay = false }
        applyGate()
    }
    fun drag(active: Boolean) {
        dragging = active; warmRevision++
        if (active) slots.warm(null, false)
        applyGate()
    }
    private fun applyGate() {
        val play = valid() && foreground && unlocked && focus && !focusBlocked &&
            userWantsPlay && !dragging && error == null && selected == desired && playback != null &&
            slots.active?.surface != null
        slots.gate(play, muted, foreground && unlocked && valid())
        onPlayingChanged(play)
    }
    fun togglePause() {
        userWantsPlay = !userWantsPlay
        if (userWantsPlay) { focusBlocked = false; onNeedFocus() }
        applyGate()
    }
    fun toggleMute() { muted = !muted; applyGate() }
    fun seek(seconds: Double) {
        val player = slots.active?.player ?: return
        if (playback != null && seconds.isFinite() && player.isCurrentMediaItemSeekable && duration > 0)
            player.seekTo((seconds.coerceIn(0.0, duration) * 1000).toLong())
    }
    fun loop() {
        val player = slots.active?.player
        if (selected != desired || playback == null || error != null || player?.isCurrentMediaItemSeekable != true) {
            app.toast("当前视频不支持循环定位"); return
        }
        player.seekTo(0); applyGate()
    }
    fun retry() { if (valid()) { retrying = true; error = null; signal.trySend(Unit) } }
    fun trimMemory() {
        memoryLimited = true; future = emptyList(); previous = emptyList()
        warmRevision++
        slots.warm(null, false)
        preparation?.cancel(); slots.releaseSpare()
        preparationSignal.trySend(Unit)
    }
    fun changeSpeed(value: Double) {
        if (speedBusy || !value.isFinite() || value !in .25..4.0 || !valid()) return
        val old = speed; val target = selected
        speed = value; speedBusy = true
        slots.active?.player?.setPlaybackSpeed(value.toFloat())
        saveSpeed = app.bg.launch {
            try { app.call("player.setPlaybackPrefs", args("liuying_speed" to value)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (valid()) {
                    speed = old
                    if (selected == target) slots.active?.player?.setPlaybackSpeed(old.toFloat())
                    app.report(failure)
                }
            } finally { if (valid()) speedBusy = false }
        }
    }

    /** 只采样本地进度与调度准备；流影不发送周期HTTP进度。 */
    suspend fun tick() {
        if (!valid()) return
        val player = slots.active?.player
        if (foreground && unlocked && selected == desired && playback != null && player != null) {
            position = (player.currentPosition / 1000.0).coerceAtLeast(0.0)
            duration = (player.duration / 1000.0).coerceAtLeast(0.0)
            buffering = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
            val buffered = ((player.bufferedPosition - player.currentPosition) / 1000.0 / speed).coerceAtLeast(0.0)
            if (buffering || buffered < 1) warmAllowed = false
            if (!buffering && (buffered >= 3 || duration > 0 && player.bufferedPosition >= player.duration)) warmAllowed = true
        } else warmAllowed = false
        val revision = warmRevision
        val gen = generation
        val request = if (foreground && unlocked && focus && !dragging && warmAllowed && !memoryLimited)
            slots.nextWarmRequest() else null
        if (warming != request) {
            slots.warm(null, false)
            warming?.let { old -> runCatching { command("Warm", args("request_id" to old, "enabled" to false, "depth" to 1), gen) } }
            warming = null
            if (request != null && valid() && revision == warmRevision) {
                val rank = neighbours().indexOfFirst { prepared[it.key]?.request == request } + 1
                val depth = when (rank) { 1 -> 5; 2 -> 3; else -> 1 }
                val matched = runCatching { command("Warm", args("request_id" to request, "enabled" to true, "depth" to depth), gen).obj().bool("matched") }.getOrDefault(false)
                if (matched && valid() && revision == warmRevision) warming = request
                else if (matched) runCatching {
                    command("Warm", args("request_id" to request, "enabled" to false, "depth" to depth), gen)
                }
            }
        }
        if (valid() && revision == warmRevision) slots.warm(warming, warming != null)
        else slots.warm(null, false)
    }

    fun controls() = FeedPlayback(position, duration, !userWantsPlay, muted,
        selected == desired && playback != null && error == null,
        ::togglePause, ::seek, ::toggleMute, speed, ::changeSpeed, speedBusy)

    fun slotFor(key: String): LiuyingMediaSlots.Slot? {
        if (selected?.key == key && playback != null) return slots.active
        val request = prepared[key]?.request ?: return null
        return slots.spare?.takeIf { it.entry?.media?.request == request }
    }

    fun close() {
        if (leaving) return
        leaving = true; signal.close(); preparationSignal.close()
        warmRevision++
        slots.silence(); onPlayingChanged(false)
        val lastPlayback = playback
        val lastPosition = stopPosition ?: (slots.active?.player?.currentPosition?.div(1000.0) ?: 0.0).coerceAtLeast(0.0)
        preparation?.cancel(); preparationWorker.cancel()
        slots.release()
        PlayerController.handoffIn(app.bg) {
            worker.join()
            saveSpeed?.join()
            val id = playback
            val old = selected
            if (id != null && old != null) runCatching {
                command("Stop", args("playback_id" to id, "item_id" to old.item,
                    "pos" to if (id == lastPlayback) lastPosition else 0.0, "handoff" to true), playedGeneration)
            }
            runCatching { command("Cancel") }
            if (playedGeneration != generation) runCatching { command("Cancel", gen = playedGeneration) }
        }
    }
}
