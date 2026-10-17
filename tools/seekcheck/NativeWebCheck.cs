using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml.Styling;
using Avalonia.Themes.Fluent;
using Avalonia.Threading;
using Avalonia.VisualTree;
using LinPlayer.Desktop.Core;
using LinPlayer.Desktop.Views;

/// <summary>在真实X11和WebKit中验证网页生命周期，只请求回环测试页。</summary>
internal static class NativeWebCheck
{
    internal static int Run() => AppBuilder.Configure<WebApp>().UsePlatformDetect().StartWithClassicDesktopLifetime([]);

    private sealed class WebApp : Application
    {
        public override void Initialize()
        {
            Styles.Add(new FluentTheme());
            Resources.MergedDictionaries.Add(new ResourceInclude(new Uri("avares://LinPlayer/")) {
                Source = new Uri("avares://LinPlayer/Theme/Tokens.axaml") });
        }
        public override void OnFrameworkInitializationCompleted()
        {
            var desktop = (IClassicDesktopStyleApplicationLifetime)ApplicationLifetime!;
            var window = new Window { Width = 900, Height = 650, Position = new PixelPoint(40, 40), Title = "网页原生自检" };
            desktop.MainWindow = window;
            var started = false;
            window.Opened += async (_, _) => {
                if (started) return;
                started = true;
                var result = 0;
                try { await CheckPage(window); }
                catch (Exception e) { Console.Error.WriteLine(e); result = 1; }
                finally { window.Content = null; desktop.Shutdown(result); }
            };
            base.OnFrameworkInitializationCompleted();
        }
    }

    private static async Task CheckPage(Window window)
    {
        Console.WriteLine("原生窗口已创建");
        using var port = new TcpListener(IPAddress.Loopback, 0);
        port.Start(); var address = $"http://127.0.0.1:{((IPEndPoint)port.LocalEndpoint).Port}/"; port.Stop();
        using var server = new HttpListener(); server.Prefixes.Add(address); server.Start();
        var worker = Task.Run(async () => {
            try {
                while (server.IsListening) {
                    var c = await server.GetContextAsync();
                    var route = c.Request.Url!.AbsolutePath;

                    c.Response.KeepAlive = false;
                    byte[] bytes;
                    if (route == "/tone.wav") { c.Response.ContentType = "audio/wav"; bytes = Silence(); }
                    else {
                        c.Response.ContentType = "text/html; charset=utf-8";
                        bytes = Encoding.UTF8.GetBytes("""
                            <!doctype html><html><body style="margin:20px;background:#eef">
                            <input id="text" style="display:block;width:220px;height:35px">
                            <button id="play" style="margin-top:20px;height:35px" onclick="m.play().catch(e=>window.mediaError=e.name)">播放</button>
                            <button id="popup" onclick="window.open('/next','_blank')">新窗口</button>
                            <audio id="m" src="/tone.wav" loop></audio>
                            <script>window.fixtureReady=true;</script></body></html>
                            """);
                    }
                    c.Response.ContentLength64 = bytes.Length;
                    await c.Response.OutputStream.WriteAsync(bytes); c.Response.Close();
                }
            } catch (Exception) when (!server.IsListening) { }
        });
        var originalHost = Nav.Host;
        var originalSession = Nav.Session;
        try {
            Check(LinuxLiuyingWebHost.Available, "缺少WebKitGTK 4.1运行库");
            Console.WriteLine("网页运行库已加载");
            Nav.Session = new Sess(address.TrimEnd('/'), "fixture", "fixture", "fixture"); BrowseSpace.Change(true);
            Nav.Host = page => window.Content = page;
            Nav.Root(new Border());
            var page = new LiuyingPage([address + "one", address + "two"]); Nav.Push(page);
            window.Activate();
            var first = await Ready(page, 0);
            await Until(() => Task.FromResult(Painted(first)), "原生网页没有实际绘制到子窗口");
            Console.WriteLine("原生网页已加载");
            await ClickElement(window, first, "text");
            await Xdo("type", "--clearmodifiers", "parity");
            await Until(async () => await Js(first, "text.value") == "parity", "原生网页未收到完整键盘输入");
            Check(first.HasNativeFocus, "网页输入时未跟踪原生焦点");
            Console.WriteLine("原生输入通过");
            Check(await Js(first, "window.innerWidth > 700 && window.innerHeight > 400") == "true", "网页没有跟随宿主尺寸");
            await Js(first, "localStorage.setItem('parity','saved'); 'ok'");
            await Js(first, "window.geoResult=0; navigator.geolocation.getCurrentPosition(()=>window.geoResult=10,e=>window.geoResult=e.code); 'ok'");
            await Until(async () => await Js(first, "window.geoResult") == "1", "网页权限请求未被拒绝");
            await ClickElement(window, first, "play");
            await Until(async () => await Js(first, "!m.paused") == "true", "网页媒体未开始播放");
            Switch(page);
            var second = await Ready(page, 1);
            Check(!ReferenceEquals(first, second), "两站复用同一个网页");
            await Until(async () => await Js(first, "m.paused && window.__liuyingInactive") == "true", "切换后旧页媒体未暂停");
            Switch(page);
            Check(ReferenceEquals(first, await Ready(page, 0)) && await Js(first, "text.value") == "parity", "切回丢失原网页状态");
            await Until(async () => await Js(first, "!m.paused") == "true", "切回后媒体未恢复");
            var other = new Window { Width = 200, Height = 100, Title = "前后台验证" };
            try {
                other.Show(); other.Activate();
                await Xdo("windowfocus", other.TryGetPlatformHandle()!.Handle.ToInt64().ToString());
                await Until(async () => await Js(first, "m.paused") == "true", "切换到其它窗口后媒体未暂停");
                Check(!first.HasNativeFocus, "切换窗口后原生焦点未清除");
            } finally { other.Close(); }
            window.Activate();
            window.Hide();
            await Until(async () => await Js(first, "m.paused") == "true", "后台媒体未暂停");
            window.Show(); window.Activate();
            await Until(async () => await Js(first, "!m.paused") == "true", "回到前台媒体未恢复");
            await Js(first, "location.href='/next'; 'ok'");
            await Until(async () => await Js(first, "location.pathname") == "/next", "网页导航未完成");
            await Xdo("key", "Escape");
            await Until(async () => await Js(first, "location.pathname") == "/one", "返回未优先处理网页历史");
            Check(ReferenceEquals(Nav.Current, page), "网页返回提前退出页面");
            await Js(first, "location.href='data:text/html,blocked'; 'ok'");
            await Task.Delay(150);
            Check(await Js(first, "location.pathname") == "/one", "允许了非HTTP导航");
            await ClickElement(window, first, "popup");
            await Until(async () => await Js(first, "location.pathname") == "/next", "新窗口未接管到当前网页");
            window.Width = 700; window.Height = 500;
            await Until(async () => await Js(first, "window.innerWidth > 0 && window.innerWidth <= 700") == "true", "缩小窗口后网页没有跟随");
            using var unavailable = new TcpListener(IPAddress.Loopback, 0);
            unavailable.Start();
            var failedAddress = $"http://127.0.0.1:{((IPEndPoint)unavailable.LocalEndpoint).Port}/";
            unavailable.Stop();
            await Js(first, $"location.href={JsonSerializer.Serialize(failedAddress)}; 'ok'");
            bool HasError() => page.GetVisualDescendants().OfType<TextBlock>().Any(t => t.Text == "网页加载失败，请检查网络后重新进入");
            await Until(() => Task.FromResult(HasError()), "网页失败没有明确反馈");
            Switch(page); Switch(page);
            Check(HasError(), "切回失败页面后错误提示丢失");
            Nav.Root(new Border());
            await Until(() => Task.FromResult(Field<nint>(first, "_window") == 0 && Field<nint>(second, "_window") == 0), "离页未释放原生窗口");
            Check(Field<nint>(first, "_web") == 0 && Field<nint>(second, "_web") == 0, "离页未销毁双网页");
            var reopened = new LiuyingPage([address + "one", address + "two"]); Nav.Push(reopened);
            var fresh = await Ready(reopened, 0);
            Check(await Js(fresh, "localStorage.getItem('parity') === null") == "true", "新私密会话复用了旧网页数据");
            Nav.Root(new Border());
            await Until(() => Task.FromResult(Field<nint>(fresh, "_window") == 0), "重进后离页未释放窗口");
            Check(!Field<DispatcherTimer>(null, "Pump").IsEnabled, "最后一个网页销毁后事件泵未停止");
            Console.WriteLine("Linux原生流影：真实像素/键盘/尺寸、双实例、媒体前后台、返回、新窗口、权限/协议、失败反馈及私密会话销毁通过");
        } finally {
            window.Content = null; Nav.Host = originalHost; Nav.Session = originalSession;
            server.Stop(); await worker;
        }
    }

    private static void Switch(LiuyingPage page) => page.GetVisualDescendants().OfType<Button>()
        .Single(b => b.Content as string == "卍解").RaiseEvent(new Avalonia.Interactivity.RoutedEventArgs(Button.ClickEvent));

    private static async Task<LinuxLiuyingWebHost> Ready(LiuyingPage page, int index)
    {
        LinuxLiuyingWebHost? host = null;
        await Until(async () => {
            host = ((LiuyingWebHost?[])typeof(LiuyingPage).GetField("_views", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(page)!)[index] as LinuxLiuyingWebHost;
            return host is not null && Field<nint>(host, "_web") != 0 && await Js(host, "window.fixtureReady === true") == "true";
        }, "测试网页未就绪");
        return host!;
    }

    private static async Task ClickElement(Window window, LinuxLiuyingWebHost host, string id)
    {
        var rect = JsonSerializer.Deserialize<double[]>(await Js(host, $"JSON.stringify((r=>[r.x+r.width/2,r.y+r.height/2])(document.getElementById('{id}').getBoundingClientRect()))"))!;
        var origin = host.PointToScreen(new Point(rect[0], rect[1]));
        await Xdo("mousemove", "--sync", origin.X.ToString(), origin.Y.ToString(), "click", "1");
        await Task.Delay(100);
    }

    private static async Task Xdo(params string[] args)
    {
        var start = new ProcessStartInfo("xdotool") { UseShellExecute = false };
        foreach (var arg in args) start.ArgumentList.Add(arg);
        using var process = Process.Start(start)!; await process.WaitForExitAsync();
        Check(process.ExitCode == 0, "原生输入工具失败");
    }

    private static T Field<T>(object? instance, string name) => (T)typeof(LinuxLiuyingWebHost)
        .GetField(name, BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static)!.GetValue(instance)!;
    private static bool Painted(LinuxLiuyingWebHost host)
    {
        var bitmap = gdk_pixbuf_get_from_window(gtk_widget_get_window(Field<nint>(host, "_window")), 10, 10, 1, 1);
        if (bitmap == 0) return false;
        try {
            var pixel = gdk_pixbuf_get_pixels(bitmap);
            return Marshal.ReadByte(pixel) == 238 && Marshal.ReadByte(pixel, 1) == 238 && Marshal.ReadByte(pixel, 2) == 255;
        } finally { g_object_unref(bitmap); }
    }
    private static void Check(bool ok, string why) { if (!ok) throw new Exception(why); }
    private static async Task Until(Func<Task<bool>> predicate, string why)
    {
        var until = Environment.TickCount64 + 15_000;
        while (Environment.TickCount64 < until) { if (await predicate()) return; await Task.Delay(30); }
        throw new Exception(why);
    }

    private static byte[] Silence()
    {
        using var stream = new MemoryStream(); using var w = new BinaryWriter(stream);
        var data = new byte[8000 * 2 * 4];
        w.Write("RIFF"u8); w.Write(data.Length + 36); w.Write("WAVEfmt "u8); w.Write(16);
        w.Write((short)1); w.Write((short)1); w.Write(8000); w.Write(16000); w.Write((short)2); w.Write((short)16);
        w.Write("data"u8); w.Write(data.Length); w.Write(data); return stream.ToArray();
    }

    private static readonly ReadyCallback Evaluated = (_, result, data) => {
        var handle = GCHandle.FromIntPtr(data);
        var pending = ((nint View, TaskCompletionSource<string> Done))handle.Target!;
        try {
            var value = webkit_web_view_evaluate_javascript_finish(pending.View, result, out var error);
            if (error != 0) { g_error_free(error); pending.Done.TrySetResult(""); return; }
            var text = jsc_value_to_string(value);
            pending.Done.TrySetResult(Marshal.PtrToStringUTF8(text) ?? "");
            g_free(text); g_object_unref(value);
        } finally { handle.Free(); }
    };
    private static Task<string> Js(LinuxLiuyingWebHost host, string script)
    {
        var pending = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        var view = Field<nint>(host, "_web"); Check(view != 0, "测试访问了已销毁网页");
        var handle = GCHandle.Alloc((view, pending));
        webkit_web_view_evaluate_javascript(view, script, -1, 0, 0, 0, Evaluated, GCHandle.ToIntPtr(handle));
        return pending.Task.WaitAsync(TimeSpan.FromSeconds(10));
    }
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate void ReadyCallback(nint source, nint result, nint data);
    [DllImport("libwebkit2gtk-4.1.so.0")] private static extern void webkit_web_view_evaluate_javascript(nint view, [MarshalAs(UnmanagedType.LPUTF8Str)] string script, nint length, nint world, nint source, nint cancel, ReadyCallback callback, nint data);
    [DllImport("libwebkit2gtk-4.1.so.0")] private static extern nint webkit_web_view_evaluate_javascript_finish(nint view, nint result, out nint error);
    [DllImport("libjavascriptcoregtk-4.1.so.0")] private static extern nint jsc_value_to_string(nint value);
    [DllImport("libglib-2.0.so.0")] private static extern void g_free(nint value);
    [DllImport("libglib-2.0.so.0")] private static extern void g_error_free(nint error);
    [DllImport("libgobject-2.0.so.0")] private static extern void g_object_unref(nint value);
    [DllImport("libgtk-3.so.0")] private static extern nint gtk_widget_get_window(nint widget);
    [DllImport("libgdk-3.so.0")] private static extern nint gdk_pixbuf_get_from_window(nint window, int x, int y, int width, int height);
    [DllImport("libgdk_pixbuf-2.0.so.0")] private static extern nint gdk_pixbuf_get_pixels(nint bitmap);
}
