package xyz.linplayer.app.data

import android.net.Uri
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 当前账号已知作品的本地缓存图片，不保存地址或凭据。 */
@Immutable
internal data class CachedHomeImage(val item: Item, val url: String, val backdrop: Boolean)

/** 仅访问核心回环缓存路由；没有缓存时跳过，不允许重定向或回源。 */
internal suspend fun AppState.cachedHomeImages(items: List<Item>): List<CachedHomeImage> {
    val identity = session.value ?: return emptyList()
    val token = core.localToken
    val candidates = items.filter { it.type in setOf("Movie", "Series", "Episode", "Season") }
        .distinctBy { it.id }.shuffled().take(24)
    // URL在切线程前用同一会话生成，切账号会取消页面任务并拒绝迟到结果。
    val urls = candidates.associateWith { item ->
        listOf("Backdrop", "Primary").associateWith { kind ->
            listOf(720, 1080, 480, 330, 220, 120).mapNotNull { height ->
                imageUrl(item.id, kind, height)?.replaceFirst("/img?", "/img-cache?")
            }
        }
    }
    return withContext(Dispatchers.IO) {
        val found = mutableListOf<CachedHomeImage>()
        for (kind in listOf("Backdrop", "Primary")) {
            for (item in candidates) {
                currentCoroutineContext().ensureActive()
                if (session.value?.let { it.server to it.userId } != (identity.server to identity.userId))
                    return@withContext emptyList()
                if (found.any { it.item.id == item.id }) continue
                for (address in urls[item]?.get(kind).orEmpty()) {
                    currentCoroutineContext().ensureActive()
                    // 先校验地址再创建连接，非核心通道不能接收凭据。
                    val uri = Uri.parse(address)
                    if (uri.scheme != "http" || uri.host != "127.0.0.1" || uri.path != "/img-cache") continue
                    val url = URL(address)
                    val connection = url.openConnection() as HttpURLConnection
                    val hit = try {
                        connection.requestMethod = "HEAD"
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 1000
                        connection.readTimeout = 1000
                        connection.setRequestProperty("X-LP-Token", token)
                        connection.responseCode == HttpURLConnection.HTTP_OK &&
                            connection.getHeaderField("X-LP-Cache") == "hit"
                    } catch (_: IOException) { return@withContext found }
                    finally { connection.disconnect() }
                    currentCoroutineContext().ensureActive()
                    if (hit) {
                        found.add(CachedHomeImage(item, address, kind == "Backdrop"))
                        break
                    }
                }
                if (found.size == 6) return@withContext found
            }
        }
        found
    }
}
