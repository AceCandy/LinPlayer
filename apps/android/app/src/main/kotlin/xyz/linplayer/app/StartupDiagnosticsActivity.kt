package xyz.linplayer.app

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** 使用系统控件取启动现场,不依赖核心库或 Compose,仅诊断构建可进入。 */
class StartupDiagnosticsActivity : Activity() {
    private lateinit var details: TextView
    private lateinit var attempt: Button
    private var report = ""
    private val record get() = File(filesDir, "startup-diagnostics.txt")

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        if (!BuildConfig.STARTUP_DIAGNOSTICS) { finish(); return }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (24 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
        }
        column.addView(TextView(this).apply {
            text = "启动诊断 V3\n点击测试播放器启动。若播放器退出,返回这里复制诊断信息。不会清除应用数据。"
            textSize = 18f
        })
        column.addView(Button(this).apply {
            id = COPY_ID
            text = "复制诊断信息"
            setOnClickListener {
                refreshReport()
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("启动诊断", report))
                Toast.makeText(this@StartupDiagnosticsActivity, "已复制", Toast.LENGTH_SHORT).show()
            }
        })
        attempt = Button(this).apply {
            text = "测试播放器启动"
            setOnClickListener {
                isEnabled = false
                update("阶段:正在启动播放器主进程", save = true)
                try { startActivity(Intent(this@StartupDiagnosticsActivity, MainActivity::class.java)) }
                catch (e: Throwable) { showFailure(e) }
                isEnabled = true
            }
        }
        column.addView(attempt)
        details = TextView(this).apply { setTextIsSelectable(true); textSize = 12f }
        column.addView(details)
        setContentView(ScrollView(this).apply { addView(column) })
        refreshReport()
    }

    override fun onResume() {
        super.onResume()
        if (::details.isInitialized) refreshReport()
    }

    private fun refreshReport() {
        val previous = runCatching { record.takeIf { it.exists() }?.readText()?.take(32768) }.getOrNull()
        val crash = runCatching { File(filesDir, "last-crash.txt").takeIf { it.exists() }?.readText()?.take(32768) }.getOrNull()
        update(listOfNotNull(previous, crash, systemExits()).joinToString("\n\n").ifEmpty { "尚未尝试启动" })
    }

    internal fun showFailure(error: Throwable) {
        update("启动失败\n" + Log.getStackTraceString(error), save = true)
        attempt.isEnabled = true
    }

    private fun update(message: String, save: Boolean = false) {
        val header = "版本:${BuildConfig.VERSION_NAME}\n设备:${Build.MANUFACTURER} ${Build.MODEL}\nAndroid:${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nABI:${Build.SUPPORTED_ABIS.joinToString()}\n"
        report = Telemetry.scrub(header + message, applicationInfo.dataDir, filesDir.absolutePath).orEmpty()
        details.text = report
        if (save) runCatching { record.writeText(report) }
    }

    /** 系统记录不依赖原生库;只读退出描述,不把二进制 tombstone 当文本展示。 */
    private fun systemExits(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, 3)
                .joinToString("\n") { "系统退出:时间=${it.timestamp},原因=${it.reason},状态=${it.status},描述=${it.description.orEmpty()}" }
        }.getOrNull()
    }

    companion object { internal const val COPY_ID = 1001 }
}
