package xyz.linplayer.app.ui.player

/** 流影只替换播放控件；控制动作仍由各端播放器的现有控制器执行。 */
data class FeedPlayback(
    val position: Double,
    val duration: Double,
    val paused: Boolean,
    val muted: Boolean,
    val ready: Boolean,
    val togglePause: () -> Unit,
    val seek: (Double) -> Unit,
    val toggleMute: () -> Unit,
    val speed: Double = 1.0,
    val setSpeed: (Double) -> Unit = {},
    val speedBusy: Boolean = false,
)
