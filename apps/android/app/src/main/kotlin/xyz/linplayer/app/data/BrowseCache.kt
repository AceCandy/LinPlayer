package xyz.linplayer.app.data

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** 首页与库首批的展示快照；按身份和查询隔离，每次进入仍向服务端刷新。 */
class BrowseCache(private val directory: File? = null) {
    private val memory = LinkedHashMap<String, JsonElement>(64, .75f, true)
    private val disk = Mutex()
    @Volatile var generation: Long = 0
        private set

    fun key(server: String, userId: String, query: String): String {
        val identity = JsonArray(listOf(server, userId, query).map(::JsonPrimitive)).toString()
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    @Synchronized fun peek(key: String): JsonElement? = memory[key]

    @Synchronized private fun remember(key: String, value: JsonElement, epoch: Long) {
        if (epoch != generation) return
        memory[key] = value
        while (memory.size > MAX_ENTRIES) memory.remove(memory.keys.first())
    }

    suspend fun load(key: String, epoch: Long): JsonElement? = withContext(Dispatchers.IO) {
        disk.withLock {
            if (epoch != generation) return@withLock null
            val file = directory?.let { File(it, key) }
            val value = peek(key) ?: try {
                if (file == null) null else AtomicFile(file).openRead().use { input ->
                    if (file.length() > MAX_BYTES) null else {
                        val raw = Json.parseToJsonElement(input.reader().readText())
                        if (raw is JsonArray || raw is JsonObject && raw["items"] is JsonArray) metadata(raw) else null
                    }
                }
            } catch (_: IOException) { null }
              catch (_: IllegalArgumentException) { null }
            currentCoroutineContext().ensureActive()
            if (epoch != generation) return@withLock null
            if (value == null) file?.delete() else {
                file?.setLastModified(System.currentTimeMillis())
                // 网络可能在读盘期间已刷新内存；不能再用磁盘旧值覆盖它。
                remember(key, peek(key) ?: value, epoch)
            }
            peek(key)
        }
    }

    suspend fun put(key: String, value: JsonElement, epoch: Long) {
        currentCoroutineContext().ensureActive()
        val safe = metadata(value)
        val bytes = safe.toString().toByteArray()
        if (bytes.size > MAX_BYTES || epoch != generation) return
        remember(key, safe, epoch)
        withContext(Dispatchers.IO) {
            disk.withLock {
                val dir = directory ?: return@withLock
                if (epoch != generation || peek(key) !== safe) return@withLock
                try {
                    if (!dir.isDirectory && !dir.mkdirs()) return@withLock
                    val target = File(dir, key)
                    // 先腾出本条及原子写临时空间，磁盘上始终不超过 64 条的预算。
                    val others = dir.listFiles().orEmpty().filter { it != target }
                        .sortedBy { it.lastModified() }
                    val reserve = if (target.exists()) 2 else 1
                    others.take((others.size - MAX_ENTRIES + reserve).coerceAtLeast(0)).forEach { it.delete() }
                    if (dir.listFiles().orEmpty().count { it != target } > MAX_ENTRIES - reserve) return@withLock
                    val atomic = AtomicFile(target)
                    val stream = atomic.startWrite()
                    try {
                        stream.write(bytes)
                        atomic.finishWrite(stream)
                    } catch (e: IOException) {
                        atomic.failWrite(stream)
                        throw e
                    }
                } catch (_: IOException) {
                    // 可再获取的展示缓存写盘失败不影响页面及内存命中。
                }
            }
        }
    }

    suspend fun remove(key: String, epoch: Long) = withContext(Dispatchers.IO) {
        disk.withLock {
            if (epoch == generation) {
                synchronized(this@BrowseCache) { memory.remove(key) }
                directory?.let { AtomicFile(File(it, key)).delete() }
            }
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            disk.withLock {
                synchronized(this@BrowseCache) { generation++; memory.clear() }
                directory?.listFiles()?.forEach {
                    if (!it.delete() && it.exists()) throw IOException("浏览缓存清理失败")
                }
            }
        }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        disk.withLock { directory?.listFiles().orEmpty().sumOf { it.length() } }
    }

    companion object {
        private const val MAX_ENTRIES = 64
        private const val MAX_BYTES = 512 * 1024
        private val fields = setOf("id", "name", "type_", "is_folder", "runtime_secs", "resume_secs",
            "series_name", "series_id", "episode_no", "season_no", "played", "unplayed_item_count",
            "unplayed_count_known", "has_backdrop", "has_logo", "douban_rating", "year", "rating", "date_updated", "sort_name", "collection_type", "has_primary", "library_type")

        /** 不写入地址、会话、播放信息；用户状态只是上次服务端展示快照。 */
        private fun metadata(value: JsonElement): JsonElement = when (value) {
            is JsonArray -> JsonArray(value.mapNotNull { entry -> entry.obj()?.let { row ->
                buildJsonObject {
                    fields.forEach { field -> (row[field] as? JsonPrimitive)?.let { put(field, it) } }
                    listOf("genres", "library_ids").forEach { field ->
                        put(field, JsonArray(row[field].arr().filterIsInstance<JsonPrimitive>()))
                    }
                }
            } })
            is JsonObject -> buildJsonObject {
                put("items", metadata(JsonArray(value["items"].arr())))
                (value["total"] as? JsonPrimitive)?.let { put("total", it) }
            }
            else -> JsonArray(emptyList())
        }
    }
}

/** 读盘与网络并行；网络完成后旧缓存不可再覆盖页面。 */
suspend fun AppState.browseBlock(
    command: String, args: JsonObject? = null, onCached: (JsonElement) -> Unit,
): Block<JsonElement> = kotlinx.coroutines.coroutineScope {
    val identity = session.value
    if (identity == null) return@coroutineScope block(command, args)
    val cache = browseCache
    val key = cache.key(identity.server, identity.userId, command + args.toString())
    val epoch = cache.generation
    var completed = false
    cache.peek(key)?.let(onCached)
    val reader = launch {
        val stored = cache.load(key, epoch)
        if (!completed && stored != null && session.value == identity) onCached(stored)
    }
    val response = block(command, args)
    currentCoroutineContext().ensureActive()
    completed = true
    reader.cancel()
    if (session.value != identity) throw kotlinx.coroutines.CancellationException("浏览会话已切换")
    // 缓存写入归应用所有，展示不等待磁盘；generation阻止清理后的旧写入。
    if (response is Block.Ok) bg.launch { cache.put(key, response.value, epoch) }
    else if (response is Block.Fail && response.code in setOf("E_AUTH", "E_NOTFOUND")) cache.remove(key, epoch)
    response
}
