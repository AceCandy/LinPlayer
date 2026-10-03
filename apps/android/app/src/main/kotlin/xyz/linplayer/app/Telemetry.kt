package xyz.linplayer.app

import android.content.Context
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import java.io.StringReader
import java.io.StringWriter
import java.net.URI
import kotlinx.serialization.json.*

/**
 * 崩溃上报(Sentry)。口径与 PC 端 `Telemetry.cs` 一致:只报崩溃 + 匿名活跃人数,
 * 不采 PII、不开性能追踪;出站前遮盖最终事件中的凭据、外部主机与应用数据路径。
 */
object Telemetry {
    fun init(ctx: Context) {
        // 本地构建没注 DSN = 不启用,开发机的崩溃不灌进线上
        if (BuildConfig.SENTRY_DSN.isEmpty()) return
        SentryAndroid.init(ctx) { configure(it, BuildConfig.SENTRY_DSN, BuildConfig.VERSION_NAME, ctx.applicationInfo.dataDir, ctx.filesDir.absolutePath) }
    }

    fun configure(o: SentryOptions, dsn: String, version: String, home: String = "", dataRoot: String = "") {
        o.dsn = dsn
        // 前缀和 PC 端区分:两端进同一个项目,活跃人数徽章要一起数
        o.release = "linplayer-android@$version"
        o.isSendDefaultPii = false
        o.tracesSampleRate = null
        o.isEnableAutoSessionTracking = true
        o.beforeSend = SentryOptions.BeforeSendCallback { ev, _ ->
            // 处理最终事件的全部字段;不能完成脱敏时丢弃,不发送原始事件。
            runCatching {
                val writer = StringWriter()
                o.serializer.serialize(ev, writer)
                val clean = scrubJson(Json.parseToJsonElement(writer.toString()), home, dataRoot)
                o.serializer.deserialize(StringReader(clean.toString()), SentryEvent::class.java)
            }.getOrNull()
        }
    }

    private const val keys = "api_key|apikey|x-emby-token|x-mediabrowser-token|token|access_token|refresh_token|pw|password|passwd|sign|authorization"
    private val secret = Regex("""(?i)\b($keys)(["']?\s*[=:]\s*["']?)(?:bearer\s+)?[^&\s"'<>,;]+""")
    private val secretKey = Regex("(?i)^(?:$keys)$")
    private val jsonSecret = Regex("""(?i)("(?:$keys)"\s*:\s*")(?:[^"\\]|\\.)*(")""")
    private val host = Regex("""(?i)\b(https?|wss?)://[^/\s"'<>]+""")
    private val localAsset = Regex("""(?i)(https?://(?:localhost|127\.0\.0\.1|\[::1\])(?::[0-9]+)?/p/)[^/\s"'<>]+""")

    fun scrub(s: String?, home: String = "", dataRoot: String = ""): String? {
        var value = s ?: return null
        for ((path, replacement) in listOf(dataRoot to "<data>", home to "~"))
            if (path.length >= 4) value = value.replace(path, replacement, ignoreCase = true)
        value = jsonSecret.replace(value, "\$1<redacted>\$2")
        value = secret.replace(value, "\$1\$2<redacted>")
        value = localAsset.replace(value, "\$1<redacted>")
        return host.replace(value) { m ->
            val uri = runCatching { URI(m.value) }.getOrNull()
            if (uri?.userInfo == null && uri?.host?.lowercase() in listOf("localhost", "127.0.0.1", "[::1]", "::1")) m.value
            else m.groupValues[1] + "://<host>"
        }
    }

    private fun scrubJson(value: JsonElement, home: String, dataRoot: String): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            if (secretKey.matches(key)) JsonPrimitive("<redacted>") else scrubJson(child, home, dataRoot)
        })
        is JsonArray -> JsonArray(value.map { scrubJson(it, home, dataRoot) })
        is JsonPrimitive -> if (value.isString) JsonPrimitive(scrub(value.content, home, dataRoot)) else value
    }
}
