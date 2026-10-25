package xyz.linplayer.app.data

import kotlinx.serialization.json.*
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

/** 当前账号轮播候选及缓存预览地址，不持久化地址或凭据。 */
@Immutable
internal data class CachedHomeImage(val item: Item, val url: String, val backdrop: Boolean)

/** 候选扫描只读缓存；缺图候选由当前可见轮播按需获取原图。 */
internal suspend fun AppState.cachedHomeImages(
    items: List<Item>, onBatch: suspend (List<CachedHomeImage>) -> Unit = {},
): List<CachedHomeImage> {
    val identity = session.value ?: return emptyList()
    val candidates = homeHeroCandidates(items)
    return withContext(Dispatchers.IO) {
        val found = mutableListOf<CachedHomeImage>()
        val probes = Semaphore(4)
        val batches = listOf(candidates.take(4)) + candidates.drop(4).chunked(24)
        for (batch in batches) {
            val hits = try { coroutineScope { batch.map { item -> async { probes.withPermit {
                currentCoroutineContext().ensureActive()
                if (session.value?.let { it.server to it.userId } != (identity.server to identity.userId))
                    throw kotlinx.coroutines.CancellationException("轮播会话已切换")
                val kind = when {
                    item.hasBackdrop -> "Backdrop"
                    item.hasPrimary != false -> "Primary"
                    else -> return@withPermit null
                }
                cachedHeroImage(item) ?: CachedHomeImage(item,
                    imageUrl(item.id, kind, original = true, server = identity.server)
                        ?.replaceFirst("/img?", "/img-cache?") ?: return@withPermit null, kind == "Backdrop")
            } } }.awaitAll().filterNotNull() } } catch (_: ConnectException) { break }
            found.addAll(hits)
            currentCoroutineContext().ensureActive()
            if (hits.isNotEmpty()) onBatch(found.toList())
        }
        found
    }
}

/** 原图缓存优先，其次兼容已有缩略图；探测绝不回源。 */
internal suspend fun AppState.cachedHeroImage(item: Item): CachedHomeImage? = withContext(Dispatchers.IO) {
    val identity = session.value ?: return@withContext null
    val token = core.localToken
    for (kind in listOf("Backdrop", "Primary")) {
        if (kind == "Backdrop" && !item.hasBackdrop || kind == "Primary" && item.hasPrimary == false) continue
        val addresses = listOfNotNull(imageUrl(item.id, kind, server = identity.server, original = true)) +
            listOf(1080, 720, 480, 330, 220, 120).mapNotNull { imageUrl(item.id, kind, it, identity.server) }
        for (source in addresses) {
            currentCoroutineContext().ensureActive()
            val address = source.replaceFirst("/img?", "/img-cache?")
            val uri = Uri.parse(address)
            if (uri.scheme != "http" || uri.host != "127.0.0.1" || uri.path != "/img-cache") continue
            val connection = URL(address).openConnection() as HttpURLConnection
            val hit = try {
                connection.requestMethod = "HEAD"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 1000
                connection.readTimeout = 1000
                connection.setRequestProperty("X-LP-Token", token)
                connection.responseCode == HttpURLConnection.HTTP_OK && connection.getHeaderField("X-LP-Cache") == "hit"
            } catch (e: ConnectException) { throw e }
              catch (_: IOException) { return@withContext null }
              finally { connection.disconnect() }
            currentCoroutineContext().ensureActive()
            if (session.value?.let { it.server to it.userId } != (identity.server to identity.userId))
                throw kotlinx.coroutines.CancellationException("轮播会话已切换")
            if (hit) return@withContext CachedHomeImage(item, address, kind == "Backdrop")
        }
    }
    null
}

/** 分集观看记录同时排除所属剧，避免换成同剧海报后又进入轮播。 */
internal suspend fun AppState.homeHeroCandidates(items: List<Item>): List<Item> {
    // 切到插件源时旧首页可能尚未离开组合，不能再发需要 Emby 会话的查询。
    if (session.value == null) return emptyList()
    val rows = items.map { item -> buildJsonObject {
        put("id", item.id); put("type_", item.type); put("played", item.played)
        put("resume_secs", item.resumeSecs)
        item.seriesId?.let { put("series_id", it) }
    } }
    val ids = call("emby.heroCandidates", buildJsonObject { put("items", JsonArray(rows)) }).arr()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    return items.distinctBy { it.id }.filter { it.id in ids }
}

/** 预热最多占用本机图片服务的两路回源，其余留给正在显示的海报。 */
private val detailBackdropWarmups = Semaphore(2)
private val warmedDetailBackdrops = java.util.Collections.synchronizedSet(HashSet<String>())

/**
 * 让本机图片服务提前缓存详情头图，首次放大进入时背景图从本机读出，不再在转场后才换图。
 * 只发 HEAD：图片字节只落核心缓存，不回传、不解码、不占前端内存。
 */
internal suspend fun AppState.warmDetailBackdrop(item: Item) {
    // 与详情 SeriesHead 的头图地址保持一致；分集详情用的是分集图，不预热
    if (item.isEpisode || !item.hasBackdrop) return
    val address = imageUrl(item.id, "Backdrop", 720) ?: return
    if (!warmedDetailBackdrops.add(address)) return
    val answered = try {
        detailBackdropWarmups.withPermit { withContext(Dispatchers.IO) {
            val connection = try { URL(address).openConnection() as HttpURLConnection } catch (_: IOException) { return@withContext false }
            try {
                connection.requestMethod = "HEAD"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 1000
                connection.readTimeout = 15000
                connection.setRequestProperty("X-LP-Token", core.localToken)
                // 已缓存或确认无图就不再重复预热；回源失败、连不上留待下次可见再试
                connection.responseCode.let { it == HttpURLConnection.HTTP_OK || it == HttpURLConnection.HTTP_NOT_FOUND }
            } catch (_: IOException) { false }
              finally { connection.disconnect() }
        } }
    } catch (e: kotlinx.coroutines.CancellationException) { warmedDetailBackdrops.remove(address); throw e }
    if (!answered) warmedDetailBackdrops.remove(address)
}
