package xyz.linplayer.app.ui.pages

import androidx.compose.runtime.Composable
import androidx.navigation.NavController

/** 流影只通过原生入口播放入库资源，不回退上游网页。 */
@Composable
internal fun LiuyingPage(nav: NavController) =
    LiuyingContent({ nav.popBackStack() }, tv = xyz.linplayer.app.tv.LocalTvMode.current)

@Composable
internal fun LiuyingContent(onExit: () -> Unit, tv: Boolean = false) {
    NativeLiuyingContent(onExit, tv)
}
