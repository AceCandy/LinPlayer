using System.Text.Json;
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Avalonia.VisualTree;
using LinPlayer.Desktop.Core;
using LinPlayer.Desktop.Views;

internal static class PhoneParityCheck
{
    internal static void Run(CoreClient core)
    {
        JsonElement Json(string text) => JsonSerializer.Deserialize<JsonElement>(text);
        var tracks = Json("""
            [{"kind":"sub","id":"2","ff_index":8,"external":false},
             {"kind":"sub","id":"4","external":true,"codec":"subrip","lang":"chi","title":"简中"},
             {"kind":"sub","id":"5","external":true,"codec":"ass","lang":"jpn","title":"日文"}]
            """);
        var choice = new InitialSubtitle("source", 17, true, "简中", "zh", "srt");
        Check(choice.Match("source", tracks) == "4", "外挂字幕仍按容器序号猜轨道");
        Check(choice.Match("other", tracks) is null, "预选跨版本串用");
        Check(choice.Match("source", Json("[]")) is null, "轨道未到时猜选");
        Check(choice.Match("source", tracks) == "4", "迟到的轨表不能重试");
        Check(new InitialSubtitle("source", null).Match("source", tracks) == "no", "关闭字幕未传关闭轨");
        Check(new InitialSubtitle("source", 8).Match("source", tracks) == "2", "内封字幕流序号映射失败");
        Check(new InitialSubtitle("source", 8).Match("source", Json("""[{"kind":"sub","id":"2","ff_index":"8"}]""")) is null, "非数值流序号不应匹配");
        Check(choice.Match("source", Json("""
            [{"kind":"sub","id":"4","external":true,"codec":"srt","lang":"zh","title":"简中"},
             {"kind":"sub","id":"6","external":true,"codec":"subrip","lang":"zho","title":"简中"}]
            """)) is null, "重复身份不能猜选");
        Check(choice.Match("source", Json("""[{"kind":"sub","id":"4","external":true,"codec":"ass","lang":"zh","title":"简中"}]""")) is null, "不同格式被错误选中");

        Await(core.CallAsync("prefs.setPrefetchSettings", new { settings = new { media_cache_bytes = 256L << 20 } }));
        var prefs = Await(core.CallAsync("prefs.getPrefs", null));
        var content = SettingsSections.MediaCache(core, prefs);
        var window = new Window { Width = 900, Height = 500, Content = content };
        try {
            window.Show(); Dispatcher.UIThread.RunJobs();
            var capacity = content.GetVisualDescendants().OfType<NumericUpDown>().Single();
            Check(capacity.Value == 256, "媒体缓存设置未回显已保存预算");
            var save = content.GetVisualDescendants().OfType<Button>().Single(b => b.Content as string == "保存");
            foreach (var value in new[] { 128, 0 }) {
                capacity.Value = value;
                save.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                Wait(() => save.IsEnabled, "媒体缓存保存未完成");
                var stored = Await(core.CallAsync("prefs.getPrefetchSettings", null));
                Check(stored.GetProperty("media_cache_bytes").GetInt64() == (long)value << 20, "媒体缓存写入契约错误");
            }
        } finally { window.Content = null; window.Close(); Dispatcher.UIThread.RunJobs(); }
        foreach (var width in new[] { 480, 1100 }) {
            string? played = null;
            var hero = new Hero(core, _ => throw new Exception("播放按钮误触详情"), item => played = item.Id);
            var heroWindow = new Window { Width = width, Height = 500, Content = hero };
            try {
                heroWindow.Show();
                hero.Show("", [Json("""{"id":"movie","name":"用于验证窄窗口标题与播放按钮的合成电影","type_":"Movie","year":2026}""")]);
                Dispatcher.UIThread.RunJobs();
                var play = hero.GetVisualDescendants().OfType<Button>().Single(b => b.Content as string == "播放");
                var point = play.TranslatePoint(new Point(), hero)!.Value;
                var title = hero.GetVisualDescendants().OfType<TextBlock>()
                    .Single(t => t.Text == "用于验证窄窗口标题与播放按钮的合成电影");
                var titlePoint = title.TranslatePoint(new Point(), hero)!.Value;
                Check(titlePoint.Y >= 0 && point.Y >= titlePoint.Y + title.Bounds.Height &&
                    Math.Abs(point.X - titlePoint.X) < 1, "Hero播放未在标题下方左对齐");
                Check(point.X >= 0 && point.Y >= 0 && point.X + play.Bounds.Width <= hero.Bounds.Width &&
                    point.Y + play.Bounds.Height <= hero.Bounds.Height,
                    $"Hero播放按钮越出布局: window={width}, hero={hero.Bounds}, point={point}, button={play.Bounds}");
                play.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                Check(played == "movie", "Hero播放未交接准确条目");
                hero.Show("", [Json("""{"id":"season","name":"季","type_":"Season"}""")]);
                Dispatcher.UIThread.RunJobs();
                Check(!hero.GetVisualDescendants().OfType<Button>().Any(b => b.Content as string == "播放"), "不可直接播放的季错误提供播放按钮");
            } finally { heroWindow.Content = null; heroWindow.Close(); Dispatcher.UIThread.RunJobs(); }
        }
        ColdHome(core);
        Console.WriteLine("手机能力对齐：字幕、媒体缓存读写、Hero布局及冷图片缓存候选通过");
    }

    private static void ColdHome(CoreClient core)
    {
        using var port = new TcpListener(IPAddress.Loopback, 0);
        port.Start();
        var address = $"http://127.0.0.1:{((IPEndPoint)port.LocalEndpoint).Port}/";
        port.Stop();
        using var server = new HttpListener();
        server.Prefixes.Add(address); server.Start();
        var requests = new ConcurrentQueue<string>();
        var worker = Task.Run(async () => {
            try {
                while (server.IsListening) {
                    var context = await server.GetContextAsync();
                    var path = context.Request.Url!.AbsolutePath;
                    requests.Enqueue(context.Request.RawUrl!);
                    object result;
                    if (path.EndsWith("AuthenticateByName"))
                        result = new { AccessToken = "fixture", User = new { Id = "fixture", Name = "fixture" } };
                    else if (path.EndsWith("/cold-hero"))
                        result = new { Id = "cold-hero", Name = "无图片缓存的推荐", Type = "Movie",
                            BackdropImageTags = new[] { "fixture" }, UserData = new { Played = false, PlaybackPositionTicks = 0 } };
                    else {
                        if (path.Contains("/Images/")) context.Response.StatusCode = 404;
                        result = new { Items = Array.Empty<object>(), TotalRecordCount = 0 };
                    }
                    var bytes = JsonSerializer.SerializeToUtf8Bytes(result);
                    context.Response.KeepAlive = false;
                    context.Response.ContentType = "application/json";
                    context.Response.ContentLength64 = bytes.Length;
                    await context.Response.OutputStream.WriteAsync(bytes); context.Response.Close();
                }
            } catch (Exception) when (!server.IsListening) { }
        });
        var previous = Nav.Session;
        var window = new Window { Width = 1100, Height = 800 };
        try {
            Await(core.CallAsync("emby.login", new { server = address, username = "fixture", password = "fixture", device_id = "fixture" }));
            Nav.Session = Sess.From(Await(core.CallAsync("emby.currentSession", null)));
            var session = Nav.Session!;
            Await(core.CallAsync("emby.itemDetail", new { session.server, session.token, session.user_id, session.device_id, item_id = "cold-hero" }));
            var pool = Await(core.CallAsync("emby.cachedItems", new { session.server, session.token, session.user_id, session.device_id }));
            Check(pool.EnumerateArray().Any(x => x.GetProperty("id").GetString() == "cold-hero"), "夹具未进入首页展示池");
            var preview = Images.LoadCachedHeroAsync(core, address.TrimEnd('/'), "cold-hero", 720, false, true);
            Wait(() => preview.IsCompleted, "缓存探测未完成");
            Check(preview.GetAwaiter().GetResult() is null && !requests.Any(x => x.Contains("/Images/")),
                "夹具不是冷图片缓存或候选探测发生回源");
            var home = new HomePage(core);
            window.Content = home; window.Show();
            Wait(() => home.GetVisualDescendants().OfType<Hero>().Any(h => h.IsVisible &&
                h.GetVisualDescendants().OfType<Button>().Any(b => b.Content as string == "播放")),
                "冷图片缓存丢弃了合法首页候选");
            Wait(() => requests.Any(x => x.Contains("/cold-hero/Images/Backdrop/0")),
                "当前轮播未按需请求原图");
            Check(!requests.Any(x => x.Contains("SortBy=Random", StringComparison.OrdinalIgnoreCase)),
                "首页为补图片重新扫描随机媒体");
        } finally {
            window.Content = null; window.Close(); Nav.Session = previous;
            server.Stop(); worker.GetAwaiter().GetResult(); Dispatcher.UIThread.RunJobs();
        }
    }

    private static JsonElement Await(Task<JsonElement> task) {
        Wait(() => task.IsCompleted, "核心命令超时"); return task.GetAwaiter().GetResult();
    }
    private static void Wait(Func<bool> done, string message) {
        var deadline = Environment.TickCount64 + 10000;
        while (!done() && Environment.TickCount64 < deadline) { Dispatcher.UIThread.RunJobs(); Thread.Sleep(10); }
        Dispatcher.UIThread.RunJobs(); Check(done(), message);
    }
    private static void Check(bool ok, string message) { if (!ok) throw new Exception(message); }
}
