package xyz.linplayer.app.data

import android.net.Uri
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.ConnectException

/** 当前账号已知作品的本地缓存图片，不持久化地址或凭据。 */
@Immutable
internal data class CachedHomeImage(val item: Item, val url: String, val backdrop: Boolean)

/** 仅访问核心回环缓存路由；没有缓存时跳过，不允许重定向或回源。 */
internal suspend fun AppState.cachedHomeImages(
    items: List<Item>, onBatch: suspend (List<CachedHomeImage>) -> Unit = {},
): List<CachedHomeImage> {
    val identity = session.value ?: return emptyList()
    val token = core.localToken
    val candidates = homeHeroCandidates(items)
    val base = core.localBaseUrl
    return withContext(Dispatchers.IO) {
        val found = mutableListOf<CachedHomeImage>()
        val probes = Semaphore(4)
        val batches = listOf(candidates.take(4)) + candidates.drop(4).chunked(24)
        for (batch in batches) {
            val hits = try { coroutineScope { batch.map { item -> async { probes.withPermit {
                currentCoroutineContext().ensureActive()
                if (session.value?.let { it.server to it.userId } != (identity.server to identity.userId))
                    throw kotlinx.coroutines.CancellationException("轮播会话已切换")
                for (kind in listOf("Backdrop", "Primary")) {
                    val segment = if (kind == "Backdrop") "Backdrop/0" else kind
                    val upstream = "${identity.server.trimEnd('/')}/Items/${item.id}/Images/$segment?quality=90"
                    for (height in listOf(720, 1080, 480, 330, 220, 120)) {
                        val address = "$base/img-cache?src=${Uri.encode(upstream)}&h=$height"
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
                        } catch (e: ConnectException) { throw e }
                          catch (_: IOException) { return@withPermit null }
                          finally { connection.disconnect() }
                        currentCoroutineContext().ensureActive()
                        if (hit) {
                            return@withPermit CachedHomeImage(item, address, kind == "Backdrop")
                        }
                    }
                }
                null
            } } }.awaitAll().filterNotNull() } } catch (_: ConnectException) { break }
            found.addAll(hits)
            currentCoroutineContext().ensureActive()
            if (hits.isNotEmpty()) onBatch(found.toList())
        }
        found
    }
}

/** 分集观看记录同时排除所属剧，避免换成同剧海报后又进入轮播。 */
internal fun homeHeroCandidates(items: List<Item>): List<Item> {
    val unique = items.distinctBy { it.id }
    val excluded = unique.filter { it.played || it.resumeSecs > 0 }.flatMap {
        listOfNotNull(it.id, it.seriesId)
    }.toSet()
    val series = unique.filter { it.isSeries }.map { it.id }.toSet()
    return unique.filter { it.type in setOf("Movie", "Series", "Episode", "Season") &&
        it.id !in excluded && it.seriesId !in excluded && it.seriesId !in series }
        .distinctBy { it.seriesId ?: it.id }
}
