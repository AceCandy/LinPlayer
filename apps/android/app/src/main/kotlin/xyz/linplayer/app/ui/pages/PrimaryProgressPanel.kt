package xyz.linplayer.app.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.components.Dim2
import xyz.linplayer.app.ui.components.Hairline
import xyz.linplayer.app.ui.components.LpButton
import xyz.linplayer.app.ui.components.LpCell
import xyz.linplayer.app.ui.components.Panel
import xyz.linplayer.app.ui.theme.Sp

@Composable
fun PrimaryProgressPanel() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<JsonObject?>(null) }
    var accounts by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        busy = true
        try {
            val state = async { app.call("prefs.getPrimaryProgressServer").obj() }
            val list = async { app.call("account.listAccounts").arr().mapNotNull { it.obj() } }
            settings = state.await()
            accounts = list.await().filter { !it.str("user_id").isNullOrBlank() && it.str("source_kind").orEmpty() in listOf("", "emby") }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "主进度设置读取失败，请重试"
            app.report(e)
        } finally {
            busy = false
        }
    }
    fun select(server: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                settings = app.call("prefs.setPrimaryProgressServer", args("server_id" to server)).obj()
                error = null
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { app.report(e)
            } finally { busy = false }
        }
    }
    Column(Modifier.padding(horizontal = Sp.x16)) {
        Dim2("相同资源以主服进度为准；独有资源各服保留。主服不可用或匹配不确定时正常播放，切换主服不迁移历史。", Modifier.padding(bottom = Sp.x12))
        if (settings == null && busy) Dim2("加载中…")
        if (settings != null) {
            Panel {
                LpCell("未指定", value = if (settings.str("server").isNullOrEmpty()) "已选择" else null, arrow = false, onClick = if (busy) null else ({ select("") }))
                accounts.forEach { account ->
                    Hairline()
                    val server = account.str("server").orEmpty()
                    val selected = settings.bool("valid") && settings.str("server") == server
                    LpCell(account.str("name") ?: "Emby 账号", sub = account.str("user_name"), value = if (selected) "已选择" else null,
                        arrow = false, onClick = if (busy) null else ({ select(server) }))
                }
            }
            if (!settings.str("server").isNullOrEmpty() && !settings.bool("valid")) Dim2("主服账号已失效，请重新选择", Modifier.padding(top = Sp.x12))
            Dim2("待同步 ${settings.long("pending") ?: 0} 项 · 冲突 ${settings.long("conflicts") ?: 0} 项", Modifier.padding(vertical = Sp.x12))
            settings.str("error")?.takeIf { it.isNotBlank() }?.let { Dim2(it) }
            LpButton("重试待同步", enabled = !busy && settings.bool("valid") && (settings.long("pending") ?: 0) > 0, onClick = {
                busy = true
                scope.launch {
                    try { settings = app.call("prefs.retryPrimaryProgressSync").obj()
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { app.report(e)
                    } finally { busy = false }
                }
            })
        }
        error?.let { Dim2(it, Modifier.padding(vertical = Sp.x12)) }
        LpButton("刷新状态", enabled = !busy, onClick = { reload++ }, m = Modifier.padding(top = Sp.x12))
    }
}
