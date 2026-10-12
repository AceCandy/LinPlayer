package xyz.linplayer.app.ui.pages

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import xyz.linplayer.app.BuildConfig
import xyz.linplayer.app.plugin.WebShell
import xyz.linplayer.app.ui.components.Dim3
import xyz.linplayer.app.ui.components.EmptyState
import xyz.linplayer.app.ui.components.ErrorState
import xyz.linplayer.app.ui.components.LpScaffold
import xyz.linplayer.app.ui.theme.Dim
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.LpText
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp

/** 两个网页按需加载，本页内保留浏览位置；不向网页暴露原生脚本桥。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun LiuyingPage(nav: NavController, urls: List<String> = listOf(
    BuildConfig.LP_LIUYING_URL, BuildConfig.LP_LIUYING_ADULT_URL,
)) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val available = remember(context) { WebShell.available(context) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val views = remember { arrayOfNulls<WebView>(2) }
    val errors = remember { mutableStateListOf<String?>(null, null) }
    val loading = remember { mutableStateListOf(true, true) }
    var foreground by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }

    fun back() {
        val web = views[selected]
        if (web?.canGoBack() == true) web.goBack() else nav.popBackStack()
    }
    BackHandler { back() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!foreground) views.forEach { it?.liuyingActive(false) }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            views.forEach { it?.liuyingActive(false) }
        }
    }

    LpScaffold("流影", onBack = { back() }, actions = {
        val on = selected == 1
        Text("卍解", Modifier.testTag("liuying.bankai")
            .heightIn(min = Dim.tap).clip(RoundedCornerShape(R.pill))
            .background(if (on) Lp.colors.mediaAccent else Lp.colors.s1)
            .toggleable(on, role = Role.Switch) { selected = if (it) 1 else 0 }
            .padding(horizontal = Sp.x16, vertical = Sp.x12),
            color = if (on) Lp.colors.mediaOnAccent else Lp.colors.fg2,
            style = LpText.list)
    }) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            if (!available) {
                EmptyState("无法打开网页", "系统没有可用的 WebView 组件", LpIcons.globe)
            } else Box(Modifier.weight(1f).fillMaxWidth().testTag("liuying.web")) {
                AndroidView(factory = { FrameLayout(it) }, modifier = Modifier.fillMaxSize(), onRelease = { frame ->
                    frame.removeAllViews()
                    views.forEach { web -> web?.stopLoading(); web?.destroy() }
                    views.fill(null)
                }, update = { frame ->
                    if (urls[selected].isNotBlank() && views[selected] == null) {
                        val index = selected
                        val web = WebView(frame.context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                    request.url.scheme !in listOf("http", "https")

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    loading[index] = true; errors[index] = null
                                }
                                override fun onPageFinished(view: WebView, url: String?) {
                                    loading[index] = false
                                    view.tag = null
                                    view.liuyingActive(index == selected && foreground)
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) { errors[index] = "网页加载失败，请检查网络后重试"; loading[index] = false }
                                }
                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                    if (request.isForMainFrame) { errors[index] = "网页暂时无法访问（${response.statusCode}）"; loading[index] = false }
                                }
                            }
                        }
                        views[index] = web
                        frame.addView(web, FrameLayout.LayoutParams(-1, -1))
                        web.liuyingActive(foreground)
                        web.loadUrl(urls[index])
                    }
                    views.forEachIndexed { i, web ->
                        web?.visibility = if (i == selected && urls[selected].isNotBlank()) View.VISIBLE else View.GONE
                        web?.liuyingActive(i == selected && foreground)
                    }
                })
                val error = errors[selected]
                if (urls[selected].isBlank()) EmptyState("暂时无法打开", "当前安装包尚未配置此页面", LpIcons.globe)
                else if (error != null) ErrorState(error, { views[selected]?.reload() })
                else if (loading[selected]) Dim3("正在加载…", Modifier.padding(Sp.x16))
            }
        }
    }
}

/** onPause 不保证网页脚本停止；隐藏后也阻止新的媒体 play 调用。 */
private fun WebView.liuyingActive(active: Boolean) {
    if (tag == active) return
    tag = active
    if (active) onResume()
    evaluateJavascript("""
        (() => {
            const p = HTMLMediaElement.prototype;
            if (!p.__liuyingPlay) {
                p.__liuyingPlay = p.play;
                p.play = function() { return window.__liuyingInactive ? Promise.resolve() : p.__liuyingPlay.call(this); };
            }
            const wasInactive = window.__liuyingInactive;
            window.__liuyingInactive = ${!active};
            if (window.__liuyingInactive) {
                if (!wasInactive) window.__liuyingResume = Array.from(document.querySelectorAll('video,audio')).filter(x => !x.paused);
                document.querySelectorAll('video,audio').forEach(x => x.pause());
            } else {
                const resume = window.__liuyingResume || [];
                window.__liuyingResume = [];
                resume.filter(x => x.isConnected).forEach(x => x.play().catch(() => {}));
            }
        })();
    """.trimIndent()) {
        // 先记录网页播放状态，避免原生暂停先行；已切回或离页的回调不再暂停。
        if (!active && tag == false && parent != null) onPause()
    }
}
