package xyz.linplayer.app.data

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** 只存详情展示资料；每次进入仍取服务端状态，不缓存进度、播放版本或凭据。 */
class DetailCache(private val directory: File? = null) {
    private val memory = LinkedHashMap<String, JsonObject>(64, .75f, true)
    private val disk = Mutex()
    @Volatile var generation: Long = 0
        private set

    fun key(server: String, userId: String, itemId: String): String {
        val identity = JsonArray(listOf(server, userId, itemId).map(::JsonPrimitive)).toString()
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    @Synchronized fun peek(key: String): JsonObject? = memory[key]

    @Synchronized private fun remember(key: String, value: JsonObject, epoch: Long) {
        if (epoch != generation) return
        memory[key] = value
        while (memory.size > MAX_ENTRIES) memory.remove(memory.keys.first())
    }

    suspend fun load(key: String, epoch: Long): JsonObject? = withContext(Dispatchers.IO) {
        disk.withLock {
            if (epoch != generation) return@withLock null
            val file = directory?.let { File(it, key) }
            val value = peek(key) ?: try {
                if (file == null) null else AtomicFile(file).openRead().use { input ->
                    if (file.length() > MAX_BYTES) null else {
                        val raw = Json.parseToJsonElement(input.reader().readText()).jsonObject
                        if (raw.str("id").isNullOrBlank() || raw.str("name") == null) null else metadata(raw)
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

    suspend fun put(key: String, value: JsonObject, epoch: Long) {
        currentCoroutineContext().ensureActive()
        val safe = metadata(value)
        val bytes = safe.toString().toByteArray()
        if (bytes.size > MAX_BYTES || epoch != generation) return
        remember(key, safe, epoch)
        withContext(Dispatchers.IO) {
            disk.withLock {
                val dir = directory ?: return@withLock
                if (epoch != generation) return@withLock
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
                    // 可再获取的展示缓存写盘失败不影响详情及内存命中。
                }
            }
        }
    }

    suspend fun remove(key: String, epoch: Long) = withContext(Dispatchers.IO) {
        disk.withLock {
            if (epoch == generation) {
                synchronized(this@DetailCache) { memory.remove(key) }
                directory?.let { AtomicFile(File(it, key)).delete() }
            }
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            disk.withLock {
                synchronized(this@DetailCache) { generation++; memory.clear() }
                directory?.listFiles()?.forEach {
                    if (!it.delete() && it.exists()) throw IOException("详情缓存清理失败")
                }
            }
        }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        disk.withLock { directory?.listFiles().orEmpty().sumOf { it.length() } }
    }

    companion object {
        private const val MAX_ENTRIES = 64
        private const val MAX_BYTES = 128 * 1024
        private val fields = setOf("id", "name", "type_", "overview", "year", "premiere_date",
            "rating", "runtime_secs", "has_primary", "has_backdrop", "series_name", "series_id",
            "season_id", "season_no", "episode_no", "official_rating", "status", "tagline", "child_count")

        /** 白名单逐层截取，新增响应字段不能隐式落盘。 */
        private fun metadata(value: JsonObject): JsonObject = buildJsonObject {
            fields.forEach { key -> (value[key] as? JsonPrimitive)?.let { put(key, it) } }
            listOf("genres", "tags").forEach { key ->
                put(key, JsonArray(value[key].arr().filterIsInstance<JsonPrimitive>()))
            }
            listOf("people", "studios").forEach { key ->
                put(key, JsonArray(value[key].arr().mapNotNull { element ->
                    element.obj()?.let { entry -> JsonObject(entry.filter { (field, content) ->
                        field in (if (key == "people") setOf("id", "name", "role") else setOf("id", "name")) &&
                            content is JsonPrimitive
                    }) }
                }))
            }
        }
    }
}
