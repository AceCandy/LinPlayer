package xyz.linplayer.app

import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.SentryException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import io.sentry.*
import io.sentry.protocol.Request
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import java.io.File
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*

class TelemetryTest {
    @Test
    fun sharedCorpusAndOutgoingEnvelope() {
        val corpus = Json.parseToJsonElement(File("../../../testdata/privacy.json").readText()).jsonObject
        val home = corpus.getValue("home").jsonPrimitive.content
        val dataRoot = corpus.getValue("data_root").jsonPrimitive.content
        for (case in corpus.getValue("cases").jsonArray) {
            val c = case.jsonObject
            assertEquals(c.getValue("expected").jsonPrimitive.content, Telemetry.scrub(c.getValue("input").jsonPrimitive.content, home, dataRoot))
        }
        val sent = mutableListOf<String>()
        val options = SentryOptions()
        Telemetry.configure(options, "https://k@o0.invalid/0", "0.0.0-test", home, dataRoot)
        options.isEnableAutoSessionTracking = false
        options.setTransportFactory(ITransportFactory { o, _ -> object : ITransport {
            override fun send(envelope: SentryEnvelope, hint: Hint) {
                val bytes = ByteArrayOutputStream()
                o.serializer.serialize(envelope, bytes)
                sent.add(bytes.toString(Charsets.UTF_8.name()))
            }
            override fun flush(timeoutMillis: Long) {}
            override fun getRateLimiter(): RateLimiter? = null
            override fun close() {}
            override fun close(isRestarting: Boolean) {}
        } })
        Sentry.init(options)
        try {
            Sentry.addBreadcrumb("http://localhost:12345/p/SECRET/author/plugin/image.png")
            val event = SentryEvent().apply {
                request = Request().apply { url = "https://server.example.test/Items"; headers = mapOf("Authorization" to "Bearer SECRET") }
                setExtra("nested", mapOf("refresh_token" to "SECRET", "paths" to listOf("$dataRoot/log", "$home/video")))
                exceptions = listOf(SentryException().apply { value = "X-Emby-Token: SECRET" })
            }
            Sentry.captureEvent(event)
            Sentry.flush(1000)
            assertTrue("没有最终信封", sent.isNotEmpty())
            val payload = sent.joinToString("\n")
            assertFalse(payload.contains("SECRET"))
            assertFalse(payload.contains("server.example.test"))
            assertFalse(payload.contains(home))
            assertTrue(payload.contains("<redacted>"))
        } finally { Sentry.close() }
    }
    /** 走 configure 装上的那个 beforeSend,不是直接调纯函数 —— 漏挂时纯函数测试照样绿。 */
    @Test
    fun beforeSendStripsTokensFromExceptions() {
        val o = SentryOptions()
        Telemetry.configure(o, "https://k@o0.invalid/0", "0.0.0-test")
        val ev = SentryEvent().apply {
            exceptions = listOf(SentryException().apply { value = "GET /Items?api_key=SECRET123&x=1 失败" })
        }
        val out = o.beforeSend!!.execute(ev, Hint())!!
        assertEquals("GET /Items?api_key=<redacted>&x=1 失败", out.exceptions!![0].value)
        assertEquals("linplayer-android@0.0.0-test", o.release)
    }
}
