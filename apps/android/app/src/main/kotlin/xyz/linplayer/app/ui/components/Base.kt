package xyz.linplayer.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import xyz.linplayer.app.ui.theme.Dim
import xyz.linplayer.app.ui.theme.LpEasing
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpText
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp
import xyz.linplayer.app.ui.theme.T
import xyz.linplayer.app.ui.theme.lpTween
import xyz.linplayer.app.ui.theme.lpSpring
import xyz.linplayer.app.tv.tvFocus
import xyz.linplayer.app.tv.tvFocusRow
import xyz.linplayer.app.tv.tvFocusScale
import xyz.linplayer.app.tv.tvRaised

/*
 * 共用组件词汇(UI_MOBILE.md §4.2)。**页面只能用它拼,不许各页自造一套**
 * —— 散着写必然长出两套间距,这条在 PC 端和 TV 端都栽过。
 */

// ---------------------------------------------------------------- 按下反馈

/** 直接消费交互事件，滚动容器的短点按也能回弹；取消滚动不补点击反馈。 */
@Composable
internal fun Modifier.pressFeedback(source: MutableInteractionSource): Modifier {
    val scale = remember(source) { Animatable(1f) }
    val spec = lpSpring<Float>(visibilityThreshold = .001f)
    LaunchedEffect(source, spec) {
        scale.snapTo(1f)
        source.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> scale.animateTo(.96f, spec)
                is PressInteraction.Release -> {
                    // 快速点按可能同帧发出Press/Release，补可见缩放但不延迟点击回调。
                    if (scale.value > .96f) scale.snapTo(.96f)
                    scale.animateTo(1f, spec)
                }
                is PressInteraction.Cancel -> scale.animateTo(1f, spec)
            }
        }
    }
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/** 共用按压反馈，不增加点击等待；只在绘制阶段读取缩放。 */
@Composable
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true, tvHighlight: Boolean = true, tvScale: Boolean = true): Modifier {
    val src = remember { MutableInteractionSource() }
    return Modifier.tvFocusScale(src, enabled && tvScale).then(this).pressFeedback(src).tvFocus(src, enabled = enabled, highlight = tvHighlight)
        .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}

/**
 * 带长按的 [pressable]。
 *
 * ★ 长按**必须震一下**:长按没有任何反馈的话,用户按到一半就松手了,
 *   以为这台机器不支持 —— 而这正是「长按用另一个内核」那条路唯一的入口。
 */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    enabled: Boolean = true,
    tvHighlight: Boolean = true,
    /** 海报类自带 [xyz.linplayer.app.tv.tvPosterFocus] 时关掉通用放大，避免叠加。 */
    tvScale: Boolean = true,
): Modifier {
    val src = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    return Modifier.tvFocusScale(src, enabled && tvScale).then(this).pressFeedback(src).tvFocus(src, onLongClick?.takeIf { enabled }, enabled, tvHighlight)
        .combinedClickable(
            interactionSource = src, indication = null, enabled = enabled,
            onLongClick = onLongClick?.let {
                { haptic.performHapticFeedback(HapticFeedbackType.LongPress); it() }
            },
            onClick = onClick,
        )
}

/** 共用按钮实色表面，透明主题色先合成到底色。 */
@Composable
fun Modifier.buttonSkin(base: Color, corner: androidx.compose.ui.unit.Dp = R.sm): Modifier {
    return this.privateNeonBorder(corner).clip(RoundedCornerShape(corner))
        .background(base.compositeOver(Lp.colors.bg))
}

// ---------------------------------------------------------------- 文字

@Composable fun H1(t: String, m: Modifier = Modifier) =
    Text(t, m, color = Lp.colors.fg, style = LpText.heading)

@Composable fun H2(t: String, m: Modifier = Modifier) =
    Text(t, m, color = Lp.colors.fg, style = LpText.section)

@Composable fun Body(t: String, m: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(t, m, color = Lp.colors.fg, style = LpText.body,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable fun Dim2(t: String, m: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(t, m, color = Lp.colors.fg2, style = LpText.secondary,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable fun Dim3(t: String, m: Modifier = Modifier, maxLines: Int = 1) =
    Text(t, m, color = Lp.colors.fg3, style = LpText.caption,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)

// ---------------------------------------------------------------- 图标按钮

/** 图标按钮。**命中区恒 ≥48dp**,视觉大小由 `size` 定(UI_MOBILE.md §1.5)。 */
@Composable
fun LpIconButton(
    icon: ImageVector,
    desc: String?,
    m: Modifier = Modifier,
    size: Int = 24,
    tint: Color? = null,
    /** 辉光。主动作(播放键)才给 —— 满屏都在发光等于没有重点。 */
    glow: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Lp.colors
    var hasFocus by remember { mutableStateOf(false) }
    val focused = xyz.linplayer.app.tv.LocalTvMode.current && hasFocus
    Box(
        m.sizeIn(minWidth = Dim.tap, minHeight = Dim.tap)
            .onFocusChanged { hasFocus = it.isFocused }
            .tvRaised(focused, corner = R.pill)
            .clip(RoundedCornerShape(R.pill))
            .background(if (focused) c.fg else Color.Transparent)
            .pressable(onClick, tvHighlight = false),
        contentAlignment = Alignment.Center,
    ) {
        if (glow) Halo(c.acc, Modifier.matchParentSize())
        Icon(icon, desc, Modifier.size(size.dp).privateNeonIcon(), tint = if (focused) c.bg else tint ?: c.fg)
    }
}

/** 呼吸辉光。三秒一轮,靠透明度而不是尺寸 —— 尺寸变化会带着周围的布局一起抖。 */
@Composable
fun Halo(color: Color, m: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "halo")
    val a by t.animateFloat(
        0.30f, 0.72f,
        infiniteRepeatable(tween(1500, easing = LpEasing.standard), RepeatMode.Reverse),
        label = "haloA",
    )
    Box(m.graphicsLayer { alpha = a }.background(
        Brush.radialGradient(listOf(color.copy(alpha = .55f), Color.Transparent)),
        RoundedCornerShape(R.pill),
    ))
}

// ---------------------------------------------------------------- 按钮

enum class BtnKind { Primary, Secondary, Danger, Ghost }

@Composable
fun LpButton(
    text: String,
    onClick: () -> Unit,
    m: Modifier = Modifier,
    kind: BtnKind = BtnKind.Primary,
    enabled: Boolean = true,
    /** 加载中:**保持原尺寸**(尺寸跳变比转圈更烦人)。 */
    loading: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val c = Lp.colors
    var hasFocus by remember { mutableStateOf(false) }
    val focused = xyz.linplayer.app.tv.LocalTvMode.current && hasFocus && enabled && !loading
    val bg = when (kind) {
        BtnKind.Primary -> c.acc
        BtnKind.Secondary -> c.s2
        BtnKind.Danger -> c.bad
        BtnKind.Ghost -> Color.Transparent
    }
    // Ghost 是「看起来不像按钮」的那一档,给它上玻璃就不是 Ghost 了
    val skin = if (focused) Modifier.buttonSkin(c.fg)
        else if (kind == BtnKind.Ghost) Modifier.privateNeonBorder() else Modifier.buttonSkin(bg)
    val fg = if (focused) c.bg else when (kind) {
        BtnKind.Primary, BtnKind.Danger -> c.accFg
        else -> c.fg
    }
    val on = enabled && !loading
    Box(
        m.heightIn(min = Dim.tap).onFocusChanged { hasFocus = it.isFocused }
            .graphicsLayer { alpha = if (on) 1f else 0.45f }
            .tvRaised(focused, corner = R.sm)
            .then(skin)
            .then(if (loading) Modifier.semantics { contentDescription = text; stateDescription = "加载中" } else Modifier)
            .pressable(onClick, onLongClick, on, tvHighlight = false)
            .padding(horizontal = Sp.x20, vertical = Sp.x12),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = if (loading) Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics {} else Modifier,
            horizontalArrangement = Arrangement.spacedBy(Sp.x6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = fg)
            if (text.isNotEmpty()) Text(
                text, color = fg, style = LpText.action,
            )
        }
        if (loading) LpLoading(size = 20.dp, tint = fg)
    }
}

// ---------------------------------------------------------------- 输入框

/** 当前是否处于 [LpDialog] 面板内。面板本身已是 s2 层，输入框需换底色才分得清。 */
internal val LocalInDialog = androidx.compose.runtime.staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LpField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    m: Modifier = Modifier,
    password: Boolean = false,
    error: String? = null,
    label: String? = null,
    /** >1 = 多行(屏蔽词那种一行一条的列表)。给参数不另造一个组件:
     *  另造一个的下场是两份配色、两份错误位置,而改的人只会记得改一份。 */
    lines: Int = 1,
    enabled: Boolean = true,
    trailingIcon: @Composable (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    focusProperties: (FocusProperties.() -> Unit)? = null,
    focusRight: FocusRequester? = null,
) {
    val c = Lp.colors
    // 弹窗面板是 s2 层，再叠半透明 s1 几乎看不出；改用页面底色做成嵌入槽
    val container = if (LocalInDialog.current) c.bg else c.s1
    val tv = xyz.linplayer.app.tv.LocalTvMode.current
    val focusManager = LocalFocusManager.current
    Column(m) {
        if (label != null) Dim3(label, Modifier.padding(bottom = Sp.x6))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            placeholder = { Text(placeholder, style = LpText.body, color = c.fg3) },
            textStyle = LpText.body,
            trailingIcon = trailingIcon,
            keyboardOptions = if (onSearch != null) KeyboardOptions(imeAction = ImeAction.Search) else KeyboardOptions.Default,
            keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
            singleLine = lines <= 1,
            minLines = lines,
            isError = error != null,
            shape = RoundedCornerShape(R.sm),
            visualTransformation = if (password)
                androidx.compose.ui.text.input.PasswordVisualTransformation() else
                androidx.compose.ui.text.input.VisualTransformation.None,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = container, unfocusedContainerColor = container,
                errorContainerColor = container,
                focusedTextColor = c.fg, unfocusedTextColor = c.fg,
                focusedIndicatorColor = c.acc, unfocusedIndicatorColor = c.line,
                cursorColor = c.acc,
            ),
            modifier = Modifier.fillMaxWidth()
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .then(focusProperties?.let { Modifier.focusProperties(it) } ?: Modifier)
                .onPreviewKeyEvent {
                // TV单行输入框上下离开字段，左右仍用于移动光标；多行保留原生编辑。
                if (tv && lines <= 1 && it.type == KeyEventType.KeyDown) when (it.key) {
                    Key.DirectionRight -> focusRight?.requestFocus() == true
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    else -> false
                } else false
            },
        )
        // ★ 错误挂在字段下面,不弹 toast:行内错误才指得出是哪一项(§6.3 的 E_INVALID)
        if (error != null) Text(error, color = c.bad, style = LpText.caption, modifier = Modifier.padding(top = Sp.x4))
    }
}

// ---------------------------------------------------------------- 单元格

/**
 * 设置 / 列表里的一行。
 *
 * ☠☠ **`onClick` 必须是最后一个参数,别再挪。**
 *   它原来排在 `onSwitch` 前面,于是所有这样写的调用点:
 *
 *       LpCell("外观", icon = LpIcons.image) { nav.navigate(...) }
 *
 *   尾随 lambda 按 Kotlin 的规矩绑到**最后一个参数**,也就是 `onSwitch` ——
 *   而一个不声明参数的 lambda 完全可以当成 `(Boolean) -> Unit`(隐式 `it`),
 *   **所以它编译得过**。结果是 `onClick` 恒 null → 这一行根本没挂 `pressable`,
 *   而 `switch` 是 null 又不画开关,`onSwitch` 永远没人调 ——
 *   整张设置列表**一条都点不进去,一个警告都没有**。用户 2026-09-06 报的就是它。
 */
@Composable
fun LpCell(
    label: String,
    m: Modifier = Modifier,
    icon: ImageVector? = null,
    sub: String? = null,
    value: String? = null,
    switch: Boolean? = null,
    arrow: Boolean = switch == null,
    onSwitch: ((Boolean) -> Unit)? = null,
    mediaStyle: Boolean = false,
    /** 单选列表的当前项：行尾画强调色对勾，代替「已选择」文字。 */
    checked: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = Lp.colors
    var hasFocus by remember { mutableStateOf(false) }
    val focused = xyz.linplayer.app.tv.LocalTvMode.current && hasFocus
    val ink = if (focused) c.bg else c.fg
    val secondary = if (focused) c.bg else c.fg2
    // 电视上开关行整行是一个焦点:开关本身太小又没有焦点框,遥控器选不中
    val tvToggle = xyz.linplayer.app.tv.LocalTvMode.current && onClick == null && switch != null && onSwitch != null
    val rowClick: (() -> Unit)? = onClick ?: if (tvToggle) ({ onSwitch?.invoke(switch != true) }) else null
    Row(
        m.fillMaxWidth().onFocusChanged { hasFocus = it.isFocused }
            .heightIn(min = Dim.tap + Sp.x8)
            .tvRaised(focused, corner = R.sm)
            .background(if (focused) c.fg else Color.Transparent, RoundedCornerShape(R.sm))
            .then(if (rowClick != null) Modifier.pressable(rowClick, tvHighlight = false) else Modifier)
            .padding(horizontal = Sp.x16, vertical = Sp.x10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(24.dp), tint = secondary)
            Spacer(Modifier.width(Sp.x12))
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = ink, style = LpText.list, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, Modifier.padding(top = Sp.x2), color = secondary,
                style = LpText.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) Text(value, Modifier.padding(start = Sp.x8)
            .then(if (mediaStyle) Modifier.widthIn(max = 120.dp) else Modifier),
            color = secondary, style = LpText.secondary, overflow = TextOverflow.Ellipsis)
        when {
            switch != null -> Switch(
                checked = switch, onCheckedChange = if (tvToggle) null else onSwitch,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = c.acc,
                    checkedThumbColor = c.accFg),
                modifier = Modifier.padding(start = Sp.x8)
                    .then(if (mediaStyle) Modifier.semantics { contentDescription = label } else Modifier),
            )
            checked -> Icon(LpIcons.check, "已选择", Modifier.padding(start = Sp.x8).size(20.dp), tint = if (focused) ink else c.acc)
            arrow -> Icon(LpIcons.chevR, null, Modifier.padding(start = Sp.x6).size(18.dp), tint = if (focused) ink else c.fg3)
        }
    }
}

// ---------------------------------------------------------------- 就地调节三件套

/** 分段控件:2~4 个互斥选项,**就地生效不开弹窗**。 */
@Composable
fun SegRow(
    label: String,
    options: List<String>,
    current: String,
    onPick: (String) -> Unit,
    m: Modifier = Modifier,
    sub: String? = null,
    icon: ImageVector? = null,
) {
    val c = Lp.colors
    Column(m.fillMaxWidth().padding(horizontal = Sp.x16, vertical = Sp.x10)) {
        RowHead(label, sub, icon)
        Spacer(Modifier.height(Sp.x8))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(R.md)).background(c.s2).padding(Sp.x4).tvFocusRow(),
            horizontalArrangement = Arrangement.spacedBy(Sp.x2),
        ) {
            options.forEach { o ->
                val on = o == current
                var hasFocus by remember { mutableStateOf(false) }
                val focused = xyz.linplayer.app.tv.LocalTvMode.current && hasFocus
                Box(
                    Modifier.weight(1f).heightIn(min = Dim.tap)
                        .onFocusChanged { hasFocus = it.isFocused }
                        .tvRaised(focused, corner = R.sm)
                        .clip(RoundedCornerShape(R.sm))
                        // 选中块是浮起的一层而非整块强调色：一页多个分段并排时不抢主按钮
                        .background(if (focused) c.fg else if (on) (if (c.isDark) c.s3 else Color.White) else Color.Transparent)
                        .semantics { selected = on }
                        .pressable({ if (!on) onPick(o) }, tvHighlight = false)
                        .padding(vertical = Sp.x8),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(o, color = if (focused) c.bg else if (on) c.acc else c.fg2,
                    style = if (on) LpText.filter.copy(fontWeight = FontWeight.SemiBold) else LpText.filter,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (on && xyz.linplayer.app.tv.LocalTvMode.current) Box(Modifier.align(Alignment.BottomCenter)
                        .size(16.dp, 2.dp).background(c.acc))
                }
            }
        }
    }
}

/** 步进器:有明确步长的数值。**到头把按钮禁掉** —— 不禁的话用户会一直点还以为没反应。 */
@Composable
fun StepperRow(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    onChange: (Double) -> Unit,
    m: Modifier = Modifier,
    sub: String? = null,
    fmt: (Double) -> String = { it.toString() },
) {
    val c = Lp.colors
    Row(
        m.fillMaxWidth().padding(horizontal = Sp.x16, vertical = Sp.x10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) { RowHead(label, sub, null) }
        Row(
            Modifier.clip(RoundedCornerShape(R.pill)).background(c.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LpIconButton(LpIcons.minus, "减少", size = 18,
                tint = if (value <= min) c.fg3 else c.fg, onClick = {
                    // 浮点步长(0.25)直接加会攒出 1.7500000000000002,固定小数位收掉
                    onChange(kotlin.math.round((value - step).coerceIn(min, max) * 10000) / 10000)
                })
            Text(fmt(value), color = c.fg, style = LpText.action.copy(fontFeatureSettings = "tnum"), modifier = Modifier.widthIn(min = 56.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            LpIconButton(LpIcons.plus, "增加", size = 18,
                tint = if (value >= max) c.fg3 else c.fg, onClick = {
                    onChange(kotlin.math.round((value + step).coerceIn(min, max) * 10000) / 10000)
                })
        }
    }
}

/** 滑块:连续值。跟手期间只更新本地 float,松手才发命令(§2.3 第 3 条)。 */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit,
    m: Modifier = Modifier,
    sub: String? = null,
    fmt: (Float) -> String = { it.toInt().toString() },
) {
    val c = Lp.colors
    var live by remember(value) { mutableFloatStateOf(value) }
    Column(m.fillMaxWidth().padding(horizontal = Sp.x16, vertical = Sp.x10)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { RowHead(label, sub, null) }
            Dim3(fmt(live))
        }
        Slider(
            value = live, onValueChange = { live = it }, onValueChangeFinished = { onCommit(live) },
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = c.acc, activeTrackColor = c.acc, inactiveTrackColor = c.s3),
        )
    }
}

@Composable
private fun RowHead(label: String, sub: String?, icon: ImageVector?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(21.dp), tint = Lp.colors.fg2)
            Spacer(Modifier.width(Sp.x12))
        }
        Column {
            Text(label, color = Lp.colors.fg, style = LpText.list)
            if (sub != null) Dim2(sub, Modifier.padding(top = Sp.x2), maxLines = 3)
        }
    }
}

/** 管理类页面(设置 / 服务器 / 插件)的内容宽度：平板与横屏最宽 720dp 居中，手机宽度不足时不起作用。 */
internal fun Modifier.manageWidth() = fillMaxWidth().wrapContentWidth().widthIn(max = 720.dp).fillMaxWidth()

internal fun Modifier.manageListWidth() = fillMaxSize().manageWidth()

/** 选项行(弹窗 / 播放器面板共用)。 */
@Composable
fun OptRow(
    label: String,
    onClick: () -> Unit,
    m: Modifier = Modifier,
    sub: String? = null,
    selected: Boolean = false,
    badge: String? = null,
    media: Boolean = false,
    labelMaxLines: Int = 2,
) {
    val c = Lp.colors
    var hasFocus by remember { mutableStateOf(false) }
    val focused = xyz.linplayer.app.tv.LocalTvMode.current && hasFocus
    Row(
        m.fillMaxWidth().heightIn(min = Dim.tap)
            .onFocusChanged { hasFocus = it.isFocused }
            .tvRaised(focused, corner = R.sm)
            .clip(RoundedCornerShape(R.sm))
            .background(if (focused) c.fg else if (selected) c.accDim else Color.Transparent)
            .semantics { this.selected = selected }
            .pressable(onClick, tvHighlight = false)
            .padding(horizontal = Sp.x12, vertical = Sp.x10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (focused) c.bg else if (selected) c.acc else c.fg, style = LpText.action, maxLines = labelMaxLines,
                overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, Modifier.padding(top = Sp.x2), color = if (focused) c.bg else c.fg3,
                style = LpText.caption, maxLines = if (media) 2 else 1, overflow = TextOverflow.Ellipsis)
            if (media && badge != null) Text(badge, Modifier.padding(top = Sp.x4),
                color = if (focused) c.bg else c.mediaIcon, style = LpText.badge)
        }
        if (!media && badge != null) LpTag(badge)
        if (selected) Icon(LpIcons.check, null, Modifier.padding(start = Sp.x8).size(18.dp), tint = if (focused) c.bg else c.acc)
    }
}

@Composable
fun LpTag(text: String, m: Modifier = Modifier, danger: Boolean = false) {
    val c = Lp.colors
    Text(
        text, m.clip(RoundedCornerShape(R.sm)).background(if (danger) c.bad.copy(alpha = .18f) else c.accDim)
            .padding(horizontal = Sp.x8, vertical = Sp.x2),
        color = if (danger) c.bad else c.acc, style = LpText.badge, maxLines = 1,
    )
}

// ---------------------------------------------------------------- 骨架

/**
 * 骨架。**形状必须和真实内容一致**(海报骨架就是 2:3 的块)。
 * 25 行自己写,不引 shimmer 库(UI_MOBILE.md §12)。
 */
@Composable
fun Skeleton(m: Modifier = Modifier, corner: androidx.compose.ui.unit.Dp = R.md) {
    val c = Lp.colors
    val t = rememberInfiniteTransition(label = "skel")
    val x by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1200, easing = LpEasing.standard), RepeatMode.Restart),
        label = "skelX",
    )
    Box(
        m.clip(RoundedCornerShape(corner)).background(
            Brush.linearGradient(
                0f to c.s3, (x * 0.6f + 0.2f).coerceIn(0f, 1f) to c.s2, 1f to c.s3,
            )
        )
    )
}

@Composable
fun SkeletonLine(width: androidx.compose.ui.unit.Dp, m: Modifier = Modifier) =
    Skeleton(m.width(width).height(12.dp), R.sm)

// ---------------------------------------------------------------- 空态

/**
 * 空态。**说明必须说清是「没有」还是「没配置」还是「被筛掉了」**(UI_MOBILE.md §6.4)。
 *
 * ☠「排行榜整屏白板一个字都没有」的根因是代码落进了 `if (busy) "加载中…" else ""` 分支
 * —— 那不是空态,那是白板。
 */
@Composable
fun EmptyState(
    title: String,
    desc: String? = null,
    icon: ImageVector = LpIcons.inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    m: Modifier = Modifier,
) {
    val c = Lp.colors
    Column(
        m.fillMaxWidth().padding(Sp.x34),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(R.pill)).background(c.s2),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(28.dp), tint = c.fg3) }
        Spacer(Modifier.height(Sp.x16))
        Text(title, color = c.fg, style = LpText.section)
        if (desc != null) {
            Spacer(Modifier.height(Sp.x8))
            Text(desc, color = c.fg2, style = LpText.secondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Sp.x16))
            LpButton(actionLabel, onAction, kind = BtnKind.Secondary)
        }
    }
}

/**
 * 行内错误态 + 重试。
 * ★ `E_NETWORK` **不弹 toast**(网络抖动时会刷屏),画在这里(UI_MOBILE.md §6.3)。
 */
@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)? = null, m: Modifier = Modifier) {
    val c = Lp.colors
    Column(m.fillMaxWidth().padding(Sp.x20), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(LpIcons.info, null, Modifier.size(24.dp), tint = c.bad)
        Spacer(Modifier.height(Sp.x8))
        Text(message, color = c.fg2, style = LpText.secondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(Sp.x12))
            LpButton("重试", onRetry, kind = BtnKind.Secondary)
        }
    }
}

// ---------------------------------------------------------------- 弹窗

/**
 * 居中弹窗。**全站没有 bottom sheet**(既有决定,UI_MOBILE.md §4.2)。
 *
 * ☠ **遮罩自己画,并且铺满整块屏(含状态栏 / 导航条)**【用户定 2026-09-06】。
 *   靠 `BasicAlertDialog` 自带的那层平台 dim 不行:它只盖 dialog 窗口那块,
 *   而我们整个应用是 edge-to-edge 的 —— 于是弹窗开着的时候,后面那一页的选项
 *   还在从上下两头露出来,用户会去点它们。
 *   做法是 `usePlatformDefaultWidth=false` + `decorFitsSystemWindows=false`
 *   把 dialog 窗撑满,再自己铺一块 scrim。
 * ★ 点遮罩关闭 —— 但**面板本身要拦住点击**,不然点面板空白处也会关掉。
 */
/**
 * 弹窗宽度:**跟着屏幕按比例走**【用户 2026-09-10】。
 *
 * 原来是「最宽 420dp,否则铺满」—— 手机上等于满幅(几个选项占一整屏),
 * 平板上 420dp 只占半屏多一点,小得像个错位的提示框。同一个数字在两种屏上
 * 给出两种毛病,因为它根本不是按比例来的。
 *
 * 上下限不是审美是可用性:窄于 280dp 输入框放不下一行地址,宽于 600dp
 * 一行文字要横扫整个平板,眼睛得来回找行首。
 */
internal fun dialogWidth(screenWidthDp: Int): Int =
    (screenWidthDp * 0.82f).toInt().coerceIn(280, 600).coerceAtMost(screenWidthDp)

@Composable
fun LpDialog(onDismiss: () -> Unit, title: String? = null, content: @Composable () -> Unit) {
    val c = Lp.colors
    /* 电视:遮罩和面板那两个吞点击的 clickable 不许拿焦点 —— 否则遥控器第一下落在全屏遮罩上,
       上下键再也进不到里面的选项。打开时把焦点交给面板里第一项(同 LpMenu)。 */
    val tv = xyz.linplayer.app.tv.LocalTvMode.current
    val noFocus = if (tv) Modifier.focusProperties { canFocus = false } else Modifier
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    // 不用 BasicAlertDialog：它把内容限在 560dp 宽，电视和横屏上遮罩只盖中间一条，两侧页面直接露出来
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        LaunchedEffect(Unit) { if (tv) { androidx.compose.runtime.withFrameNanos { }; runCatching { focus.requestFocus() } } }
        Box(
            Modifier.fillMaxSize()
                .background(c.bg.copy(alpha = if (c.isDark) 0.82f else 0.72f))
                .then(noFocus)
                .clickable(interactionSource = remember { MutableInteractionSource() },
                    indication = null, onClick = onDismiss)
                .padding(Sp.x20),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(dialogWidth(
                    androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp).dp)
                    // ★ 吞掉点击:不吞的话点面板里的空白也会走到上面那层的 onDismiss
                    .then(noFocus)
                    .clickable(interactionSource = remember { MutableInteractionSource() },
                        indication = null, onClick = {})
                    .then(if (tv) Modifier.focusRequester(focus).focusGroup() else Modifier)
                    .glass(R.lg, solid = 1.25f)
                    .padding(Sp.x20),
            ) {
                if (title != null) {
                    H2(title)
                    Spacer(Modifier.height(Sp.x12))
                }
                CompositionLocalProvider(LocalPosterScene provides null, LocalInDialog provides true) { content() }
            }
        }
    }
}

// ---------------------------------------------------------------- 浮出菜单

/**
 * 长按 / 下拉浮出来的那张小面板。
 *
 * ☠ **不用 M3 的 `DropdownMenu`**:它是 Material 自己的方盘 + 一条平淡的 fade,
 *   夹在这一套玻璃面里像贴上去的另一款应用(用户 2026-09-07 原话:
 *   「太丑了,没有做适配软件的 UI 和动效」)。
 * ★ 从**锚点那一角**长出来 —— 菜单的来处就是手指刚碰过的地方。
 */
@Composable
fun LpMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    alignment: Alignment = Alignment.TopStart,
    offset: androidx.compose.ui.unit.IntOffset = androidx.compose.ui.unit.IntOffset.Zero,
    solid: Boolean = false,
    positionProvider: androidx.compose.ui.window.PopupPositionProvider? = null,
    m: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (!open) return
    val tv = xyz.linplayer.app.tv.LocalTvMode.current
    if (tv) androidx.activity.compose.BackHandler(onBack = onDismiss)
    val panel: @Composable () -> Unit = {
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { if (tv) { androidx.compose.runtime.withFrameNanos { }; focus.requestFocus() } }
        var shown by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
        val sc by androidx.compose.animation.core.animateFloatAsState(
            if (shown) 1f else .88f, lpSpring(.001f), label = "menuZ")
        val op by androidx.compose.animation.core.animateFloatAsState(
            if (shown) 1f else 0f, lpTween(T.T4), label = "menuA")
        val origin = if (positionProvider != null) androidx.compose.ui.graphics.TransformOrigin.Center
        else androidx.compose.ui.graphics.TransformOrigin(
            if (alignment == Alignment.TopEnd || alignment == Alignment.BottomEnd) 1f else 0f,
            if (alignment == Alignment.BottomStart || alignment == Alignment.BottomEnd) 1f else 0f,
        )
        Box(if (positionProvider != null) Modifier.padding(4.dp) else Modifier) {
            Column(
                m.then(if (tv) Modifier.focusRequester(focus).focusGroup() else Modifier)
                    .graphicsLayer { scaleX = sc; scaleY = sc; alpha = op; transformOrigin = origin }
                    // 菜单收窄一档【用户 2026-09-07:「又大又丑」】—— 它是几条一两个字的动作,
                    // 撑到 184dp 宽、每行 48dp 高就成了一块盖住半张卡的板
                    .widthIn(min = 148.dp, max = 260.dp)
                    .heightIn(max = 420.dp)
                    // 实底菜单浅色用白底加一圈细线：弹窗窗口按内容裁切，画不出投影，靠描边从底下内容里分出来
                    .then(if (solid) Modifier.clip(RoundedCornerShape(R.md))
                        .background(if (Lp.colors.isDark) Lp.colors.mediaPanel.copy(alpha = 1f) else Color.White)
                        .border(Dim.hairline, Lp.colors.line2, RoundedCornerShape(R.md))
                        else Modifier.glass(R.md, solid = 1.7f))
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(vertical = Sp.x6),
                content = { CompositionLocalProvider(LocalPosterScene provides null) { content() } },
            )
        }
    }
    if (positionProvider == null) androidx.compose.ui.window.Popup(
        alignment, offset, onDismiss,
        androidx.compose.ui.window.PopupProperties(focusable = true), content = panel,
    ) else androidx.compose.ui.window.Popup(
        positionProvider, onDismiss,
        androidx.compose.ui.window.PopupProperties(focusable = true), content = panel,
    )
}

/** 菜单里的一行。两行:上面是名字,下面那行小字是「它到底是哪一个」。 */
@Composable
fun LpMenuItem(
    label: String,
    onClick: () -> Unit,
    sub: String? = null,
    selected: Boolean = false,
    danger: Boolean = false,
    icon: ImageVector? = null,
    /** 服务器这类「选哪一个」的主条目：字大一档、行距更松，与下面的操作项分出层级。 */
    large: Boolean = false,
) {
    val c = Lp.colors
    var hasFocus by remember { mutableStateOf(false) }
    val focused = xyz.linplayer.app.tv.LocalTvMode.current && hasFocus
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (large) Dim.tap + 8.dp else 40.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .tvRaised(focused, corner = R.sm)
            .background(if (focused) c.fg else Color.Transparent, RoundedCornerShape(R.sm))
            .pressable(onClick, tvHighlight = false)
            .padding(horizontal = Sp.x12, vertical = if (large) Sp.x6 else Sp.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = if (focused) c.bg else if (danger) c.bad else c.fg2)
            Spacer(Modifier.width(Sp.x8))
        }
        Column(Modifier.weight(1f)) {
            Text(
                label, color = when { focused -> c.bg; danger -> c.bad; selected -> c.acc; else -> c.fg },
                style = if (large) LpText.section else LpText.action,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) Text(sub, Modifier.padding(top = if (large) Sp.x2 else 1.dp),
                color = if (focused) c.bg else c.fg3, style = LpText.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(LpIcons.check, null,
            Modifier.padding(start = Sp.x8).size(17.dp), tint = if (focused) c.bg else c.acc)
    }
}

// ---------------------------------------------------------------- 分隔与容器

@Composable
fun Hairline(m: Modifier = Modifier) =
    Box(m.fillMaxWidth().height(Dim.hairline).background(Lp.colors.line))

/**
 * 卡片面板。设置组、服务器卡都用它。
 *
 * ★ 2026-09-06 从「s1 平铺 + 发丝边」换成 [glass]:扁平面在这套深底上读起来发闷,
 *   而玻璃只是**换了一层膜**,版式一个像素没动。
 * ★ [solid] 透给调用方:服务器卡要更实一点【用户定】。
 */
@Composable
fun Panel(m: Modifier = Modifier, solid: Float = 1f, content: @Composable () -> Unit) {
    Column(m.fillMaxWidth().glass(R.md, solid)) { content() }
}

/** 保留既有等待入口与语义，绘制跟随手机全局设置。 */
@Composable
fun LpRefreshIcon() {
    LpLoading(label = "刷新中")
}

/** 接口等待只显示轻量反馈，不伪造尚未返回的海报和文字；默认居中于所在区域。整页等待统一用大尺寸动画。 */
@Composable
fun LoadingState(m: Modifier = Modifier.fillMaxSize()) {
    // 默认原生样式与 Lottie 样式的视觉占比不同，尺寸分开取。
    Box(m, contentAlignment = Alignment.Center) {
        LpLoading(size = if (xyz.linplayer.app.data.UiPrefs.loadingStyle.value == 0) 90.dp else 130.dp, label = "刷新中")
    }
}

/**
 * Grid/列表项内的整块等待：懒加载项的主轴高度不受限，不能用 fillMaxSize，
 * 按屏幕高度取比例，随设备自适应；上方已有头部时取小于 1 的比例避免多滚一屏。
 */
@Composable
fun LoadingPane(heightFraction: Float) {
    LoadingState(Modifier.fillMaxWidth().height(LocalConfiguration.current.screenHeightDp.dp * heightFraction))
}

/** 三态渲染的收口:一个区块自己管自己的 Loading / Ok / Fail。 */
@Composable
fun <T> BlockBox(
    block: xyz.linplayer.app.data.Block<T>,
    onRetry: (() -> Unit)? = null,
    skeleton: @Composable () -> Unit = { LoadingState() },
    ok: @Composable (T) -> Unit,
) {
    when (block) {
        is xyz.linplayer.app.data.Block.Loading -> skeleton()
        is xyz.linplayer.app.data.Block.Ok -> ok(block.value)
        is xyz.linplayer.app.data.Block.Fail ->
            // E_UNSUPPORTED 静默降级:整块不画,一个字都不显示(UI_MOBILE.md §6.3)
            if (!block.isSilent) ErrorState(block.message, onRetry)
    }
}

@Composable
fun FullScreenBox(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides Lp.colors.fg) {
        Box(Modifier.fillMaxSize().background(Lp.colors.bg)) { content() }
    }
}

internal val ListPad = PaddingValues(horizontal = Sp.x16, vertical = Sp.x12)
internal const val SkelDur = T.T6
