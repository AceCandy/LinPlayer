package xyz.linplayer.app.tv

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.PageCache

/** 人工视觉验收:只读本地图片,未提供素材时跳过,不让日常测试依赖外网。 */
@OptIn(coil3.annotation.DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvHomeDesignShot {
    @get:Rule val rule = createComposeRule()

    @Test fun homeWithArtwork() {
        val path = System.getenv("LP_TV_ART_DIR")
        assumeTrue("视觉素材由本地验收提供", !path.isNullOrBlank())
        val dir = File(requireNotNull(path))
        val ids = listOf("238", "603", "496243", "157336", "324786")
        val names = listOf("教父", "黑客帝国", "寄生虫", "星际穿越", "血战钢锯岭")
        val images = (ids + "backdrop").associateWith { id ->
            requireNotNull(BitmapFactory.decodeFile(File(dir, "$id.jpg").path)) { "缺少视觉素材: $id" }
        }
        val loader = ImageLoader.Builder(ApplicationProvider.getApplicationContext()).components {
            add(Fetcher.Factory<coil3.Uri> { data, _, _ ->
                Fetcher {
                    val url = Uri.decode(data.toString())
                    val id = ids.firstOrNull { url.contains("/$it/") } ?: "238"
                    val bitmap = images.getValue(if (url.contains("Backdrop") && id == "238") "backdrop" else id)
                    ImageFetchResult(bitmap.asImage(), false, DataSource.MEMORY)
                }
            })
        }.coroutineContext(Dispatchers.Unconfined).build()
        SingletonImageLoader.setUnsafe(loader)
        val core = FakeCore().loggedIn().apply {
            ret("emby.listRandom", arr(item("238", "教父", year = 1972, rating = 9.2, genres = listOf("剧情", "犯罪"), runtime = 10500.0)))
            ret("emby.listResume", arr(*ids.mapIndexed { i, id -> item(id, names[i], runtime = 9000.0, resume = 1200.0 + i * 900) }.toTypedArray()))
            ret("emby.listNextUp", arr())
            ret("emby.listLatest", arr(*ids.mapIndexed { i, id -> item(id, names[i]) }.toTypedArray()))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val app = AppState(core, scope)
        try {
            PageCache.clear()
            runBlocking { app.boot() }
            rule.mainClock.autoAdvance = false
            rule.setContent { TvFrame(app) { Shell(TvRoute.Home) } }
            advance(rule, 1600)
            rule.onRoot().captureRoboImage("build/tvpages/home-artwork.png")
            press(rule, androidx.compose.ui.input.key.Key.DirectionUp, androidx.compose.ui.input.key.Key.DirectionLeft)
            rule.onRoot().captureRoboImage("build/tvpages/home-artwork-navigation.png")
        } finally {
            scope.cancel()
            app.bg.cancel()
            PageCache.clear()
        }
    }
}
