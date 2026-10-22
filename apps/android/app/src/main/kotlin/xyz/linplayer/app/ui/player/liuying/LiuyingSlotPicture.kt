package xyz.linplayer.app.ui.player.liuying

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import xyz.linplayer.app.ui.player.ExoSubtitles
import xyz.linplayer.app.ui.player.Libass
import xyz.linplayer.app.ui.player.VideoFit
import xyz.linplayer.app.ui.player.videoRect

/** 不绑定通用截屏/播放器全局；相邻页只显示其自己的Surface及画面。 */
@Composable
internal fun LiuyingSlotPicture(slot: LiuyingMediaSlots.Slot, active: Boolean,
    onSurfaceChanged: () -> Unit, modifier: Modifier = Modifier) {
    var video by remember(slot, slot.entry) { mutableStateOf(slot.player.videoSize) }
    DisposableEffect(slot, slot.entry) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) { video = videoSize }
        }
        slot.player.addListener(listener)
        onDispose { slot.player.removeListener(listener) }
    }
    val ratio = if (video.width > 0 && video.height > 0) video.width * video.pixelWidthHeightRatio / video.height else 0f
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val rect = videoRect(constraints.maxWidth, constraints.maxHeight, ratio, VideoFit.Source)
        val picture = with(LocalDensity.current) { Modifier.requiredSize(rect.width.toDp(), rect.height.toDp()) }
        LiuyingVideoSurface(slot, onSurfaceChanged, picture)
        if (active) key(slot, slot.entry) {
            ExoSubtitles(slot.player, picture)
            if (Libass.available) LiuyingAss(slot, video, picture)
        }
    }
}

/** 双槽仅当前画面渲染字幕；后台/备用没有字幕时钟或全局libass写入。 */
@Composable
private fun LiuyingAss(slot: LiuyingMediaSlots.Slot, video: VideoSize, modifier: Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var front by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    val buffers = remember(size) {
        if (size.width > 0 && size.height > 0) Array(2) { Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888) } else null
    }
    LaunchedEffect(buffers, slot, video) {
        val bitmaps = buffers ?: return@LaunchedEffect
        Libass.setSize(size.width, size.height, video.width, video.height)
        var force = true
        while (true) {
            val position = slot.player.currentPosition
            val back = 1 - front
            if (withContext(Dispatchers.Default) { force || Libass.changed(position) }) {
                force = false
                if (withContext(Dispatchers.Default) { Libass.render(bitmaps[back], position, true) } > 0) {
                    front = back; revision++
                }
            }
            delay(33)
        }
    }
    Canvas(modifier.onSizeChanged { size = it }) {
        @Suppress("UNUSED_EXPRESSION") revision
        buffers?.let { bitmaps -> drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmaps[front], 0f, 0f, null) } }
    }
}
