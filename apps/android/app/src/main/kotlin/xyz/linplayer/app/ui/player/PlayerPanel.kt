package xyz.linplayer.app.ui.player

import xyz.linplayer.app.ui.theme.LpText

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.SideEffect
import xyz.linplayer.app.ui.theme.LpIcons
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.boolOrNull
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.strList
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.ui.pages.langCn
import androidx.compose.material3.Text
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.fmtTime
import xyz.linplayer.app.ui.theme.Sp

/** 播放器入口锚定菜单，保留原有选轨、分页与插件操作。 */
@Composable
fun PlayerPanel(
    kind: String?,
    itemId: String,
    exo: androidx.media3.exoplayer.ExoPlayer? = null,
    fit: VideoFit = VideoFit.Source,
    anchor: Rect? = null,
    speed: Double = 1.0,
    hasEpisodes: Boolean = false,
    onSpeed: (Double) -> Unit = {},
    /** 这一格是插件标签时的那一行(`plugin.playerSurfaces` 的 panel),官方面板是 null。 */
    pluginPanel: xyz.linplayer.app.ui.plugin.PlayerSurfaceInfo? = null,
    onOpen: (String) -> Unit = {},
    onFit: (VideoFit) -> Unit = {},
    // 搜索弹幕是**居中大弹窗**,不塞进这个 236dp 的小面板里 ——
    // 搜索结果是「源 → 条目 → 集数」三层,一列 236dp 摊不开
    onSearch: () -> Unit = {},
    onTrackPicked: (String) -> Unit = {},
    onMpvTrackPick: (suspend (String, String) -> Unit)? = null,
    onMpvTracks: (List<JsonObject>) -> Unit = {},
    onExoTrackPick: ((String, String) -> Unit)? = null,
    onPlaybackTarget: (String, String?, String) -> Unit = { _, _, _ -> },
    onSeek: (Double) -> Unit = {},
    onClose: () -> Unit,
) {
    val app = LocalApp.current
    // 自动读取跟随active取消；用户已点击的提交跟随播放页，不随菜单关闭取消。
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val active = kind != null
    var lastKind by remember { mutableStateOf(kind ?: "more") }
    SideEffect { if (kind != null) lastKind = kind }
    val menuKind = kind ?: lastKind
    var danmakuPage by remember(kind, itemId, exo) { mutableStateOf("quick") }
    var lastPluginPanel by remember { mutableStateOf(pluginPanel) }
    SideEffect { if (active) lastPluginPanel = pluginPanel }
    val shownPluginPanel = if (active) pluginPanel else lastPluginPanel

    var options by remember(menuKind, itemId, exo) { mutableStateOf<List<Triple<String, String?, String>>>(emptyList()) }
    var current by remember(menuKind, itemId, exo) { mutableStateOf<String?>(null) }
    var episodes by remember(menuKind, itemId) { mutableStateOf<List<Item>>(emptyList()) }
    var loadError by remember(menuKind, itemId, exo) { mutableStateOf<String?>(null) }
    var retry by remember(menuKind, itemId, exo) { mutableIntStateOf(0) }
    var positioned by remember(menuKind, itemId, exo) { mutableStateOf(false) }
    // 补帧和画质增强同在一个面板里,各自有「当前档」
    var currentInterp by remember { mutableStateOf<String?>(null) }
    // 核心层在安卓上拿不到窗口系统:补完超过刷新率的档由它按这个数藏掉
    val displayHz = androidx.compose.ui.platform.LocalView.current.display?.refreshRate ?: 0f
    var loading by remember(menuKind, itemId, exo) { mutableStateOf(true) }

    LaunchedEffect(active, menuKind, itemId, exo, retry, hasEpisodes) {
        if (!active) return@LaunchedEffect
        loading = true; loadError = null
        if (menuKind != "episodes") options = emptyList()
        when (menuKind) {
            "audio", "subtitle" -> if (exo != null) {
                /* ☠☠ **Exo 内核不走 `player.tracks`。** 那条命令问的是 mpv,
                   而 Exo 那条路上 mpv 手里根本没有这一片 —— 表现是音轨/字幕面板
                   恒空、点了也没反应,而**一句错都不报**。轨道表要问 ExoPlayer 自己。 */
                options = exoTracks(exo, menuKind)
                current = exoCurrent(exo, menuKind)
            } else {
                // ☠ `player.tracks` 返回**裸数组**,轨道类型的字段名是 `kind` 不是 `type`。
                //    两处都错的表现是音轨/字幕面板恒空,而且一句错都不报。
                val t = try {
                    app.call("player.tracks").also { currentCoroutineContext().ensureActive() }
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    loadError = if (menuKind == "audio") "音轨读取失败" else "字幕读取失败"
                    loading = false
                    return@LaunchedEffect
                }
                onMpvTracks(t.arr().mapNotNull { it.obj() })
                val want = if (menuKind == "audio") "audio" else "sub"
                // 字幕多给一项「关闭字幕」(id 空串 = 核心层设 sid=no),和 Exo 那边同一个理由
                val off = if (want == "sub") listOf(Triple("", null, "关闭字幕")) else emptyList()
                options = off + t.arr().mapNotNull {
                    val o = it.obj() ?: return@mapNotNull null
                    if (o.str("kind") != want) return@mapNotNull null
                    val language = trackLanguage(o.str("lang"), menuKind)
                    Triple(
                        o.str("id") ?: return@mapNotNull null,
                        language,
                        o.str("title")?.takeIf { it.isNotBlank() && it != o.str("lang") && (want != "audio" || it != "未标注") }
                            ?: language ?: "轨道",
                    )
                }
                current = t.arr().firstOrNull { it.obj().str("kind") == want && it.obj().bool("selected") }
                    .obj().str("id") ?: if (want == "sub") "" else null
            }
            "source" -> {
                val m = runCatching { app.call("emby.itemMedia", args("item_id" to itemId)) }
                    .getOrNull()
                currentCoroutineContext().ensureActive()
                options = m.arr().mapNotNull {
                    val o = it.obj() ?: return@mapNotNull null
                    Triple(o.str("id") ?: return@mapNotNull null,
                        if (o.bool("preferred")) "推荐" else null, o.str("name") ?: "版本")
                }
            }
            "episodes" -> {
                try {
                    val d = app.call("emby.itemDetail", args("item_id" to itemId)).obj()
                    currentCoroutineContext().ensureActive()
                    // ★ 季的主键叫 season_id(核心层这次补上的);Emby 的 ParentId 不在详情里
                    val season = d.str("season_id")
                    if (season != null) {
                        current = itemId
                        // 首批即可选集；重试从已加载项之后续取，避免丢掉当前列表。
                        app.seasonEpisodes(season, episodes) { items, _ ->
                            episodes = items
                            options = items.map {
                                Triple(it.id, "${(it.runtimeSecs / 60).toInt()} 分钟",
                                    "S${it.seasonNo ?: 1}E${it.episodeNo ?: 1} ${it.name}")
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    loadError = "选集加载失败"
                }
            }
            /* 画面增强。★ **`will_run == false` 的档位不进列表**
               ——【用户定 2026-09-04:「不生效的选项直接删掉,不要展示出来」】。
               核心层每档都带这个判据,上一版安卓侧把它整个丢了,于是 PC 藏起来的
               档位在手机上照样列出来,点了画面一点不变 —— 那正是用户说的
               「完全没看出来效果」。`off` 永远留着:它是「关掉」不是一档效果。 */
            "chapters" -> {
                try {
                    val info = app.call("player.chapterInfo", args("item_id" to itemId)).obj()
                    currentCoroutineContext().ensureActive()
                    options = info?.get("chapters").arr().mapNotNull {
                        val chapter = it.obj()
                        val at = chapter.dbl("start_secs")?.takeIf { t -> t.isFinite() && t >= 0 } ?: return@mapNotNull null
                        Triple(at.toString(), fmtTime(at), chapter.str("name").orEmpty().ifBlank { "章节 ${fmtTime(at)}" })
                    }.distinctBy { it.first }
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { loadError = "章节读取失败" }
            }
            "quality" -> {
                val lv = runCatching { app.call("player.shaderLevels") }.getOrNull().arr()
                currentCoroutineContext().ensureActive()
                options = lv.mapNotNull {
                    val o = it.obj() ?: return@mapNotNull null
                    val id = o.str("id") ?: return@mapNotNull null
                    // ★ 用 boolOrNull:没这个键 = 核心层还没法判断(没在播),那就全都留着
                    if (id != "off" && o.boolOrNull("will_run") == false) return@mapNotNull null
                    Triple(id, o.str("group"), o.str("name") ?: "档位")
                }
                current = lv.firstOrNull { it.obj().bool("selected") }.obj().str("id")
                /* 补帧【用户定 2026-09-17:画质增强 / 补帧 / SDR2HDR 分三类】。
                   同样只列 will_run 不为 false 的档;这台手机用不了时留一行说清原因 ——
                   OpenCL 能不能用因机型而异,整段消失的话用户只会以为是没做。 */
                val st = runCatching {
                    app.call("player.interpLevels", args("display_hz" to displayHz.toDouble()))
                }.getOrNull().obj()
                currentCoroutineContext().ensureActive()
                if (st != null && !st.bool("supported")) {
                    options = options + Triple("interp:na", "补帧", st.str("reason") ?: "这台手机用不了补帧")
                } else if (st != null) {
                    val il = st["levels"].arr()
                    options = options + il.mapNotNull {
                        val o = it.obj() ?: return@mapNotNull null
                        val id = o.str("id") ?: return@mapNotNull null
                        if (id != "off" && o.boolOrNull("will_run") == false) return@mapNotNull null
                        val g = o.str("group")
                        val fps = o.str("fps")  // 在播时的实际帧数,比如 24→48 帧
                        Triple("interp:$id", if (g.isNullOrEmpty()) "补帧" else "补帧 · $g",
                            (o.str("name") ?: "档位") + if (fps.isNullOrEmpty()) "" else " · $fps")
                    }
                    currentInterp = il.firstOrNull { it.obj().bool("selected") }.obj().str("id")?.let { "interp:$it" }
                }
            }
            /* 画面比例。★ 档位表由 [VideoFit] 一处定 —— 面板里再抄一遍的话,
               加一档就得改两处,而漏掉的那处不会报错,只是少一个选项。
               ☠ **只写档位名,不挂说明也不挂「片源未知」**【用户定 2026-09-07:
                 「我只是切换比例而已,这个提示对这个选项没有用处」】。
                 那行字本来是给我自己看的自检 —— 自检该进日志(`lp-exo` 已经在打了),
                 不该摆在用户每次切比例都要读一遍的地方。 */
            "ratio" -> {
                options = VideoFit.entries.map { Triple(it.name, null, it.label) }
                current = fit.name
            }
            /* 「更多」是**跳板**,不是设置项:选一条就换一个面板。
               ☠ 上一版把它当设置项走 `pick`,而 pick 的 when 里根本没有 "more"
                  这一支 —— 落到 `else -> Unit`,表现是「更多里点什么都没反应」。 */
            "speed" -> {
                options = (1..16).map { Triple((it * .25).toString(), null, "${it * .25}×") }
                current = speed.toString()
            }
            "more" -> {
                options = listOfNotNull(
                    if (hasEpisodes) Triple("episodes", null, "选集") else null,
                    Triple("ratio", null, "画面比例"),
                    Triple("source", null, "版本与线路"),
                    Triple("audio", null, "音轨"),
                    /* ☠ **Exo 内核下没有「画面增强」这一项。** 它是 mpv 的 glsl-shaders,
                       Exo 那条路上 mpv 手里根本没有这一片 —— 挂上去是空转,
                       而 `player.setShaderLevel` 照样返回成功。
                       一颗「点开永远没效果」的按钮比没有它更糟。 */
                    if (exo == null) Triple("quality", null, "画面增强") else null,
                    // 弹幕两个内核下都有:它画在 View 层,和 mpv 没关系(见 DanmakuLayer)
                    Triple("danmaku", null, "弹幕"),
                    Triple("substyle", null, "字幕样式"),
                )
            }
        }
        currentCoroutineContext().ensureActive()
        loading = false
    }

    LaunchedEffect(active, menuKind) { if (active && menuKind == "substyle" && !SubStyle.loaded.value) SubStyle.load(app) }
    LaunchedEffect(active, menuKind) { if (active && menuKind == "danmaku") DanmakuStyle.load(app) }

    val width = when (menuKind) {
        "more" -> 192.dp
        "audio", "subtitle", "ratio", "speed", "chapters" -> 200.dp
        "substyle" -> 272.dp
        "danmaku" -> if (danmakuPage == "quick") 220.dp else 280.dp
        else -> 236.dp
    }
    PlayerMenu(active, anchor, width, tall = menuKind == "substyle" || menuKind == "danmaku",
        cornerRadius = if (menuKind == "danmaku") 12.dp else 4.dp,
        background = if (menuKind == "danmaku") Color(0xEB1D2124) else Color(0xFF1D2124),
        sidePanel = menuKind == "danmaku" && danmakuPage == "style",
        onBack = if (menuKind == "danmaku" && danmakuPage != "quick") ({ danmakuPage = "quick" }) else null,
        onClose = onClose) {
        loadError?.let {
            PlayerMenuNote(it, Modifier.padding(horizontal = Sp.x12), maxLines = 2)
            PlayerMenuRow("重试", { retry++ })
        }
        when {
            shownPluginPanel != null -> xyz.linplayer.app.ui.plugin.PluginSurface(
                shownPluginPanel.pluginId, shownPluginPanel.target, "panel",
                modifier = Modifier.fillMaxWidth(),
                claimInitialFocus = false,
                focusNs = "${shownPluginPanel.pluginId}/${shownPluginPanel.target}.",
            )
            menuKind == "substyle" -> SubStylePanel(app, scope)
            menuKind == "danmaku" -> DanmakuPanel(app, scope, itemId, danmakuPage, onPage = { danmakuPage = it }, onSearch = {
                onClose(); onSearch()
            })
            loading && options.isEmpty() -> Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) { xyz.linplayer.app.ui.components.LpLoading(tint = PlayerMenuAccent) }
            options.isEmpty() && loadError == null -> PlayerMenuNote("这里没有可选项", maxLines = 2)
            options.isNotEmpty() -> LazyColumn(Modifier.fillMaxWidth(), list) {
                // 字幕面板顶上挂一个「字幕样式…」的跳板:调样式和选轨是同一件事的两步,
                // 让用户退出去再从「更多」进一遍等于把它藏起来
                if (menuKind == "subtitle") item("substyle") {
                    PlayerMenuRow("字幕样式…", { onOpen("substyle") }, selected = false)
                }
                items(options, key = { it.first }) { (id, badge, label) ->
                    PlayerMenuRow(label, {
                        if (menuKind == "more" || id == "substyle") onOpen(id)
                        else if (id == "interp:na") app.toast(label, ToastKind.Error)
                        else if (menuKind == "ratio") { onFit(VideoFit.of(id)); onClose() }
                        else if (menuKind == "speed") { id.toDoubleOrNull()?.let(onSpeed); onClose() }
                        else if (menuKind == "chapters") { id.toDoubleOrNull()?.let(onSeek); onClose() }
                        else if (menuKind == "source" || menuKind == "episodes") {
                            onPlaybackTarget(if (menuKind == "source") itemId else id,
                                if (menuKind == "source") id else null, label)
                            onClose()
                        }
                        else {
                            if (menuKind == "audio" || menuKind == "subtitle") onTrackPicked(menuKind)
                            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { pick(app, menuKind, id, exo, displayHz, onMpvTrackPick, onExoTrackPick) }
                            onClose()
                        }
                    }, selected = id == current || id == currentInterp,
                        sub = badge?.takeIf { (menuKind == "audio" || menuKind == "subtitle") && it != label },
                        badge = badge.takeUnless { menuKind == "audio" || menuKind == "subtitle" },
                        icon = if (menuKind == "more") when (id) {
                            "episodes" -> LpIcons.list; "ratio" -> LpIcons.aspect; "source" -> LpIcons.version; "audio" -> LpIcons.music
                            "quality" -> LpIcons.sparkle; "danmaku" -> LpIcons.danmaku; else -> LpIcons.sub
                        } else null)
                }
                if (loading) item("loading") { Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) { xyz.linplayer.app.ui.components.LpLoading(tint = PlayerMenuAccent) } }
            }
        }
}

    // 当前项到达后只定位一次；后续补页不能覆盖用户手动滚动的位置。
    LaunchedEffect(active, options, current) {
        if (!active) return@LaunchedEffect
        val i = options.indexOfFirst { it.first == current }
        if (!positioned && i >= 0) {
            list.scrollToItem(i + if (menuKind == "subtitle") 1 else 0)
            positioned = true
        }
    }
}

/** 位置与缩放拖动仅更新预览，松手一次落库；ASS限制说明保留。 */
@Composable
private fun SubStylePanel(app: xyz.linplayer.app.data.AppState, scope: kotlinx.coroutines.CoroutineScope) {
    LazyColumn(Modifier.fillMaxWidth()) {
        item("pos") {
            PlayerStyleSlider("字幕位置", SubStyle.position.intValue.toFloat(), 0f..150f, 29,
                format = { it.roundToInt().toString() }) { value ->
                scope.launch { SubStyle.set(app, "position", value.roundToInt().coerceIn(0, 150)) }
            }
        }
        item("scale") {
            PlayerStyleSlider("字幕大小", SubStyle.scale.doubleValue.toFloat(), .2f..4f, 37,
                format = { "%.1f×".format(it) }) { value ->
                scope.launch { SubStyle.set(app, "scale", (value * 10).roundToInt() / 10.0) }
            }
        }
        item("border") {
            StepRow("字幕描边", "%.1f".format(SubStyle.border.doubleValue)) { up ->
                if (SubStyle.loaded.value) scope.launch {
                    SubStyle.set(app, "border_size", SubStyle.stepBorder(SubStyle.border.doubleValue, up))
                }
            }
        }
        item("bold") {
            PlayerMenuRow("粗体", { if (SubStyle.loaded.value) scope.launch { SubStyle.set(app, "bold", !SubStyle.bold.value) } },
                selected = SubStyle.bold.value)
        }
        item("bywin") {
            PlayerMenuRow("字号跟窗口缩放",
                { if (SubStyle.loaded.value) scope.launch { SubStyle.set(app, "scale_by_window", !SubStyle.scaleByWindow.value) } },
                selected = SubStyle.scaleByWindow.value)
        }
        item("note") {
            PlayerMenuNote("100 = 画面下沿。描边和粗体对 ASS 特效字幕不生效 —— 那种字幕自带样式。")
        }
    }
}

@Composable
internal fun PlayerStyleSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    enabled: Boolean = SubStyle.loaded.value, accent: Color = PlayerMenuAccent, resetKey: Int = 0,
    format: (Float) -> String, onCommit: (Float) -> Unit) {
    var live by remember(value, resetKey) { mutableStateOf(value.coerceIn(range)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = Color.White.copy(alpha = .7f), style = LpText.action)
            Text(format(live), color = Color.White.copy(alpha = .7f), style = LpText.number)
        }
        Slider(live, { live = it }, enabled = enabled, valueRange = range, steps = steps,
            onValueChangeFinished = { onCommit(live) }, modifier = Modifier.semantics { contentDescription = label },
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(alpha = .2f)))
    }
}

/** 弹幕开关。开的时候顺手匹配一次并灌进渲染层。 */
internal suspend fun toggleDanmaku(
    app: xyz.linplayer.app.data.AppState, itemId: String, on: Boolean,
) {
    DanmakuSession.invalidate()
    runCatching { app.call("player.setDanmakuEnabled", args("enabled" to on)) }
        .onFailure { app.report(it); return }
    DanmakuStyle.enabled.value = on
    if (on) loadDanmakuFor(app, itemId, loud = true)
    else {
        runCatching { app.call("player.danmakuBegin") }
        DanmakuStyle.layout.value = null
    }
}

/**
 * 一行步进器:标签 + 读数 + 两个键。
 *
 * ★ 两个键的命中区拉到 36dp:面板本身只有 236dp 宽,按 12sp 的字面大小去点
 *   在播放页(手指、还常常在动)上是点不中的。
 */
@Composable
internal fun StepRow(label: String, readout: String, onStep: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(vertical = Sp.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerMenuNote(label, Modifier.weight(1f))
        StepKey("−") { onStep(false) }
        androidx.compose.material3.Text(
            readout, Modifier.widthIn(min = 56.dp),
            color = Color.White.copy(alpha = .7f), style = LpText.number,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepKey("+") { onStep(true) }
    }
}

@Composable
private fun StepKey(glyph: String, onClick: () -> Unit) = Box(
    // pressable 是全站统一的那个(带按压反馈),不自己再搭一套
    Modifier.width(36.dp).heightIn(min = 36.dp).pressable(onClick, null),
    contentAlignment = Alignment.Center,
) {
    androidx.compose.material3.Text(glyph, color = Color.White.copy(alpha = .7f), style = LpText.section)
}

private suspend fun pick(
    app: xyz.linplayer.app.data.AppState, kind: String, id: String,
    exo: androidx.media3.exoplayer.ExoPlayer? = null,
    displayHz: Float = 0f,
    onMpvTrackPick: (suspend (String, String) -> Unit)? = null,
    onExoTrackPick: ((String, String) -> Unit)? = null,
) {
    if (kind == "audio" || kind == "subtitle") {
        runCatching {
            if (exo != null) { if (onExoTrackPick != null) onExoTrackPick(kind, id) else exoPick(exo, kind, id) }
            else if (onMpvTrackPick != null) onMpvTrackPick(kind, id)
            else app.call("player.setTrack", args("kind" to if (kind == "subtitle") "sub" else kind, "id" to id))
        }.onFailure { app.report(it) }
        return
    }
    runCatching {
        when (kind) {
            /* ★ 超分**必须看返回体**:`setShaderLevel` 在着色器跑不起来时会
               自己退回关闭并带上 `reverted` —— 不看就是「界面说已启用、实际是关的」,
               本仓最贵的那类 bug。核心层把原因写在 note 里,原样转给用户。 */
            // 补帧。挂上只说明滤镜收下了;跑不动由 player.interpReverted 事件回来说(PlayerPage 订阅)
            "quality" -> if (id.startsWith("interp:")) {
                val level = id.removePrefix("interp:")
                val r = app.call("player.setInterpLevel",
                    args("level" to level, "display_hz" to displayHz.toDouble())).obj()
                if (r.bool("reverted")) app.toast(r.str("note") ?: "补帧开不了", ToastKind.Error)
                else if (level != "off") app.toast("补帧已开启,光流起来要一两秒")
            } else {
                val r = app.call("player.setShaderLevel", args("level" to id)).obj()
                if (r.bool("reverted")) {
                    app.toast(r.str("note") ?: "这档在你这台机器上跑不起来", ToastKind.Error)
                }
            }
            else -> Unit
        }
    }.onFailure { app.report(it) }
}

/**
 * 匹配本片弹幕并灌进播放器。
 *
 * ★ 匹配不上**不弹错**:九成片子本来就没有弹幕,弹一次错等于骂用户一次。
 *   `danmaku.autoLoad` 分不够时返回 null,那是正常结果不是失败。
 */
/**
 * 起播时的弹幕。**换一集必须先清**:核心层存的是上一集的语料,
 * 这一集匹配不上时它会原样留着 —— 表现是新一集播着上一集的弹幕。
 */
internal suspend fun startDanmakuFor(app: xyz.linplayer.app.data.AppState, itemId: String) {
    DanmakuSession.invalidate()
    val generation = DanmakuSession.generation
    DanmakuStyle.source.value = ""
    DanmakuStyle.offset.doubleValue = 0.0
    DanmakuStyle.layout.value = null
    runCatching { app.call("player.danmakuBegin") }
    currentCoroutineContext().ensureActive()
    if (generation != DanmakuSession.generation) return
    DanmakuStyle.load(app)
    currentCoroutineContext().ensureActive()
    if (generation != DanmakuSession.generation) return
    if (DanmakuStyle.enabled.value) loadDanmakuFor(app, itemId, loud = false)
}

internal suspend fun loadDanmakuFor(
    app: xyz.linplayer.app.data.AppState, itemId: String, loud: Boolean = false, ignoreMemory: Boolean = false,
) {
    resolveDanmakuFor(app, itemId, loud = loud, ignoreMemory = ignoreMemory)
}

// ---------------------------------------------------------------- Exo 的轨道表

/** 关字幕那一项的 id。用一个不可能和 `Format.id` 撞的串。 */
private const val EXO_TRACK_OFF = "lp:off"

/**
 * ExoPlayer 的音轨 / 字幕轨。
 *
 * ★ id 用 `groupIndex:trackIndex` 而不是 `Format.id`:后者**允许为 null**,
 *   而且同一个文件里可以重复 —— 拿它当主键的表现是「选了第二条,生效的是第一条」。
 * ★ 字幕多给一项「关闭字幕」:没有它的话字幕一旦打开就再也关不掉。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun exoTracks(
    exo: androidx.media3.exoplayer.ExoPlayer, kind: String,
): List<Triple<String, String?, String>> {
    val want = if (kind == "audio") androidx.media3.common.C.TRACK_TYPE_AUDIO
    else androidx.media3.common.C.TRACK_TYPE_TEXT
    val out = ArrayList<Triple<String, String?, String>>()
    if (want == androidx.media3.common.C.TRACK_TYPE_TEXT) {
        out.add(Triple(EXO_TRACK_OFF, null, "关闭字幕"))
    }
    exo.currentTracks.groups.forEachIndexed { gi, g ->
        if (g.type != want) return@forEachIndexed
        for (ti in 0 until g.length) {
            val f = g.getTrackFormat(ti)
            val name = listOfNotNull(
                f.label?.takeIf { it.isNotBlank() && it != f.language && (kind != "audio" || it != "未标注") }
                    ?: trackLanguage(f.language, kind),
                // ASS 标出来:用户才知道这条是带特效的那一条
                if (Libass.isAss(f)) "特效" else null,
            ).joinToString(" · ").ifBlank { "轨道 ${gi + 1}-${ti + 1}" }
            out.add(Triple("$gi:$ti", trackLanguage(f.language, kind), name))
        }
    }
    return out
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun exoCurrent(exo: androidx.media3.exoplayer.ExoPlayer, kind: String): String? {
    val want = if (kind == "audio") androidx.media3.common.C.TRACK_TYPE_AUDIO
    else androidx.media3.common.C.TRACK_TYPE_TEXT
    exo.currentTracks.groups.forEachIndexed { gi, g ->
        if (g.type != want) return@forEachIndexed
        for (ti in 0 until g.length) if (g.isTrackSelected(ti)) return "$gi:$ti"
    }
    return if (want == androidx.media3.common.C.TRACK_TYPE_TEXT) EXO_TRACK_OFF else null
}

/**
 * 切轨。
 *
 * ☠ 关字幕要**同时**关掉 libass:只 disable ExoPlayer 的文本轨,
 * libass 手里那条 track 还在,画面上的特效字幕纹丝不动。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun exoPick(exo: androidx.media3.exoplayer.ExoPlayer, kind: String, id: String) {
    val text = androidx.media3.common.C.TRACK_TYPE_TEXT
    if (id == EXO_TRACK_OFF) {
        Libass.deactivate()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(text)
            .setTrackTypeDisabled(text, true)
            .build()
        return
    }
    val gi = id.substringBefore(':').toIntOrNull() ?: return
    val ti = id.substringAfter(':').toIntOrNull() ?: return
    val g = exo.currentTracks.groups.getOrNull(gi) ?: return
    if (ti !in 0 until g.length) return
    val type = g.type
    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(type, false)
        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, ti))
        .build()
    // 选的是 ASS 就把 libass 接上,不是就撤掉 —— `onTracksChanged` 里那条同源判断
    if (type == text && !Libass.isAss(g.getTrackFormat(ti))) Libass.deactivate()
}

/** 音轨隐藏未知语言占位；可辨认的轨道语言使用中文名称。 */
internal fun trackLanguage(language: String?, kind: String): String? =
    langCn(language?.takeIf { it.isNotBlank() && (kind != "audio" || !it.equals("und", true)) })
