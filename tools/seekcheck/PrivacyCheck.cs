using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Input;
using Avalonia.Markup.Xaml.Styling;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Styling;
using Avalonia.Threading;
using Avalonia.VisualTree;
using LinPlayer.Desktop.Core;
using LinPlayer.Desktop.Views;

/// <summary>通过真实核心和HTTP夹具验证空间分类、旧响应、缓存和主题恢复。</summary>
internal static class PrivacyCheck
{
    internal static void Run(CoreClient core, string dataDir)
    {
        using var port = new TcpListener(IPAddress.Loopback, 0);
        port.Start();
        var address = $"http://127.0.0.1:{((IPEndPoint)port.LocalEndpoint).Port}/";
        port.Stop();
        using var server = new HttpListener();
        server.Prefixes.Add(address); server.Start();
        var classified = false;
        var queries = new ConcurrentQueue<string>();
        var started = new TaskCompletionSource();
        var release = new TaskCompletionSource();
        var worker = Task.Run(async () => {
            try {
                while (server.IsListening) {
                    var c = await server.GetContextAsync();
                    c.Response.KeepAlive = false;
                    var adult = c.Request.QueryString["IsAdult"];
                    queries.Enqueue(adult ?? "unfiltered");
                    object response;
                    if (c.Request.Url!.AbsolutePath.EndsWith("/Views")) {
                        var item = new Dictionary<string, object> { ["Id"] = "library", ["Name"] = "测试媒体库", ["Type"] = "CollectionFolder" };
                        if (classified) item["IsAdult"] = adult != "false";
                        response = new { Items = new[] { item }, TotalRecordCount = 1 };
                    } else {
                        if (c.Request.QueryString["SearchTerm"] == "late") { started.TrySetResult(); await release.Task; }
                        response = new { Items = new[] { new { Id = "film", Name = adult == "true" ? "私密作品" : "普通作品", Type = "Movie" } }, TotalRecordCount = 1 };
                    }
                    var bytes = JsonSerializer.SerializeToUtf8Bytes(response);
                    c.Response.ContentType = "application/json"; c.Response.ContentLength64 = bytes.Length;
                    await c.Response.OutputStream.WriteAsync(bytes); c.Response.Close();
                }
            } catch (Exception) when (!server.IsListening) { }
        });
        var previous = Nav.Session;
        var window = new Window { Width = 1000, Height = 720 };
        var app = Application.Current!;
        var tokens = new ResourceInclude(new Uri("avares://LinPlayer/")) { Source = new Uri("avares://LinPlayer/Theme/Tokens.axaml") };
        app.Resources.MergedDictionaries.Add(tokens);
        try {
            var s = new Sess(address.TrimEnd('/'), "fixture", "fixture", "fixture");
            Nav.Session = s;
            var normal = BrowseSpace.Current;
            var unsupported = core.EnterPrivateSpaceAsync();
            Wait(() => unsupported.IsCompleted, "旧服能力探测未完成");
            Check(unsupported.IsFaulted && !BrowseSpace.IsPrivate, "无分类的服务器仍进入私密空间");
            classified = true;
            var enter = core.EnterPrivateSpaceAsync();
            Wait(() => enter.IsCompleted, "分类探测未完成"); enter.GetAwaiter().GetResult();
            Check(BrowseSpace.IsPrivate && queries.Contains("unfiltered"), "入口没有使用完整库清单探测");
            var search = core.CallAsync("emby.search", new { s.server, s.user_id, s.token, s.device_id, query = "private" });
            Wait(() => search.IsCompleted, "私密搜索未完成"); search.GetAwaiter().GetResult();
            Check(queries.Last() == "true", "私密搜索没有分类");
            JsonElement? cached = null;
            var refresh = core.CallDisplayAsync("emby.search", new { s.server, s.user_id, s.token, s.device_id, query = "private" }, value => cached = value);
            Wait(() => refresh.IsCompleted, "私密快照刷新未完成"); refresh.GetAwaiter().GetResult();
            Check(cached is not null && cached.Value.GetRawText().Contains("私密作品"), "私密核心快照未交付");

            foreach (var command in new[] { "emby.watchHistoryList", "source.switchCandidates", "source.aggregateSearch", "download.enqueue", "prefs.pushSearch", "emby.aggregateVersions" }) {
                var rejected = core.CallAsync(command, new { });
                Wait(() => rejected.IsCompleted, "边界请求未结束");
                Check(rejected.IsFaulted && rejected.Exception!.InnerException is CoreException { Code: "E_UNSUPPORTED" }, "私密边界未拦截:" + command);
            }
            foreach (var field in new[] { "server", "server_id", "user_id", "token" }) {
                var rejected = core.CallAsync("emby.search", new Dictionary<string, string> { [field] = "other" });
                Wait(() => rejected.IsCompleted, "跨服请求未结束");
                Check(rejected.IsFaulted, "私密空间接受其它身份:" + field);
            }
            var late = core.CallDisplayAsync("emby.search", new { s.server, s.user_id, s.token, s.device_id, query = "late" }, _ => { });
            Wait(() => started.Task.IsCompleted, "迟到请求未开始");
            BrowseSpace.Change(false); release.TrySetResult();
            Wait(() => late.IsCompleted, "迟到请求未结束");
            Check(late.IsCanceled, "私密结果进入了普通空间");
            var normalSearch = core.CallAsync("emby.search", new { s.server, s.user_id, s.token, s.device_id, query = "normal" });
            Wait(() => normalSearch.IsCompleted, "普通搜索未完成"); normalSearch.GetAwaiter().GetResult();
            Check(queries.Last() == "false", "普通查询没有显式分类");
            try { BrowseSpace.Change(true, normal); throw new Exception("旧探测解锁了新空间"); } catch (OperationCanceledException) { }
            foreach (var variant in new[] { ThemeVariant.Dark, ThemeVariant.Light }) {
                app.RequestedThemeVariant = variant;
                PrivacyTheme.Apply(false);
                var before = ((SolidColorBrush)Tok.Of("Bg")).Color;
                var panel = new Border(); panel.Bind(Border.BackgroundProperty, panel.GetResourceObservable("Bg"));
                window.Content = panel; window.Show(); Dispatcher.UIThread.RunJobs();
                PrivacyTheme.Apply(true); Dispatcher.UIThread.RunJobs();
                var expected = Color.Parse(variant == ThemeVariant.Dark ? "#21152D" : "#EFE5F7");
                Check(((SolidColorBrush)Tok.Of("Bg")).Color == expected && ((SolidColorBrush)panel.Background!).Color == expected,
                    "临时配色没有更新真实控件:" + variant);
                PrivacyTheme.Apply(false); Dispatcher.UIThread.RunJobs();
                Check(((SolidColorBrush)panel.Background!).Color == before, "退出没有恢复普通主题:" + variant);
            }
            BrowseSpace.Change(true);
            CheckUi(core, window, queries);
            Nav.Session = s with { user_id = "other", token = "other" };
            Check(!BrowseSpace.IsPrivate, "切换账号没有退出私密空间");
            Console.WriteLine("隐私空间：分类探测/普通与私密查询、命令及身份边界、迟到响应、元数据隔离与深浅主题恢复通过");
        } finally {
            release.TrySetResult(); PrivacyTheme.Apply(false); window.Content = null; window.Close();
            Nav.Session = previous; app.Resources.MergedDictionaries.Remove(tokens);
            server.Stop(); worker.GetAwaiter().GetResult(); Dispatcher.UIThread.RunJobs();
        }
    }

    private static void CheckUi(CoreClient core, Window window, ConcurrentQueue<string> queries)
    {
        var reduced = typeof(Motion).GetField("_reduced", BindingFlags.NonPublic | BindingFlags.Static)!;
        var previous = reduced.GetValue(null);
        var host = Nav.Host;
        try {
            var root = new Grid { Background = Brushes.Black };
            var button = new Button { Content = "测试按钮", Width = 160, Height = 50,
                HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
                VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
            var clicks = 0;
            button.Click += (_, _) => clicks++;
            root.Children.Add(button);
            var neon = new PrivateNeon(window, root);
            root.Children.Add(neon);
            window.Content = root; window.Activate(); window.UpdateLayout(); Dispatcher.UIThread.RunJobs();
            reduced.SetValue(null, true);
            var plain = Snapshot(neon);
            neon.SetSpace(true); window.UpdateLayout();
            Check(!plain.SequenceEqual(Snapshot(neon)), "私密流光没有实际绘制");
            var point = button.TranslatePoint(new Point(button.Bounds.Width / 2, button.Bounds.Height / 2), window)!.Value;
            window.MouseDown(point, MouseButton.Left); window.MouseUp(point, MouseButton.Left);
            Check(clicks == 1, "流光遮挡按钮点击");
            var still = Snapshot(neon);
            Advance(150);
            Check(still.SequenceEqual(Snapshot(neon)), "减少动效时流光仍在变化");
            reduced.SetValue(null, false); neon.SetSpace(true);
            Check(window.IsActive, "动效测试窗口未激活");
            var moving = Snapshot(neon);
            Advance(150);
            Check(!moving.SequenceEqual(Snapshot(neon)), "前台流光相位没有推进");
            window.Hide(); Dispatcher.UIThread.RunJobs();
            Check(!window.IsActive, "后台动效测试窗口仍然激活");
            var paused = Snapshot(neon);
            Advance(150);
            Check(paused.SequenceEqual(Snapshot(neon)), "窗口后台时流光仍在变化");
            window.Show(); window.Activate(); Dispatcher.UIThread.RunJobs();
            neon.SetPlaying(true);
            Check(plain.SequenceEqual(Snapshot(neon)), "播放期间仍绘制流光");
            neon.SetPlaying(false); neon.SetSpace(false);
            Check(plain.SequenceEqual(Snapshot(neon)), "退出空间后仍绘制流光");

            var count = queries.Count;
            Nav.Host = page => { window.Content = page; window.UpdateLayout(); Dispatcher.UIThread.RunJobs(); };
            Nav.Root(new AggregatePage(core));
            Advance(50);
            Check(queries.Count == count && Nav.Current!.GetVisualDescendants().OfType<Button>()
                .Single().Content as string == "流影", "私密发现仍读取或显示聚合资料");
            Nav.Push(new LiuyingPage(["", ""]));
            Check(Nav.Current!.GetVisualDescendants().OfType<TextBlock>().Any(t => t.Text == "当前安装包尚未配置此页面"),
                "网页漏配时没有明确反馈");
            Nav.Back();
            Check(Nav.Current is AggregatePage, "流影没有网页历史时未返回发现页");
            Nav.Push(new FavoritesPage(core));
            Wait(() => Nav.Current!.GetVisualDescendants().OfType<TextBlock>().Any(t => t.Text == "私密作品"), "私密收藏未加载");
            Check(!Nav.Current!.GetVisualDescendants().OfType<TextBlock>().Any(t => t.Text?.StartsWith("数据源收藏") == true),
                "私密收藏仍读取或显示数据源收藏");
            Console.WriteLine("私密界面：流光真实绘制/点击透传/减少动效/前后台/播放隐藏、发现无聚合请求及网页漏配反馈通过");
        } finally { reduced.SetValue(null, previous); Nav.Host = host; window.Content = null; Dispatcher.UIThread.RunJobs(); }
    }

    private static byte[] Snapshot(Control control)
    {
        var size = TopLevel.GetTopLevel(control)!.ClientSize;
        using var bitmap = new RenderTargetBitmap(new PixelSize((int)size.Width, (int)size.Height));
        bitmap.Render(control);
        using var stream = new MemoryStream(); bitmap.Save(stream); return stream.ToArray();
    }

    private static void Advance(int milliseconds)
    {
        var until = Environment.TickCount64 + milliseconds;
        while (Environment.TickCount64 < until) { Dispatcher.UIThread.RunJobs(); Thread.Sleep(5); }
        Dispatcher.UIThread.RunJobs();
    }

    private static void Check(bool ok, string why) { if (!ok) throw new Exception(why); }
    private static void Wait(Func<bool> done, string why) {
        var deadline = Environment.TickCount64 + 5000;
        while (!done() && Environment.TickCount64 < deadline) { Dispatcher.UIThread.RunJobs(); Thread.Sleep(5); }
        Dispatcher.UIThread.RunJobs(); Check(done(), why);
    }
}
