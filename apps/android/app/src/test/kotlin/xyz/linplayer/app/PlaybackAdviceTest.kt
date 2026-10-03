package xyz.linplayer.app

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.linplayer.app.ui.player.playbackAdvice

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class PlaybackAdviceTest {
    @Test fun 按真实错误码补建议并保留原始原因() {
        val network = playbackAdvice(PlaybackException("连接超时", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertTrue(network.contains("连接超时") && network.contains("切换线路"))
        val decoder = playbackAdvice(PlaybackException("解码器初始化失败", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertTrue(decoder.contains("解码器初始化失败") && decoder.contains("mpv"))
        val unknown = playbackAdvice(PlaybackException("未知原因", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
        assertTrue(unknown.contains("未知原因") && !unknown.contains("重新登录"))
    }
}
