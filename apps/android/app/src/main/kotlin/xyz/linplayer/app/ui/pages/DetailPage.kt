package xyz.linplayer.app.ui.pages

import xyz.linplayer.app.ui.theme.LpText

import xyz.linplayer.app.ui.components.sharedPoster
import xyz.linplayer.app.ui.components.posterPreview
import xyz.linplayer.app.ui.components.detailPreview
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import xyz.linplayer.app.ui.components.LpMenu
import xyz.linplayer.app.ui.components.LpMenuItem
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import android.net.Uri
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.block
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.boolOrNull
import xyz.linplayer.app.ui.player.TrackCandidate
import xyz.linplayer.app.ui.player.preferredTrackIndex
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.namedList
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.strList
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.LongShotTarget
import xyz.linplayer.app.ui.components.Dim3
import xyz.linplayer.app.ui.components.Kicker
import xyz.linplayer.app.ui.components.Layer
import xyz.linplayer.app.ui.components.LpButton
import xyz.linplayer.app.ui.components.LpDialog
import xyz.linplayer.app.ui.components.LpImmersive
import xyz.linplayer.app.ui.components.LoadingState
import xyz.linplayer.app.ui.components.LpRow
import xyz.linplayer.app.ui.components.NetImage
import xyz.linplayer.app.ui.components.OptRow
import xyz.linplayer.app.ui.components.SectionTitle
import xyz.linplayer.app.ui.plugin.Anchored
import xyz.linplayer.app.ui.plugin.LocalAnchors
import xyz.linplayer.app.ui.plugin.PluginAnchors
import xyz.linplayer.app.ui.plugin.detailProps
import xyz.linplayer.app.ui.plugin.rememberAnchorScope
import xyz.linplayer.app.ui.components.dissolve
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.components.toneScene
import xyz.linplayer.app.ui.theme.Dim
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp
import xyz.linplayer.app.ui.theme.T
import xyz.linplayer.app.ui.theme.lpTween
import xyz.linplayer.app.ui.theme.rememberTone

/* ───────────────────────────── 数据 ───────────────────────────── */

/** 一条流。字段名照 `core/emby/mediainfo.go` 的 `StreamInfo`。 */
internal data class Stream(
    val index: Long, val type: String, val codec: String, val profile: String?,
    val title: String?, val display: String?, val lang: String?,
    val width: Long?, val height: Long?,
    val bitrate: Long?, val channels: Long?, val layout: String?, val fps: Double?,
    val range: String?, val isDefault: Boolean, val isExternal: Boolean,
    val bitDepth: Long? = null, val colorSpace: String? = null, val pixelFormat: String? = null,
    val isForced: Boolean? = null,
) {
    /* ☠ **轨道名优先用 `title`,不是 `display_title`。** 后者是 Emby 自己拼的
       「语言 + 格式」(「Chinese - PGS」),它长得像名字但不是名字 ——
       压制组写的「简体中文特效」在 `title` 里。上一版只有 display_title,
       于是整张字幕表看起来全是格式标签,谁是谁分不出来(用户 2026-09-07 第二次报)。 */
    val label: String
        get() = title?.takeUnless { type == "Audio" && (it.isBlank() || it == "未标注") }
            ?: display?.takeUnless { type == "Audio" && (it.isBlank() || it == "未标注") }
            ?: listOfNotNull(
            langCn(lang?.takeUnless { type == "Audio" && it.equals("und", true) }),
            codec.uppercase().takeIf { it.isNotBlank() },
        ).joinToString(" ")

    /** 副标题那一行:语言标识 / 字幕格式【用户定 2026-09-07】。 */
    val langAndCodec: String
        get() = listOfNotNull(
            langCn(lang) ?: lang,
            codec.uppercase().takeIf { it.isNotBlank() },
        ).joinToString(" / ")
}

/** 一个可播版本。`preferred` 由**核心层**标 —— UI 不许自己回落 `versions[0]`。 */
internal data class Version(
    val id: String, val name: String, val preferred: Boolean,
    val container: String? = null, val sizeBytes: Long? = null, val bitrate: Long? = null,
    val streams: List<Stream> = emptyList(),
    val path: String? = null, val dateCreated: String? = null,
) {
    fun of(kind: String) = streams.filter { it.type == kind }
    /** 泛称不能识别文件，优先用安全路径的文件名；仅影响展示，不参与版本选择。 */
    val displayName: String
        get() {
            val title = name.trim()
            if (title.isNotEmpty() && title.lowercase(Locale.ROOT) !in
                setOf("版本", "默认版本", "default", "default version")) return title
            return displayMediaPath(path)?.substringBefore('?')?.substringBefore('#')
                ?.replace('\\', '/')?.substringAfterLast('/')?.trim()?.takeIf(String::isNotEmpty)
                ?: "未提供版本名称"
        }
    companion object {
        /** ☠ `emby.itemMedia` 返回的是**裸数组** `[]MediaVersion`,不是 `{versions:[…]}`。 */
        fun list(e: kotlinx.serialization.json.JsonElement?): List<Version> = e.arr().mapNotNull {
            val o = it.obj() ?: return@mapNotNull null
            Version(
                id = o.str("id") ?: return@mapNotNull null,
                name = o.str("name") ?: "版本",
                preferred = o.bool("preferred"),
                container = o.str("container"),
                sizeBytes = o.long("size_bytes"),
                bitrate = o.long("bitrate"),
                path = o.str("path"), dateCreated = o.str("date_created"),
                streams = o["streams"].arr().mapNotNull inner@{ se ->
                    val s = se.obj() ?: return@inner null
                    Stream(
                        index = s.long("index") ?: 0, type = s.str("type_") ?: "",
                        codec = s.str("codec") ?: "", profile = s.str("profile"),
                        title = s.str("title"), display = s.str("display_title"),
                        lang = s.str("language"),
                        width = s.long("width"), height = s.long("height"),
                        bitrate = s.long("bitrate"), channels = s.long("channels"),
                        layout = s.str("channel_layout"), fps = s.dbl("frame_rate"),
                        range = s.str("video_range_type") ?: s.str("video_range"),
                        isDefault = s.bool("is_default"), isExternal = s.bool("is_external"),
                        bitDepth = s.long("bit_depth"), colorSpace = s.str("color_space"),
                        pixelFormat = s.str("pixel_format"),
                        isForced = (s["is_forced"] as? JsonPrimitive)?.booleanOrNull,
                    )
                },
            )
        }
    }
}

/** 一位演职人员。 */
internal data class Person(val id: String, val name: String, val role: String? = null)

/**
 * 唯一的「会播哪个版本」算法。
 * ☠ **不许自己回落 `versions[0]`** —— 核心层没标 preferred 就是「让核心层自己决定」,
 * UI 传 null 而不是替它选一个。这条有真实故障:正则真选对了版本,
 * 但详情页写死回落,用户看到的是「功能没生效」。
 */
internal fun defaultVersion(vs: List<Version>): Version? = vs.firstOrNull { it.preferred }

/**
 * 这一版画面的宽高比。拿不到就 0。
 *
 * 用处是**在进播放页之前**就把横竖屏定下来(见 `Route.Player.ar`)。
 * 0 不是「方形」,是「不知道」—— strm / 网盘源常常没有这份数据,那时不许瞎猜一个方向,
 * 交给播放页自己去问。
 */
internal fun arOf(v: Version?): Float {
    val s = v?.of("Video")?.firstOrNull() ?: return 0f
    val w = s.width ?: return 0f
    val h = s.height ?: return 0f
    return if (w > 0 && h > 0) w.toFloat() / h else 0f
}

/* ───────────────────────────── 页面 ───────────────────────────── */

/**
 * 详情页族(U1.5)。版式照草稿 03(剧 / 影)与 04(集)。
 *
 * ☠ **封面不许有边界**【用户定 2026-09-06】:背景图下沿用 [dissolve] 把自己的 alpha 推到 0,
 *   露出来的是**从这张封面取的色**([rememberTone] + [toneScene])。
 *   盖一层「透明→背景色」的渐变是错的 —— 那会留下一块比周围略深的矩形。
 * ☠ **没有分享按钮**【用户定】—— 我们本来就分享不了。
 */
@Composable
fun DetailPage(nav: NavController, entry: NavBackStackEntry) {
    val app = LocalApp.current
    val session by app.session.collectAsState()
    val account = session ?: return
    key(entry.id, account.server, account.userId) {
        DetailContent(nav, entry, app.detailCache.key(account.server, account.userId, entry.toRoute<Route.Detail>().itemId))
    }
}

@Composable
private fun DetailContent(nav: NavController, entry: NavBackStackEntry, cacheKey: String) {
    var switching by remember { mutableStateOf(false) }
    val route = entry.toRoute<Route.Detail>()
    val app = LocalApp.current
    val c = Lp.colors
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    if (switching) EmbySwitchSource(nav, route.itemId) { switching = false }
    // 截长屏认的就是这个滚动容器(设置里开了才画按钮,见 LongShot)
    LongShotTarget(list)


    var detail by remember { mutableStateOf<Block<JsonObject>>(Block.Loading) }
    var cached by remember { mutableStateOf(app.detailCache.peek(cacheKey)) }
    var detailRetry by remember { mutableStateOf(0) }
    var cacheAllowed by remember { mutableStateOf(true) }
    LaunchedEffect(cacheKey) {
        val stored = app.detailCache.load(cacheKey, app.detailCache.generation)
        if (detail !is Block.Ok && cacheAllowed) cached = stored
    }
    var media by remember { mutableStateOf<Block<List<Version>>>(Block.Loading) }
    var mediaRetry by remember { mutableStateOf(0) }
    val versions = media.valueOrNull.orEmpty()
    /** ☠ 「未选」是 `null` 不是 `0` —— 传了 id 核心层就走「手动指定」分支,版本正则整个被跳过。 */
    var pickedVersion by remember { mutableStateOf<String?>(null) }
    var seasons by remember { mutableStateOf<List<Item>>(emptyList()) }
    var seasonImages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var seasonLoad by remember { mutableStateOf<Block<Unit>>(Block.Loading) }
    var seasonRetry by remember { mutableStateOf(0) }
    var curSeason by remember { mutableStateOf<Item?>(null) }
    var episodeRequest by remember { mutableStateOf(0) }
    var episodes by remember(route.itemId, curSeason?.id, episodeRequest) { mutableStateOf<List<Item>>(emptyList()) }
    var episodeTotal by remember(route.itemId, curSeason?.id, episodeRequest) { mutableStateOf<Long?>(null) }
    var episodeLoad by remember(route.itemId, curSeason?.id, episodeRequest) { mutableStateOf<Block<Unit>>(Block.Loading) }
    var episodeRetry by remember { mutableStateOf(0) }
    LaunchedEffect(route.itemId, curSeason?.id, episodeRequest, episodeRetry) {
        val parent = curSeason?.id ?: return@LaunchedEffect
        episodeLoad = Block.Loading
        val result = try {
            app.seasonEpisodes(parent, episodes) { items, total ->
                episodes = items
                episodeTotal = total
            }
            Block.Ok(Unit)
        } catch (e: CancellationException) { throw e }
          catch (e: xyz.linplayer.app.core.CoreException) { Block.Fail(e.code, e.advice) }
          catch (e: Exception) { Block.Fail("E_INTERNAL", e.message ?: "分集加载失败") }
        currentCoroutineContext().ensureActive()
        episodeLoad = result
    }
    var similar by remember { mutableStateOf<List<Item>>(emptyList()) }
    var favorite by remember { mutableStateOf(false) }
    var played by remember { mutableStateOf(false) }
    var audioLang by remember { mutableStateOf<String?>(null) }
    var subLang by remember { mutableStateOf<String?>(null) }
    var subRegex by remember { mutableStateOf("") }
    var subsEnabled by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var serverId by remember { mutableStateOf<String?>(null) }
    /** 「哪台服务器的哪条线路」。取不到线路表时只有服务器名 —— 也比「服务器线路」四个字强。 */
    var lineLabel by remember { mutableStateOf<String?>(null) }
    var lineCount by remember { mutableStateOf(0) }

    // 展示资料可先用缓存，用户状态、分集目标与插件仍只用本次服务端结果。
    LaunchedEffect(route.itemId, detailRetry) {
        val epoch = app.detailCache.generation
        detail = Block.Loading
        val d = app.block("emby.itemDetail", args("item_id" to route.itemId))
        currentCoroutineContext().ensureActive()
        detail = when (d) {
            is Block.Ok -> Block.Ok(d.value.obj() ?: JsonObject(emptyMap()))
            is Block.Fail -> d
            else -> Block.Loading
        }
        val o = d.valueOrNull.obj()
        favorite = o.bool("is_favorite")
        played = o.bool("played")
        if (d is Block.Fail && d.code in setOf("E_AUTH", "E_NOTFOUND")) {
            cacheAllowed = false
            cached = null
            app.detailCache.remove(cacheKey, epoch)
        } else if (o != null) {
            cacheAllowed = true
            launch { app.detailCache.put(cacheKey, o, epoch) }
        }
    }

    // 季失败仅重试选集；effect的key和放行条件必须使用同一份详情快照。
    val seasonDetail = detail.valueOrNull
    LaunchedEffect(route.itemId, seasonDetail, seasonRetry) {
        val o = seasonDetail ?: return@LaunchedEffect
        val seriesId = o.str("series_id") ?: route.itemId.takeIf { route.type == "Series" }
        // ★ 这两个值都从**详情**里取,在发下一条命令之前就读完 ——
        //   夹在 seriesSeasons 调用后面的话,`check-android-fields.py` 的窗口
        //   会把它算成 SeasonInfo 的字段,报一条查不出所以然的假红
        val wantSeason = o.str("season_id") ?: route.itemId
        if (seriesId != null &&
            (route.type == "Series" || route.type == "Season" || route.type == "Episode")) {
            seasonLoad = Block.Loading
            val result = app.block("emby.seriesSeasons", args("series_id" to seriesId))
            currentCoroutineContext().ensureActive()
            seasons = result.valueOrNull.arr()
                .mapNotNull { value -> Item.from(value)?.copy(seasonNo = value.obj().long("index_no")) }
            seasonImages = result.valueOrNull.arr().filter { it.obj().boolOrNull("has_primary") != false }
                .mapNotNull { it.obj().str("id") }.toSet()
            // 集详情页要定位到**这一集所属的季**,不是第一季
            val s = seasons.firstOrNull { it.id == wantSeason } ?: seasons.firstOrNull()
            curSeason = s
            seasonLoad = when (result) {
                is Block.Fail -> result
                else -> Block.Ok(Unit)
            }
        } else seasonLoad = Block.Ok(Unit)
    }

    // 媒体选项一次就绪；失败不伪装成默认版本，重试不重复其它详情请求。
    LaunchedEffect(route.itemId, mediaRetry) {
        media = Block.Loading
        val result = app.block("emby.itemMedia", args("item_id" to route.itemId))
        currentCoroutineContext().ensureActive()
        media = when (result) {
            is Block.Ok -> Block.Ok(Version.list(result.value))
            is Block.Fail -> result
            else -> Block.Loading
        }
    }

    // 辅助请求不等待磁盘、详情或媒体选项。
    LaunchedEffect(route.itemId) {
        launch { similar = Item.list(app.block("emby.similarItems", args("item_id" to route.itemId)).valueOrNull) }
        launch {
            val p = runCatching { app.call("prefs.getPrefs") }.getOrNull().obj()
            audioLang = p.str("audio_lang"); subLang = p.str("sub_lang")
            subRegex = p.str("sub_regex") ?: ""
            subsEnabled = p.boolOrNull("sub_enabled") != false
        }
        /* 线路那一行要跳线路页,而线路页认的是**账号 id**,不是当前生效的线路地址。
           拿 session.server 去传是错的 —— 换过线路之后那个值已经是中转地址了。 */
        launch {
            val raw = app.block("account.listAccounts").valueOrNull
            val active = xyz.linplayer.app.data.Account.list(raw).firstOrNull { it.isActive }
            serverId = active?.id
            /* ★★ 线路那一行要写**具体是哪台的哪条**【用户定 2026-09-06】。
               原来恒写死「服务器线路」—— 那句话对多线路用户等于没说:
               他想知道的正是「我现在连的是哪一条」。
               线路名和当前线路都只在**账号表**里(`lines[] / active_line`);
               `account.probeLines` 只发 index/ms/url,照它取值会恒「线路 N」。 */
            val accObj = raw.arr().firstOrNull { it.obj().str("server") == active?.id }.obj()
            val idx = accObj.long("active_line")?.toInt() ?: 0
            val lines = accObj?.get("lines").arr()
            lineCount = lines.size
            val lineName = lines.getOrNull(idx).obj()?.str("name")?.takeIf { it.isNotBlank() }
                ?: if (lines.isEmpty()) "主线路" else "线路 ${idx + 1}"
            lineLabel = active?.name?.let { "$it · $lineName" } ?: lineName
        }
        // 进详情页就开始预热「▶ 会播的那个条目」。fire-and-forget,失败全吞
        launch { runCatching { app.call("prefs.preloadItem", args("item_id" to route.itemId)) } }
    }

    // 离页取消预热:留着不取消 = 用户翻十个详情就有十条流在偷偷拉
    androidx.compose.runtime.DisposableEffect(route.itemId) {
        // 同 PlayerPage:收尾要走 app.bg,composition 的 scope 这时已经在取消了
        onDispose { app.bg.launch { runCatching { app.call("prefs.preloadCancel") } } }
    }

    val live = detail.valueOrNull
    val d = live ?: cached
    val people = remember(d) { d?.get("people").arr().mapNotNull {
        val p = it.obj() ?: return@mapNotNull null
        Person(p.str("id") ?: return@mapNotNull null, p.str("name") ?: "",
            p.str("role")?.takeIf { it.isNotBlank() })
    } }
    val title = d.str("name") ?: ""
    val isEpisode = route.type == "Episode"
    val isSeries = route.type == "Series" || route.type == "Season"
    val isSeriesEntry = route.type == "Series"
    val nextEp = episodes.firstOrNull { !it.played } ?: episodes.firstOrNull()
    /* ☠ **合集原来一个字都画不出来。** 详情那条链只对 Series/Season 拉子项,
       而合集本身没有简介、没有年份、没有演职员 —— 整页只剩一个标题
       (用户 2026-09-08:「合集无法正确显示,显示不出来任何的东西」)。
       它也不该有播放按钮:合集不是一部片,点了核心层只会报错。 */
    val isBoxSet = route.type == "BoxSet"
    var boxMovies by remember(route.itemId) { mutableStateOf<List<Item>>(emptyList()) }
    var boxSeries by remember(route.itemId) { mutableStateOf<List<Item>>(emptyList()) }
    var boxOthers by remember(route.itemId) { mutableStateOf<List<Item>>(emptyList()) }
    var boxLoading by remember(route.itemId) { mutableStateOf(isBoxSet) }
    LaunchedEffect(route.itemId, isBoxSet) {
        if (!isBoxSet) return@LaunchedEffect
        val r = app.block("emby.collectionItems", args("item_id" to route.itemId)).valueOrNull.obj()
        boxMovies = Item.list(r?.get("movies"))
        boxSeries = Item.list(r?.get("series"))
        boxOthers = Item.list(r?.get("others"))
        boxLoading = false
    }
    /* ☠☠ **展示用的版本和「发给核心层的版本 id」是两回事,以前混成了一个。**
       `defaultVersion` 只认核心层标的 `preferred`,标不出来时返回 null —— 那是对的,
       因为**不许替核心层挑一个 id 发过去**(版本正则会被整个跳过)。
       但上一版把这个 null 直接当成了「没有版本可展示」,后果全落在集详情页上:
         · 音轨 / 字幕两行 `ver?.of(...)` 恒空 → onClick 恒 null → **点不动**
         · 「媒体信息」整块 `if (ver != null)` 不成立 → **整块不画**
       用户报的「音轨字幕选不了、缺媒体信息模块」是同一个 null。
       所以这里分成两个值:**展示**可以回落到第一条,**发命令**仍然只认 preferred。 */
    val ver = versions.firstOrNull { it.id == pickedVersion }
        ?: defaultVersion(versions) ?: versions.firstOrNull()
    val contentFade = lpTween<Float>(T.T5, LinearEasing)
    val contentSize = lpTween<IntSize>(T.T5)

    // 底色从**海报**取,不从背景图取:海报是这部片的主视觉,背景图常常是一片夜景
    val tone = rememberTone(app.imageUrl(route.itemId, "Primary", 330), c.acc.copy(alpha = .9f))

    /* 起播。`engine` 为空 = 用设置里的默认内核。
       ★ 长按播放键换内核【用户定 2026-09-08】:短按一个、长按另一个,哪个在前由设置决定。
         做成「这一次的参数」而不是改全局开关 —— 换回来不必再进一趟设置页。 */
    val toPlayer: (String, String?, Boolean) -> Unit = { target, engine, fromStart ->
        xyz.linplayer.app.ui.player.PlaybackClickTimes.navigate(nav, Route.Player(
            target, title, pickedVersion ?: ver?.takeIf { it.preferred }?.id, engine,
            // 只有播的就是这一页这一片时,手里这份宽高比才算数
            ar = if (target == route.itemId) arOf(ver) else 0f, fromStart = fromStart))
    }

    val bodyHeight = with(LocalDensity.current) {
        rememberTextMeasurer().measure("共 1 季", style = LpText.body).size.height.toDp()
    }
    val barTint = if (list.firstVisibleItemIndex == 0) Color.White else c.fg

    LpImmersive(bar = {
        IconButton(onClick = { nav.popBackStack() }) {
            Icon(LpIcons.back, "返回", Modifier.size(24.dp), tint = barTint)
        }
        Spacer(Modifier.weight(1f))
        if (!isBoxSet) {
            // Emby 的片也能换到数据源看(D525):这是跳转,不是把数据源塞进来当线路
            IconButton(onClick = { switching = true }) {
                Icon(LpIcons.switchSource, "换源", Modifier.size(24.dp), tint = barTint)
            }
            IconButton(enabled = live != null, onClick = {
                scope.launch {
                    val want = !played
                    played = want
                    runCatching {
                        app.call("emby.setPlayed", args("item_id" to route.itemId, "played" to want))
                    }.onFailure { played = !want; app.report(it) }
                }
            }) {
                if (played) Box(
                    Modifier.size(24.dp).clip(RoundedCornerShape(R.pill)).background(barTint),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(LpIcons.check, "标未看", Modifier.size(18.dp),
                        tint = if (list.firstVisibleItemIndex == 0) Color.Black else c.bg)
                } else Icon(LpIcons.checkCircle, "标已看", Modifier.size(24.dp), tint = barTint)
            }
        }
        if (!isBoxSet && !isEpisode) IconButton(enabled = live != null, onClick = {
            scope.launch {
                val want = !favorite
                favorite = want
                runCatching {
                    app.call("emby.setFavorite", args("item_id" to route.itemId, "fav" to want))
                }.onFailure { favorite = !want; app.report(it) }
            }
        }) {
            Icon(if (favorite) LpIcons.heartOn else LpIcons.heart, "收藏", Modifier.size(24.dp),
                tint = if (favorite) c.mediaIcon else barTint)
        }
        if (!isBoxSet) IconButton(onClick = {
            scope.launch {
                runCatching { app.call("download.enqueue", args("item_id" to route.itemId)) }
                    .onSuccess { app.toast("已加入下载队列", xyz.linplayer.app.data.ToastKind.Ok) }
                    .onFailure { app.report(it) }
            }
        }) {
            Icon(LpIcons.download, "下载", Modifier.size(24.dp), tint = barTint)
        }
    }) { pad ->
        val f = detail
        if (f is Block.Fail && !f.isSilent && d == null) {
            xyz.linplayer.app.ui.components.ErrorState(f.message, { detailRetry++ }, Modifier.fillMaxSize())
            return@LpImmersive
        }

        // ★ 取色底铺满整屏并且**不跟着滚**:图往上走、色留在原地,那一层视差就是「华丽」的来源
        /* 插件注入位(SPEC 6.2)。详情还没回来时先给一张空表:props 是**挂载那一刻**
           读走的,拿 null 条目挂上去的插件块此后永远收不到真数据。 */
        val anchors = rememberAnchorScope(PluginAnchors.Detail, detailProps(live))
        androidx.compose.runtime.CompositionLocalProvider(
            LocalAnchors provides if (live == null) xyz.linplayer.app.ui.plugin.AnchorScope() else anchors
        ) {
        Box(Modifier.fillMaxSize().background(toneScene(tone, c.bg))) {
            LazyColumn(Modifier.fillMaxSize(), list, contentPadding = pad) {
                item("hero") {
                    Anchored(PluginAnchors.DETAIL_HEADER) {
                        if (isEpisode) EpisodeHead(app, route.itemId, d, list) {
                            live.str("series_id")?.let { sid ->
                                nav.navigate(Route.Detail(sid, "Series"))
                            }
                        }
                        else SeriesHead(app, route.itemId, d, list, isSeriesEntry)
                    }
                }

                if (f is Block.Fail && !f.isSilent) item("refresh-error") {
                    xyz.linplayer.app.ui.components.ErrorState("资料刷新失败：${f.message}", { detailRetry++ })
                }

                // 标题画在头图**里面**,没有独立的一块可以换掉,所以这里是纯插入位:
                // 「标题下方」(SPEC 20.3)—— hide 没有东西可藏,replace 等同于 after
                item("anchor-title") { Anchored(PluginAnchors.DETAIL_TITLE, Modifier.padding(horizontal = Sp.x16)) {} }

                // 评分就在这条数据带里(★ 评分是第一格),锚点挂它
                item("data") { Anchored(PluginAnchors.DETAIL_RATINGS, Modifier.padding(horizontal = Sp.x16)) {
                    if (isSeriesEntry) {
                        val summary = Triple(d.dbl("rating")?.takeIf { it > 0 },
                            d.long("year")?.toString() ?: detailDate(d)?.take(4),
                            seasons.size.takeIf { seasonLoad is Block.Ok && it > 0 })
                        DetailReveal(summary, Modifier.testTag("detail.ratings")) { current ->
                            androidx.compose.foundation.layout.FlowRow(
                                Modifier.fillMaxWidth().padding(horizontal = Sp.x16, vertical = Sp.x12)
                                    .heightIn(min = bodyHeight + 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(Sp.x10),
                                verticalArrangement = Arrangement.spacedBy(Sp.x6),
                            ) {
                                current.first?.let {
                                    Text("★ %.1f".format(it), color = c.mediaIcon, style = LpText.body)
                                }
                                current.second?.let {
                                    Text(it, color = c.fg2, style = LpText.body)
                                }
                                current.third?.let { count -> Text(
                                    "共 $count 季",
                                    Modifier.border(1.dp, c.mediaIcon, RoundedCornerShape(4.dp))
                                        .padding(horizontal = Sp.x6, vertical = 2.dp),
                                    color = c.mediaIcon, style = LpText.body,
                                ) }
                            }
                        }
                        return@Anchored
                    }
                    val values = listOfNotNull(
                        d.dbl("rating")?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                        detailDate(d),
                        d.dbl("runtime_secs")?.takeIf { it > 0 }?.let { fmtDur(it) },
                        d.long("child_count")?.takeIf { it > 0 && isSeries }?.let { "$it 集" },
                    )
                    DetailReveal(if (d == null) null else values, Modifier.testTag("detail.ratings")) { current ->
                        if (current == null) Spacer(Modifier.height(with(LocalDensity.current) { 21.sp.toDp() } + Sp.x12 * 2))
                        else if (current.isNotEmpty()) Text(
                            current.joinToString(" · "),
                            Modifier.padding(horizontal = Sp.x16, vertical = Sp.x12),
                            color = c.fg2, style = LpText.body,
                        )
                    }
                } }

                item("tags") {
                    /* 类型 / 标签 / 工作室这三种**能点**
                       【用户定 2026-09-12:「支持点击 标签 工作室 类型 的跳转」】。
                       分级和完结状态不给点:它们不是「能按它列一串」的维度。
                       工作室带的是 **id 不是名字** —— 按名字筛在真 Emby 上是假的。 */
                    val jumps = d.strList("genres").take(4).map { Triple("genre", it, it) } +
                        d.strList("tags").take(6).map { Triple("tag", it, it) } +
                        d.namedList("studios").take(3).map { Triple("studio", it.second, it.first) }
                    val plain = listOfNotNull(d.str("official_rating"), d.str("status"))
                    DetailReveal(if (d == null) null else jumps to plain, Modifier.testTag("detail.tags")) { current ->
                        if (current == null) Spacer(Modifier.height(with(LocalDensity.current) { 24.sp.toDp() } + Sp.x6 + Sp.x12))
                        else if (current.first.isNotEmpty() || current.second.isNotEmpty()) Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(start = Sp.x16, end = Sp.x16, top = Sp.x12),
                            horizontalArrangement = Arrangement.spacedBy(Sp.x6),
                        ) {
                            current.first.forEach { (kind, value, label) ->
                                if (live == null || current != (jumps to plain) ||
                                    live.get("capabilities").obj()?.get("filters")?.toString() == "false") Tag(label)
                                else Tag(label) { nav.navigate(Route.Facet(kind, value, label)) }
                            }
                            current.second.forEach { Tag(it) }
                        }
                    }
                }

                /* 播放选项:**版本 / 线路 / 音轨 / 字幕**【用户点名要的四项】。
                   剧集页不画 —— 那是整部剧,选版本没有意义;进到某一集里才有。 */
                if (!isSeries && !isBoxSet) item("pick") {
                    AnimatedContent(media,
                        modifier = Modifier.fillMaxWidth().testTag("detail.options"),
                        contentKey = { it::class },
                        transitionSpec = {
                            (fadeIn(contentFade) togetherWith fadeOut(contentFade))
                                .using(SizeTransform { _, _ -> contentSize })
                        }, label = "detailOptions") { state ->
                        when (state) {
                            Block.Loading -> LoadingState()
                            is Block.Fail -> if (!state.isSilent)
                                xyz.linplayer.app.ui.components.ErrorState("播放选项读取失败：${state.message}", { mediaRetry++ })
                            is Block.Ok -> {
                                val video = ver?.of("Video")?.firstOrNull()
                                val audio = selectedTrack(ver?.of("Audio"), audioLang)
                                PickList(buildList {
                                    if (ver != null) add(PickRow("版本", ver.displayName, LpIcons.version,
                                        onClick = if (versions.size > 1) ({ sheet = "version" }) else null,
                                        metadata = listOfNotNull(ver.container?.uppercase(), fmtSize(ver.sizeBytes)).joinToString(" · ")))
                                    video?.let {
                                        val value = videoSummary(it)
                                        if (value.isNotBlank()) add(PickRow("视频", value, LpIcons.video))
                                    }
                                    audio?.let {
                                        val summary = audioSummary(it)
                                        add(PickRow("音轨", summary, LpIcons.musicNote,
                                            onClick = if ((ver?.of("Audio")?.size ?: 0) > 1) ({ sheet = "audio" }) else null,
                                            subtitle = trackSubtitle(it, summary)))
                                    }
                                    if (!ver?.of("Subtitle").isNullOrEmpty()) {
                                        val subtitle = if (subsEnabled) selectedTrack(ver.of("Subtitle"), subLang, subRegex) else null
                                        val summary = if (!subsEnabled) "关闭" else subtitle?.let(::subtitleSummary) ?: "无"
                                        add(PickRow("字幕", summary, LpIcons.sub, onClick = { sheet = "sub" },
                                            subtitle = subtitle?.let { trackSubtitle(it, summary) }))
                                    }
                                    serverId?.takeIf { lineCount > 1 }?.let { sid ->
                                        add(PickRow("线路", lineLabel ?: "线路", LpIcons.line,
                                            // 线路页认的是账号 ID，换过线路后的会话地址不能代替它。
                                            onClick = { nav.navigate(Route.Lines(sid, title)) }))
                                    }
                                })
                            }
                        }
                    }
                }

                item("actions") { Anchored(PluginAnchors.DETAIL_ACTIONS, Modifier.padding(horizontal = Sp.x16)) {
                    // 合集不是一部片,没有播放按钮 —— 但锚点位照留,插件还能往这儿挂
                    if (isBoxSet) return@Anchored
                    val target = if (isSeries) nextEp?.id ?: route.itemId else route.itemId
                    val resume = if (isSeries) nextEp?.resumeSecs ?: 0.0 else live.dbl("resume_secs") ?: 0.0
                    val runtime = if (isSeries) nextEp?.runtimeSecs ?: 0.0 else d.dbl("runtime_secs") ?: 0.0
                    val continuing = resume > 0 && runtime > resume && (!isSeries || nextEp?.played != true)
                    Column(Modifier.padding(top = Sp.x16)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Sp.x16).testTag("detail.actions.layout")
                                .then(when {
                                    isSeriesEntry -> Modifier.heightIn(min = 52.dp)
                                    isSeries -> Modifier.heightIn(min = with(LocalDensity.current) { 60.sp.toDp() } + Sp.x20)
                                    else -> Modifier
                                }),
                            horizontalArrangement = Arrangement.spacedBy(Sp.x10),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val label = when {
                                isSeriesEntry -> if (continuing) "继续观看" else "播放"
                                isSeries && nextEp != null ->
                                    "${if (continuing) "继续" else "播放"} S${nextEp.seasonNo ?: 1}E${nextEp.episodeNo ?: 1}"
                                continuing -> "继续观看"
                                else -> "播放"
                            } + if (continuing && !isSeriesEntry) "（剩余：${fmtDur(runtime - resume)}）" else ""
                            Box(
                                (if (continuing || isSeriesEntry) Modifier.weight(1f) else Modifier.width(132.dp)).height(IntrinsicSize.Min)
                                    .clip(RoundedCornerShape(R.md))
                                    .background(if (continuing) c.s2 else c.mediaAccent)
                                    .testTag("detail.play")
                                    .semantics {
                                        if (continuing) progressBarRangeInfo = ProgressBarRangeInfo(
                                            (resume / runtime).toFloat().coerceIn(0f, 1f), 0f..1f)
                                    }
                                    .pressable({ toPlayer(target, null, false) },
                                        { toPlayer(target, xyz.linplayer.app.data.UiPrefs.otherEngine(), false) }),
                            ) {
                                if (continuing) Box(Modifier.matchParentSize()) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth((resume / runtime).toFloat().coerceIn(0f, 1f))
                                        .background(c.mediaAccent.copy(alpha = .35f)))
                                }
                                Row(Modifier.fillMaxWidth().heightIn(min = if (isSeriesEntry) 52.dp else 48.dp).padding(horizontal = Sp.x12, vertical = Sp.x10),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Sp.x8, Alignment.CenterHorizontally)) {
                                    Icon(LpIcons.play, null, Modifier.size(18.dp),
                                        tint = if (continuing) c.fg else c.mediaOnAccent)
                                    Text(label, if (continuing) Modifier.weight(1f) else Modifier, style = LpText.action,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (continuing) c.fg else c.mediaOnAccent)
                                }
                            }
                            var menuOpen by remember { mutableStateOf(false) }
                            val menuOffset = with(LocalDensity.current) { 52.dp.roundToPx() }
                            Box {
                                Box(
                                    Modifier.size(if (isSeriesEntry) 52.dp else 48.dp).clip(RoundedCornerShape(R.md))
                                        .background(c.mediaAccent).pressable({ menuOpen = !menuOpen }),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(LpIcons.chevD, "播放更多操作",
                                        Modifier.size(22.dp).graphicsLayer { rotationZ = if (menuOpen) 180f else 0f }, tint = c.mediaOnAccent)
                                }
                                LpMenu(menuOpen, { menuOpen = false }, alignment = Alignment.TopEnd,
                                    offset = IntOffset(0, menuOffset), solid = true) {
                                    LpMenuItem("从头开始播放", {
                                        menuOpen = false
                                        toPlayer(target, null, true)
                                    })
                                }
                            }
                        }
                        if (isSeriesEntry) Box(
                            Modifier.fillMaxWidth().padding(horizontal = Sp.x16, vertical = Sp.x8)
                                .heightIn(min = bodyHeight)
                                .testTag("detail.series.target"),
                        ) {
                            nextEp?.let { ep -> Text(
                                listOfNotNull(ep.seasonNo?.let { "S$it" }, ep.episodeNo?.let { "E$it" })
                                    .joinToString("").let { number ->
                                        listOf(number, ep.name).filter(String::isNotBlank).joinToString("：")
                                    },
                                color = c.fg2, style = LpText.body,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            ) }
                        }
                    }
                } }

                if (isEpisode || isSeriesEntry) item("overview") { Anchored(PluginAnchors.DETAIL_OVERVIEW, Modifier.padding(horizontal = Sp.x16)) {
                    DetailReveal(d.str("overview")?.takeIf { it.isNotBlank() }, Modifier.testTag("detail.overview")) { ov ->
                        ov?.let { Overview(it) }
                    }
                } }

                // 剧集入口的季海报位于分集之后；其它详情继续保留季插件插入位。
                if (!isSeriesEntry) item("seasons") { Anchored(PluginAnchors.DETAIL_SEASONS, Modifier.padding(horizontal = Sp.x16)) {} }
                // 换季仍复用同一请求链和滚动状态。
                item("episodes") { Anchored(PluginAnchors.DETAIL_EPISODES, Modifier.padding(horizontal = Sp.x16)) {
                    if (!isSeries && !isEpisode) return@Anchored
                    var seasonsOpen by remember { mutableStateOf(false) }
                    val menuOffset = with(LocalDensity.current) { 48.dp.roundToPx() }
                    Column(Modifier.fillMaxWidth().testTag("detail.episodes")) {
                        Column(Modifier.padding(horizontal = Sp.x16, vertical = Sp.x12)) {
                            Box {
                                Row(Modifier.then(if (isSeriesEntry) Modifier.fillMaxWidth() else Modifier).heightIn(min = 48.dp)
                                    .then(if (curSeason != null && seasons.size > 1) Modifier.pressable({ seasonsOpen = !seasonsOpen }) else Modifier),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
                                    Text(if (isSeriesEntry) "继续观看" else curSeason?.let { seasonLabel(it) } ?: "选集",
                                        color = c.fg, style = LpText.title)
                                    if (isSeriesEntry) {
                                        curSeason?.let { Text(seasonLabel(it), Modifier.weight(1f),
                                            color = c.mediaIcon, style = LpText.compact,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                            ?: Spacer(Modifier.weight(1f))
                                    }
                                    if (curSeason != null && seasons.size > 1) Icon(LpIcons.chevD, "选择季", Modifier.size(20.dp), tint = c.fg2)
                                }
                                LpMenu(seasonsOpen, { seasonsOpen = false }, offset = IntOffset(0, menuOffset), solid = true) {
                                    seasons.forEach { season ->
                                        LpMenuItem(seasonLabel(season), {
                                            seasonsOpen = false
                                            if (curSeason?.id != season.id) {
                                                curSeason = season
                                                episodeRequest++
                                            }
                                        }, selected = season.id == curSeason?.id)
                                    }
                                }
                            }
                            Dim3(when {
                                seasonLoad is Block.Loading -> "正在读取选集…"
                                curSeason != null && episodeLoad is Block.Loading -> "正在加载分集…" +
                                    if (episodes.isNotEmpty()) " 已加载 ${episodes.size}" + (episodeTotal?.let { " / $it" } ?: "") else ""
                                curSeason != null && episodeLoad is Block.Ok -> "已看 ${episodes.count { it.played }} / ${episodes.size}"
                                else -> "选集信息"
                            }, Modifier.height(with(LocalDensity.current) { 16.sp.toDp() }))
                        }
                        if (episodes.isNotEmpty()) key(curSeason?.id) {
                            EpisodeStrip(app, episodes, currentId = route.itemId, targetId = nextEp?.id.takeIf { isSeries },
                                expanded = isSeriesEntry,
                                onOpen = { ep -> nav.navigate(Route.Detail(ep.id, "Episode")) })
                        } else Box(Modifier.fillMaxWidth().height(episodeStripHeight(isSeriesEntry)), contentAlignment = Alignment.Center) {
                            when {
                                seasonLoad is Block.Fail -> xyz.linplayer.app.ui.components.ErrorState(
                                    (seasonLoad as Block.Fail).message, { seasonRetry++ })
                                curSeason != null && episodeLoad is Block.Fail -> xyz.linplayer.app.ui.components.ErrorState(
                                    (episodeLoad as Block.Fail).message, { episodeRetry++ })
                                seasonLoad is Block.Loading || (curSeason != null && episodeLoad is Block.Loading) ->
                                    LoadingState(Modifier.fillMaxSize())
                                else -> Dim3(if (curSeason == null) "暂无可用选集" else "本季暂无分集")
                            }
                        }
                        if (episodes.isNotEmpty() && episodeLoad is Block.Fail)
                            xyz.linplayer.app.ui.components.ErrorState((episodeLoad as Block.Fail).message, { episodeRetry++ })
                    }
                } }

                if (isSeriesEntry) item("seasons") { Anchored(PluginAnchors.DETAIL_SEASONS, Modifier.padding(horizontal = Sp.x16)) {
                    Column(Modifier.fillMaxWidth().testTag("detail.seasons")) {
                        SectionTitle("季", accent = c.mediaIcon)
                        val seasonHeight = with(LocalDensity.current) { 156.dp + Sp.x6 + LpText.compact.lineHeight.toDp() * 2 }
                        if (seasons.isNotEmpty()) LazyRow(
                            Modifier.fillMaxWidth().height(seasonHeight),
                            contentPadding = PaddingValues(horizontal = Sp.x16),
                            horizontalArrangement = Arrangement.spacedBy(Sp.x12),
                        ) {
                            itemsIndexed(seasons, key = { _, season -> season.id }) { _, season ->
                                Column(Modifier.width(104.dp).pressable({
                                    if (curSeason?.id != season.id) {
                                        curSeason = season
                                        episodeRequest++
                                    }
                                }).semantics { contentDescription = "查看${seasonLabel(season)}分集" }) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(R.md))
                                        .then(if (season.id == curSeason?.id)
                                            Modifier.border(2.dp, c.mediaIcon, RoundedCornerShape(R.md)) else Modifier)) {
                                        NetImage(if (season.id in seasonImages) app.imageUrl(season.id, "Primary", 330) else null,
                                            null, Modifier.fillMaxSize(), R.md)
                                    }
                                    Spacer(Modifier.height(Sp.x6))
                                    Text(seasonLabel(season), color = if (season.id == curSeason?.id) c.mediaIcon else c.fg,
                                        style = LpText.compact, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        } else if (seasonLoad is Block.Loading) LoadingState(Modifier.fillMaxWidth().height(seasonHeight))
                    }
                } }

                /* 合集的成员。**影片和剧集分成两段**(用户 2026-09-08:
                   「合集要把影片和剧集分开,方便用户查找」)。
                   分堆在核心层做(`emby.collectionItems`)—— 两端各分一次的话,
                   迟早在「其它类型往哪儿归」上分叉,而那种不一致没人会报上来。 */
                if (isBoxSet) {
                    if (boxLoading) item("boxbusy") { SectionTitle("正在取合集内容…", accent = Lp.colors.mediaIcon) }
                    else if (boxMovies.isEmpty() && boxSeries.isEmpty() && boxOthers.isEmpty()) {
                        // 说清是「空的」而不是「没拉到」。空着的话和还在加载长得一样。
                        item("boxempty") { SectionTitle("这个合集里没有内容", accent = Lp.colors.mediaIcon) }
                    }
                    listOf("影片" to boxMovies, "剧集" to boxSeries, "其它" to boxOthers)
                        .forEach { (label, list2) ->
                            if (list2.isEmpty()) return@forEach   // 一部都没有就整段不画
                            item("box-$label") {
                                // 用全站那条横滑轨道,不另搭一套(高度是常量,见 LpRow 的注释)
                                LpRow(
                                    "$label · ${list2.size}", list2,
                                    { app.imageUrl(it.id, "Primary", 330) },
                                    { it2 -> nav.navigate(
                                        Route.Detail(it2.id, it2.type.ifEmpty { "Movie" })) },
                                )
                            }
                        }
                }

                if (!isEpisode && !isSeriesEntry) item("overview") { Anchored(PluginAnchors.DETAIL_OVERVIEW, Modifier.padding(horizontal = Sp.x16)) {
                    DetailReveal(d.str("overview")?.takeIf { it.isNotBlank() }, Modifier.testTag("detail.overview")) { ov ->
                        ov?.let { Overview(it) }
                    }
                } }

                item("people") { Anchored(PluginAnchors.DETAIL_CAST, Modifier.padding(horizontal = Sp.x16)) {
                    DetailReveal(people, Modifier.testTag("detail.people")) { current ->
                        if (current.isNotEmpty()) People(app, current)
                    }
                } }

                // 媒体信息:**照 Emby 官端分组成卡**,不是一张 kv 大表
                if (!isSeries && !isBoxSet) item("media") {
                    AnimatedContent(ver, modifier = Modifier.fillMaxWidth(), contentKey = { it?.id }, transitionSpec = {
                        (fadeIn(contentFade) togetherWith fadeOut(contentFade))
                            .using(SizeTransform { _, _ -> contentSize })
                    }, label = "detailMedia") { current ->
                        Column {
                            if (current != null) {
                                SectionTitle("媒体信息", accent = Lp.colors.mediaIcon)
                                MediaCards(current)
                            }
                        }
                    }
                }

                /* 季 / 集**两条横滑栏**(照 PC 端)【用户定 2026-09-06】。
                   ★ 换季那一栏一动,下面的集数栏当场跟着换 —— 两栏是一条链,不是两个列表。
                   ★ 点一集进的是**这一集的详情页**,不是直接起播:起播是详情页里那颗大按钮。
                     上一版是一条竖着的长列表,把整页撑得看不到下面的相似推荐。 */
                item("similar") { Anchored(PluginAnchors.DETAIL_SIMILAR, Modifier.padding(horizontal = Sp.x16)) {
                    if (similar.isEmpty()) return@Anchored
                    LpRow("相似推荐", similar, { app.imageUrl(it.id, "Primary", 330) },
                        { nav.navigate(Route.Detail(it.id, it.type)) },
                        menu = { cardActions(app, scope, it) })
                } }

                item("tail") { Anchored(PluginAnchors.DETAIL_FOOTER, Modifier.padding(horizontal = Sp.x16)) { Spacer(Modifier.height(Sp.x26)) } }
            }
        }
        }
    }

    /* ── 选择弹窗。**全站没有 bottom sheet**,一律居中弹窗 ── */
    when (sheet) {
        "version" -> LpDialog({ sheet = null }, "选择版本") {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                versions.forEach { v ->
                    OptRow(
                        v.displayName, { pickedVersion = v.id; sheet = null },
                        media = true, labelMaxLines = Int.MAX_VALUE,
                        sub = listOfNotNull(v.container?.uppercase(), fmtSize(v.sizeBytes),
                            fmtRate(v.bitrate)).joinToString(" · ").takeIf { it.isNotEmpty() },
                        selected = v.id == ver?.id,
                        badge = if (v.preferred) "正则选中" else null,
                    )
                }
            }
        }
        "audio" -> LangDialog(
            "首选音轨语言", ver?.of("Audio").orEmpty(), audioLang,
            onPick = { lang ->
                audioLang = lang; sheet = null
                // ☠ **两项必须一起发**:核心层的 setPrefs 是无条件覆盖,
                //   只发 audio_lang 会把 sub_lang 清成 null
                scope.launch { savePrefs(app, lang, subLang) }
            },
            onDismiss = { sheet = null },
        )
        "sub" -> SubDialog(
            ver?.of("Subtitle").orEmpty(), subLang,
            onPick = { lang ->
                subLang = lang; subsEnabled = lang != ""; sheet = null
                scope.launch { savePrefs(app, audioLang, lang) }
            },
            onDismiss = { sheet = null },
        )

    }
}

private suspend fun savePrefs(app: xyz.linplayer.app.data.AppState, audio: String?, sub: String?) {
    runCatching {
        app.call("prefs.setPrefs", JsonObject(buildMap {
            put("audio_lang", kotlinx.serialization.json.JsonPrimitive(audio ?: ""))
            put("sub_lang", kotlinx.serialization.json.JsonPrimitive(sub ?: ""))
            put("sub_enabled", kotlinx.serialization.json.JsonPrimitive(sub != ""))
        }))
    }.onFailure { app.report(it) }
}

/* ───────────────────────────── 头部 ───────────────────────────── */

/**
 * 剧 / 影头部(草稿 03)。
 *
 * ★ 背景渐隐到正文，标题占整行，不再在标题前重复放一张小海报。
 * ★ 标题拆两级:眉标 → 主名 25sp → 副名 16sp 半透明。
 *   「鬼灭之刃 无限城篇」挤在一行是上一稿最明显的塌陷点。
 */
@Composable
private fun SeriesHead(
    app: xyz.linplayer.app.data.AppState,
    id: String,
    d: JsonObject?,
    list: androidx.compose.foundation.lazy.LazyListState,
    seriesEntry: Boolean,
) {
    val c = Lp.colors
    val preview = detailPreview(id).takeIf { d == null }
    var backdropFailed by remember(id) { mutableStateOf(false) }
    val useBackdrop = d != null && d.boolOrNull("has_backdrop") != false && !backdropFailed
    val imageKind = if (useBackdrop) "Backdrop" else "Primary"
    val imageUrl = if (!useBackdrop && d.boolOrNull("has_primary") == false) null
        else app.imageUrl(id, imageKind, 720)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = maxWidth * 1.08f).testTag("detail.hero")) {
            Box(
                Modifier.matchParentSize()
                    // 视差:图跟着滚一半 —— 海报和文字按正常速度走,两层错开才有纵深
                    .graphicsLayer {
                        val off = if (list.firstVisibleItemIndex == 0)
                            list.firstVisibleItemScrollOffset.toFloat() else size.height
                        translationY = off * 0.35f
                    }
                    .dissolve(0.64f, 0.99f)
            ) {
                NetImage(imageUrl, null, Modifier.fillMaxSize().testTag("detail.hero.image"), 0.dp,
                    backgroundBlur = true, previewUrl = posterPreview(id),
                    onLoadResult = { loaded -> if (!loaded && useBackdrop) backdropFailed = true })
                // 顶上压一层:给浮在图上的状态栏和返回键留可读性。**不是给它留黑底**
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.00f to Color.Black.copy(alpha = .52f),
                            0.30f to Color.Transparent,
                            0.78f to c.bg.copy(alpha = .34f),
                            1.00f to c.bg.copy(alpha = .72f),
                        )
                    )
                )
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(
                    start = Sp.x16, end = Sp.x16,
                    top = Dim.topBar + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                ),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f).padding(bottom = Sp.x6)) {
                    if (!seriesEntry) {
                        Kicker(kickerOf(d.str("type_") ?: preview?.type), color = c.fg2)
                        Spacer(Modifier.height(Sp.x4))
                    }
                    Text(
                        d.str("name") ?: preview?.name ?: "", color = c.fg, style = LpText.hero,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    // 未知标语仅留一行空间，确认没有后收起；不让晚到文字突然抬高标题。
                    DetailReveal(d?.let { it.str("tagline")?.takeIf(String::isNotBlank) ?: "" },
                        Modifier.testTag("detail.tagline")) { tagline ->
                        if (tagline == null) Spacer(Modifier.height(with(LocalDensity.current) { 24.sp.toDp() } + 3.dp))
                        else if (tagline.isNotEmpty()) {
                            Spacer(Modifier.height(3.dp))
                            Text(tagline, color = c.fg.copy(alpha = .82f), style = LpText.section.copy(fontWeight = FontWeight.Normal),
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 集头部(草稿 04)。
 *
 * ★ **封面上没有播放键**【用户定 2026-09-06】—— 下面那颗宽按钮就是播放,
 *   在封面上再来一个是同一件事写两遍。那块地方改放标题。
 */
@Composable
private fun EpisodeHead(
    app: xyz.linplayer.app.data.AppState,
    id: String,
    d: JsonObject?,
    list: androidx.compose.foundation.lazy.LazyListState,
    onSeries: () -> Unit,
) {
    val c = Lp.colors
    val preview = detailPreview(id).takeIf { d == null }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = maxWidth * 1.08f).testTag("detail.hero")) {
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer {
                        val off = if (list.firstVisibleItemIndex == 0)
                            list.firstVisibleItemScrollOffset.toFloat() else size.height
                        translationY = off * 0.35f
                    }
                    .dissolve(0.64f, 0.99f)
            ) {
                NetImage(if (d.boolOrNull("has_primary") == false) null else app.imageUrl(id, "Primary", 480),
                    null, Modifier.fillMaxSize().sharedPoster(id), 0.dp, backgroundBlur = true, previewUrl = posterPreview(id))
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.00f to Color.Black.copy(alpha = .52f),
                            0.34f to Color.Transparent,
                            0.78f to c.bg.copy(alpha = .34f),
                            1.00f to c.bg.copy(alpha = .72f),
                        )
                    )
                )
            }
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .padding(start = Sp.x16, end = Sp.x16, bottom = Sp.x12,
                        top = Dim.topBar + WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
            ) {
                /* 剧名**点得动**:Emby 上点集详情页的剧名就回到剧集主页,我们只把它
                   当一行说明文字画着(用户 2026-09-11)。从某一集想回到整部剧,
                   原来只能一路按返回。
                   ★ 刮削不全的库拿不到 series_id —— 那时候不加下划线也不给点,
                     摆一个点了没反应的链接比没有更糟。 */
                (d.str("series_name") ?: preview?.seriesName)?.let {
                    val linked = !d.str("series_id").isNullOrBlank()
                    Text(
                        it,
                        if (linked) Modifier.clickable(onClick = onSeries) else Modifier,
                        color = c.fg, style = LpText.hero, maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(Sp.x12))
                val number = listOfNotNull(
                    (d.long("season_no") ?: preview?.seasonNo)?.let { "S$it" }, (d.long("episode_no") ?: preview?.episodeNo)?.let { "E$it" },
                ).joinToString("")
                Text(
                    listOf(number, d.str("name") ?: preview?.name ?: "").filter { it.isNotBlank() }.joinToString("："),
                    color = c.fg, style = LpText.headline,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun kickerOf(type: String?): String = when (type) {
    "Series" -> "剧集"
    "Season" -> "季"
    "Movie" -> "电影"
    "Episode" -> "分集"
    else -> "条目"
}

/* ───────────────────────────── 区块 ───────────────────────────── */

/** 仅对本区展示值过渡，缓存首帧直接呈现，相同资料刷新不重播。 */
@Composable
private fun <Value> DetailReveal(value: Value, modifier: Modifier = Modifier, content: @Composable (Value) -> Unit) {
    val fade = lpTween<Float>(T.T8, LinearEasing)
    val size = lpTween<IntSize>(T.T6)
    AnimatedContent(value, modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart,
        transitionSpec = {
            (fadeIn(fade) togetherWith fadeOut(fade)).using(SizeTransform { _, _ -> size })
        }, label = "detailReveal") { current ->
        Column(Modifier.fillMaxWidth()) { content(current) }
    }
}

@Composable
private fun Tag(text: String, onClick: (() -> Unit)? = null) {
    val c = Lp.colors
    // 能点的和不能点的**长得不一样**:一排里有的能点有的不能点,
    // 外观一致的话用户只能靠试
    Text(
        text,
        Modifier.clip(RoundedCornerShape(R.pill))
            .background(if (onClick != null) c.mediaAccent.copy(alpha = .18f) else c.s1)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = Sp.x10, vertical = 3.dp),
        color = if (onClick != null) c.mediaIcon else c.fg.copy(alpha = .86f),
        style = LpText.badge, maxLines = 1,
    )
}

/** 简介直接铺正文，点击文字展开/收起，不额外占用一行标题。 */
@Composable
private fun Overview(text: String) {
    var expand by remember(text) { mutableStateOf(false) }
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = Sp.x16, vertical = Sp.x20)
            .semantics { contentDescription = if (expand) "收起简介" else "展开简介" }
            .pressable({ expand = !expand }),
        color = Lp.colors.fg2, style = LpText.body.copy(lineHeight = 22.sp),
        maxLines = if (expand) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun People(app: xyz.linplayer.app.data.AppState, people: List<Person>) {
    val c = Lp.colors
    SectionTitle("演员", accent = Lp.colors.mediaIcon)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = Sp.x16),
        horizontalArrangement = Arrangement.spacedBy(Sp.x12),
    ) {
        people.take(20).forEach { p ->
            Column(Modifier.width(54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                NetImage(app.imageUrl(p.id, "Primary", 120), p.name, Modifier.size(54.dp), R.pill)
                Spacer(Modifier.height(5.dp))
                Text(p.name, color = c.fg2, style = LpText.caption, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                p.role?.let { Text(it, Modifier.padding(top = Sp.x2), color = c.fg3,
                    style = LpText.badge.copy(lineHeight = 16.sp, fontWeight = FontWeight.Normal), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
        }
    }
}

/** 季号来自接口；默认季名不重复，自定义名称保留。 */
internal fun seasonLabel(season: Item): String {
    val number = season.seasonNo ?: return season.name.replace(Regex("第\\s*(\\d+)\\s*季"), "第$1季")
    val base = "第${number}季"
    val name = season.name.trim()
    return if (name.isEmpty() || Regex("第\\s*${number}\\s*季|Season\\s*${number}", RegexOption.IGNORE_CASE).matches(name)) base
    else "$base：$name"
}

/**
 * 一行播放选项。
 * ★ `onClick` 为 null = **这一行只是在告诉你会用哪个**,没有别的可选。
 *   照样画一个箭头、点了没反应,是界面在撒谎 —— 只有一个选项的选择器本来就不该是选择器。
 */
private data class PickRow(
    val label: String, val value: String, val icon: ImageVector,
    val onClick: (() -> Unit)? = null,
    val subtitle: String? = null, val metadata: String? = null,
)

/** 版本用整行浮动标题描边框，视频标签与音轨/字幕选择独立展示。 */
@Composable
private fun PickList(rows: List<PickRow>) {
    val c = Lp.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = Sp.x8),
        verticalArrangement = Arrangement.spacedBy(Sp.x8)) {
        rows.forEach { r ->
            if (r.label == "版本") {
                var labelSize by remember { mutableStateOf(IntSize.Zero) }
                val labelOffset = with(LocalDensity.current) { LpText.caption.lineHeight.toDp() / 2 }
                Box(Modifier.fillMaxWidth().padding(top = Sp.x8)
                    .semantics { contentDescription = r.label }
                    .then(r.onClick?.let { Modifier.pressable(it) } ?: Modifier)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .drawBehind {
                            val stroke = 1.dp.toPx()
                            // 给浮动标题留出描边缺口，不用色块遮盖详情的取色背景。
                            clipRect(left = Sp.x12.toPx(), top = 0f,
                                right = Sp.x12.toPx() + labelSize.width, bottom = labelSize.height / 2f,
                                clipOp = ClipOp.Difference) {
                                drawRoundRect(c.line2, topLeft = Offset(stroke / 2, stroke / 2),
                                    size = Size(size.width - stroke, size.height - stroke),
                                    cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(stroke))
                            }
                        }
                        .padding(horizontal = Sp.x12, vertical = Sp.x16),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Sp.x12)) {
                        Text(r.value, Modifier.weight(1f), color = c.fg, style = LpText.list)
                        if (r.onClick != null) Icon(LpIcons.chevD, null, Modifier.size(16.dp), tint = c.fg2)
                    }
                    Text(listOfNotNull("版本", r.metadata?.takeIf(String::isNotBlank)).joinToString(" · "),
                        Modifier.padding(end = Sp.x12).offset(x = Sp.x12, y = -labelOffset)
                            .padding(horizontal = Sp.x4).onSizeChanged { labelSize = it },
                        color = c.fg3, style = LpText.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Row(
                    Modifier.widthIn(min = 112.dp).heightIn(min = when {
                        r.label == "视频" -> 32.dp
                        r.onClick != null -> 48.dp
                        else -> 40.dp
                    })
                        .clip(RoundedCornerShape(R.sm))
                        .background(when (r.label) {
                            "音轨" -> c.mediaPanel
                            "字幕" -> c.fg2.copy(alpha = .16f)
                            else -> Color.Transparent
                        })
                        .border(1.dp, c.line2, RoundedCornerShape(R.sm))
                        .semantics { contentDescription = r.label }
                        .then(r.onClick?.let { Modifier.pressable(it) } ?: Modifier)
                        .padding(horizontal = Sp.x12, vertical = Sp.x6),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Sp.x10),
                ) {
                    Icon(r.icon, null, Modifier.size(18.dp), tint = c.fg)
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(r.value, color = c.fg, style = LpText.body)
                        r.subtitle?.let { Text(it, color = c.fg2, style = LpText.secondary) }
                    }
                    if (r.onClick != null) Icon(LpIcons.chevR, null, Modifier.size(16.dp), tint = c.fg3)
                }
            }
        }
    }
}

/** 文件信息在上，视频/音频/字幕每条轨道独立一张横滑卡。 */
@Composable
private fun MediaCards(v: Version) {
    val c = Lp.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Sp.x16),
        verticalArrangement = Arrangement.spacedBy(Sp.x6)) {
        displayMediaPath(v.path)?.let {
            Text(it, color = c.fg, style = LpText.body)
        }
        val summary = listOfNotNull(
            v.container?.uppercase(), fmtSize(v.sizeBytes),
            addedTime(v.dateCreated)?.let { "添加于: $it" },
        ).joinToString("  ")
        if (summary.isNotBlank()) Text(summary, color = c.fg2, style = LpText.secondary)
    }
    if (v.streams.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(top = Sp.x12, bottom = Sp.x16)
            .height(IntrinsicSize.Min).horizontalScroll(rememberScrollState())
            .padding(horizontal = Sp.x16).testTag("detail.media.tracks"),
        horizontalArrangement = Arrangement.spacedBy(Sp.x10),
    ) {
        v.streams.forEach { stream ->
            Layer(Modifier.width(190.dp).fillMaxHeight(), corner = R.lg) {
                Column(Modifier.padding(Sp.x12)) {
                    Icon(when (stream.type) {
                        "Audio" -> LpIcons.audio
                        "Subtitle" -> LpIcons.sub
                        else -> LpIcons.version
                    }, null, Modifier.padding(bottom = Sp.x8).size(24.dp), tint = c.fg2)
                    val rows = listOfNotNull(
                        "类型" to stream.type,
                        "编号" to stream.index.toString(),
                        stream.display?.let { "显示标题" to it },
                        stream.title?.takeIf { it != stream.display }?.let { "标题" to it },
                        stream.lang?.let { "语言" to it },
                        stream.codec.takeIf { it.isNotBlank() }?.let { "编码器" to it },
                        stream.width?.let { w -> stream.height?.let { h -> "分辨率" to "$w × $h" } },
                        stream.fps?.takeIf { it > 0 }?.let { "帧率" to "%.3f".format(Locale.ROOT, it).trimEnd('0').trimEnd('.') },
                        stream.range?.let { "动态范围" to it },
                        stream.profile?.let { "配置" to it },
                        fmtRate(stream.bitrate)?.let { "比特率" to it },
                        stream.bitDepth?.let { "位深" to it.toString() },
                        stream.colorSpace?.let { "色域" to it },
                        stream.pixelFormat?.let { "像素格式" to it },
                        stream.layout?.let { "声道" to it } ?: stream.channels?.let { "声道数" to it.toString() },
                        "默认" to stream.isDefault.toString(),
                        stream.isForced?.let { "强制" to it.toString() },
                        "外部" to stream.isExternal.toString(),
                    )
                    rows.forEach { (label, value) ->
                        Text("$label: $value", color = c.fg2, style = LpText.caption)
                    }
                }
            }
        }
    }
}

/** 远程源只展示路径部分，不能把播放地址中的查询令牌或用户信息画到媒体信息里。 */
internal fun displayMediaPath(path: String?): String? = path?.takeIf { it.isNotBlank() }?.let {
    val uri = Uri.parse(if (it.startsWith("//")) "https:$it" else it)
    if ((uri.scheme == null && uri.authority == null) ||
        (uri.scheme?.length == 1 && it.getOrNull(1) == ':')) it
    else uri.path?.takeIf { value -> value.isNotBlank() }
}

/** 添加时间是时间戳，按设备时区展示；元数据日期仍由 detailDate 原样格式化。 */
private fun addedTime(raw: String?): String? = raw?.let {
    runCatching {
        val normalized = it.replace(Regex("\\.\\d+(?=Z|[+-]\\d{2}:\\d{2}$)"), "")
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
            .apply { isLenient = false }.parse(normalized)
        date?.let { value -> SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ROOT).format(value) }
    }.getOrNull()
}

/**
 * 集数栏:**一条横滑的卡带**(草稿 03 的 `.eplist` 横过来)。
 *
 * ★ 高度按 sp 现算,不写死 dp —— 系统字号放大时写死的 dp 会把集名裁掉半行
 *   (和首页那两条轨道同一个坑,见 `Cards.kt` 的 `rowHeight`)。
 * ★ 当前单集与待播放目标用文字区分，描边和集号颜色辅助定位。
 * ★ 文字和封面区域立即占位，图片自行渐显，不对整卡错峰隐藏。
 */
@Composable
private fun EpisodeStrip(
    app: xyz.linplayer.app.data.AppState,
    episodes: List<Item>,
    currentId: String,
    targetId: String?,
    expanded: Boolean = false,
    onOpen: (Item) -> Unit,
) {
    val state = rememberLazyListState()
    var positioned by rememberSaveable(currentId) { mutableStateOf(false) }
    val currentIndex = episodes.indexOfFirst { it.id == currentId }
    LaunchedEffect(currentId, currentIndex) {
        if (!positioned && currentIndex >= 0) {
            state.scrollToItem(currentIndex)
            positioned = true
        }
    }
    LazyRow(
        Modifier.fillMaxWidth().height(episodeStripHeight(expanded)),
        state = state,
        contentPadding = PaddingValues(horizontal = Sp.x16),
        horizontalArrangement = Arrangement.spacedBy(Sp.x10),
    ) {
        itemsIndexed(episodes, key = { _, e -> e.id }, contentType = { _, _ -> "ep" }) { i, ep ->
            EpCard(app, ep, i, ep.id == currentId, ep.id == targetId, expanded) { onOpen(ep) }
        }
    }
}

/** 等待和真实分集轨道共用几何，数据到达不挤动下面的简介与演员。 */
@Composable
private fun episodeStripHeight(expanded: Boolean = false) = with(LocalDensity.current) {
    if (expanded) return@with SeriesEpCardW * 9 / 16 + Sp.x6 + LpText.body.lineHeight.toDp() * 2 +
        LpText.caption.lineHeight.toDp() + Sp.x8
    EpCardW * 9 / 16 + Sp.x6 + LpText.caption.lineHeight.toDp() + LpText.compact.lineHeight.toDp() * 2 + Sp.x8
}

private val EpCardW = 168.dp
private val SeriesEpCardW = 224.dp

@Composable
private fun EpCard(
    app: xyz.linplayer.app.data.AppState,
    ep: Item,
    index: Int,
    current: Boolean,
    target: Boolean,
    expanded: Boolean,
    onOpen: () -> Unit,
) {
    val c = Lp.colors
    Column(
        Modifier.width(if (expanded) SeriesEpCardW else EpCardW).testTag("detail.ep.${ep.id}").pressable(onOpen)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (current || target) Modifier.border(2.dp, c.mediaIcon, RoundedCornerShape(12.dp))
                    else Modifier
                )
        ) {
            NetImage(app.imageUrl(ep.id, "Primary", 330), null, Modifier.fillMaxSize(), 12.dp)
            if (ep.progress > 0f) Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp)
                    .background(c.line2)
            ) { Box(Modifier.fillMaxWidth(ep.progress).height(2.dp).background(c.mediaAccent)) }
            if (ep.runtimeSecs > 0) {
                val remaining = !ep.played && ep.resumeSecs > 0 && ep.resumeSecs < ep.runtimeSecs
                Text((if (remaining) "剩余 " else "") + fmtTime(if (remaining) ep.runtimeSecs - ep.resumeSecs else ep.runtimeSecs),
                    color = Color.White, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomStart).padding(Sp.x6)
                        .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = .65f))
                        .padding(horizontal = 5.dp, vertical = 2.dp))
            }
            xyz.linplayer.app.ui.components.EpisodeStatusBadge(ep,
                Modifier.align(Alignment.TopEnd).padding(Sp.x6))
        }
        Spacer(Modifier.height(Sp.x6))
        if (expanded) {
            Text(
                listOfNotNull(ep.seasonNo?.let { "S$it" }, ep.episodeNo?.let { "E$it" }).joinToString("")
                    .let { number -> listOf(number, ep.name).filter(String::isNotBlank).joinToString("：") },
                color = if (target) c.mediaIcon else c.fg, style = LpText.body,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (target) Text("待播放", color = c.mediaIcon, style = LpText.caption)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "EP %02d".format(ep.episodeNo ?: (index + 1).toLong()),
                    color = if (current || target) c.mediaIcon else c.fg3, style = LpText.badge.copy(lineHeight = LpText.caption.lineHeight, letterSpacing = 1.1.sp), fontWeight = FontWeight.SemiBold,
                )
                if (current || target) Text(if (current) "当前集" else "待播放",
                    color = c.mediaIcon, style = LpText.caption)
            }
            Text(
                ep.name, color = c.fg, style = LpText.compact, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 语言选择弹窗(现在只剩音轨在用 —— 字幕走 [SubDialog] 的轨道列表)。 */
@Composable
private fun LangDialog(
    title: String,
    streams: List<Stream>,
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val langs = streams.mapNotNull { it.lang }.distinct()
    LpDialog(onDismiss, title) {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            if (langs.isEmpty()) Dim3("这个版本里没有可选的轨。", Modifier.padding(Sp.x12), maxLines = 3)
            langs.forEach { l ->
                OptRow(
                    langCn(l.takeUnless { it.equals("und", true) })
                        ?: streams.firstOrNull { it.lang == l }?.label?.takeIf { it.isNotBlank() } ?: "音轨", { onPick(l) },
                    media = true,
                    sub = streams.firstOrNull { it.lang == l }?.label,
                    selected = l == current,
                )
            }
            Spacer(Modifier.height(Sp.x8))
            LpButton("关闭", onDismiss, Modifier.fillMaxWidth(),
                xyz.linplayer.app.ui.components.BtnKind.Secondary)
        }
    }
}

/**
 * 字幕轨列表【用户定 2026-09-07】。
 *
 * ☠ **一条轨一行,不是一种语言一行。** 上一版按语言折叠 —— 而一部片常常挂着
 *   「简中 / 繁中 / 简日双语 / 特效」四条中文轨,折起来之后它们在界面上是同一行,
 *   用户根本看不出自己选中的是哪一条。第一行是轨道名(`title`,压制组写的那个),
 *   第二行小字是「语言标识 / 字幕格式」【用户定 2026-09-07】。
 * ☠ **落库的仍然是语言。** `prefs.setPrefs` 只认 `sub_lang`,核心层没有「记住某一条轨」
 *   这回事 —— 同语言的两条轨在这里选谁,起播时由播放器按同一条规则挑。
 *   播放中要精确换某一条,走播放页的字幕面板(那里是真的按轨切)。
 */
@Composable
private fun SubDialog(
    streams: List<Stream>,
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    LpDialog(onDismiss, "字幕轨") {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (streams.isEmpty())
                Dim3("这个版本里没有字幕轨。", Modifier.padding(Sp.x12), maxLines = 3)
            streams.forEach { st ->
                OptRow(
                    st.label.ifBlank { langCn(st.lang) ?: "字幕轨 ${st.index}" },
                    { onPick(st.lang.orEmpty()) },
                    media = true,
                    sub = st.langAndCodec + if (st.isExternal) " · 外挂" else "",
                    // 只有语言能落库,所以选中态也只能按语言判 —— 同语言的几条会一起亮
                    selected = !current.isNullOrEmpty() && st.lang == current,
                )
            }
            OptRow("不显示字幕", { onPick("") }, selected = current == "", media = true)
            Spacer(Modifier.height(Sp.x8))
            LpButton("关闭", onDismiss, Modifier.fillMaxWidth(),
                xyz.linplayer.app.ui.components.BtnKind.Secondary)
        }
    }
}

/* ───────────────────────────── 小工具 ───────────────────────────── */

/** 展示沿用当前语言偏好与服务端默认轨，不另选播放版本。 */
private fun selectedTrack(streams: List<Stream>?, prefer: String?, pattern: String = ""): Stream? {
    val ss = streams.orEmpty()
    if (ss.firstOrNull()?.type == "Subtitle") {
        val index = preferredTrackIndex(ss.map { TrackCandidate(
            listOfNotNull(it.title, it.display).joinToString(" "), it.lang.orEmpty(), it.isDefault,
        ) }, prefer, pattern, subtitle = true)
        if (index >= 0) return ss[index]
    }
    return prefer?.takeIf { it.isNotEmpty() }?.let { p -> ss.firstOrNull { it.lang == p } }
        ?: ss.firstOrNull { it.isDefault } ?: ss.firstOrNull()
}

/** 视频规格保留动态范围，不从版本文件名猜测HDR或杜比视界。 */
private fun videoSummary(stream: Stream): String {
    val range = trackText(stream.range)
    val dynamic = when {
        range?.contains("dovi", true) == true || range?.contains("dolby", true) == true || range.equals("DV", true) ||
            stream.profile?.contains("dolby vision", true) == true || stream.codec.lowercase() in setOf("dvhe", "dvh1", "dav1") -> "Dolby Vision"
        range?.contains("hdr10plus", true) == true || range?.contains("hdr10+", true) == true -> "HDR10+"
        range?.contains("hdr10", true) == true -> "HDR10"
        range.equals("HLG", true) -> "HLG"
        range.equals("SDR", true) || range.equals("Unknown", true) -> null
        else -> range
    }
    return listOfNotNull(stream.height?.takeIf { it > 0 }?.let { if (it >= 2160) "4K" else "${it}p" },
        dynamic, trackText(stream.codec)?.uppercase()).joinToString(" ")
        .ifBlank { trackText(stream.display) ?: trackText(stream.title).orEmpty() }
}

private fun audioSummary(stream: Stream): String {
    val summary = trackText(stream.display) ?: listOfNotNull(langCn(trackText(stream.lang)),
        trackText(stream.codec)?.uppercase(), trackText(stream.layout) ?: stream.channels?.takeIf { it > 0 }?.let { "${it}声道" })
        .joinToString(" ").ifBlank { stream.label }
    return summary + if (stream.isDefault && !summary.contains("默认") && !summary.contains("default", true)) "（默认）" else ""
}

private fun subtitleSummary(stream: Stream): String {
    trackText(stream.display)?.let { return it }
    val name = trackText(stream.title) ?: langCn(trackText(stream.lang)).orEmpty()
    val codec = trackText(stream.codec)?.uppercase().orEmpty()
    return if (codec.isEmpty() || name.equals(codec, true) || name.endsWith(" $codec", true)) name
        else listOf(name, codec).filter(String::isNotBlank).joinToString(" ")
}

/** 主行展示服务器规格，副行保留压制组真名；缺标题时仅补已知参数。 */
private fun trackSubtitle(stream: Stream, summary: String): String? {
    trackText(stream.title)?.takeUnless { it == summary }?.let { return it }
    val language = langCn(trackText(stream.lang))
    if (stream.type == "Audio") {
        val rate = fmtRate(stream.bitrate)
        if (rate == null) return language?.takeUnless { summary.contains(it, true) }
        val specs = listOfNotNull(trackText(stream.codec)?.uppercase(), trackText(stream.layout)
            ?: stream.channels?.takeIf { it > 0 }?.let { "${it}声道" }, rate).joinToString(" ")
        return listOfNotNull(language, "[$specs]").joinToString(" ")
    }
    return listOfNotNull(language, trackText(stream.codec)?.uppercase()).joinToString(" / ")
        .takeUnless { it.isBlank() || it == summary }
}

private fun trackText(value: String?): String? = value?.trim()?.takeUnless {
    it.isBlank() || it == "未标注" || it.equals("und", true)
}

/** 直接展示元数据日期，避免时区转换把首播日期移到前一天；无有效日期才回落年份。 */
private fun detailDate(d: JsonObject?): String? = d.str("premiere_date")?.let {
    runCatching {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }
        format.parse(it.take(10))?.let { date -> format.format(date).replace('-', '/') }
    }.getOrNull()
} ?: d.long("year")?.toString()

/** ISO 639-2 → 中文。查不到就原样返回 —— **不编一个名字出来**。 */
internal fun langCn(code: String?): String? = when (code?.lowercase()) {
    null, "" -> null
    "zh-hans", "zh_cn", "zh-cn", "zh_hans", "chs" -> "简体中文"
    "zh-hant", "zh_tw", "zh-tw", "zh_hant", "cht", "zh-hk" -> "繁体中文"
    "chi", "zho", "zh", "cmn" -> "中文"
    "jpn", "ja" -> "日语"
    "eng", "en" -> "英语"
    "kor", "ko" -> "韩语"
    "fre", "fra", "fr" -> "法语"
    "ger", "deu", "de" -> "德语"
    "spa", "es" -> "西班牙语"
    "rus", "ru" -> "俄语"
    "ita", "it" -> "意大利语"
    "und" -> "未标注"
    else -> code.let {
        val base = it.substringBefore('-').substringBefore('_')
        if (base != it) langCn(base) else it
    }
}

internal fun fmtSize(bytes: Long?): String? {
    val b = bytes ?: return null
    if (b <= 0) return null
    val g = b / 1024.0 / 1024.0 / 1024.0
    return if (g >= 1) "%.2f GB".format(Locale.ROOT, g) else "%.0f MB".format(b / 1024.0 / 1024.0)
}

internal fun fmtRate(bps: Long?): String? {
    val v = bps ?: return null
    if (v <= 0) return null
    return if (v < 1_000_000) "%.0f kbps".format(Locale.ROOT, v / 1000.0)
    else "%.2f Mbps".format(Locale.ROOT, v / 1_000_000.0).replace(".00 Mbps", " Mbps")
}

internal fun fmtTime(secs: Double): String {
    val s = secs.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

internal fun fmtDur(secs: Double): String {
    val s = secs.toLong().coerceAtLeast(0)
    return when {
        s >= 3600 -> "${s / 3600}小时${s % 3600 / 60}分${s % 60}秒"
        s >= 60 -> "${s / 60}分${s % 60}秒"
        else -> "${s}秒"
    }
}
