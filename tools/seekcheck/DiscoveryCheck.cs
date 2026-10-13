using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Avalonia.VisualTree;
using LinPlayer.Desktop.Core;
using LinPlayer.Desktop.Views;

/// <summary>真实桌面页面、FFI 与本地扩展协议的回归，不访问生产服务。</summary>
internal static class DiscoveryCheck
{
    internal static void Run(CoreClient core)
    {
        using var port = new TcpListener(IPAddress.Loopback, 0);
        port.Start();
        var address = $"http://127.0.0.1:{((IPEndPoint)port.LocalEndpoint).Port}/";
        port.Stop();
        using var server = new HttpListener();
        server.Prefixes.Add(address); server.Start();
        var requests = new ConcurrentQueue<string>();
        var matches = new ConcurrentQueue<string>();
        var tokens = new ConcurrentQueue<string>();
        var fail = false;
        var worker = Task.Run(async () => {
            try {
                while (server.IsListening) {
                    var c = await server.GetContextAsync();
                    c.Response.KeepAlive = false;
                    var path = c.Request.Url!.AbsolutePath;
                    requests.Enqueue(c.Request.RawUrl!);
                    tokens.Enqueue(c.Request.Headers["X-Emby-Token"] ?? "");
                    object response;
                    if (path.EndsWith("capabilities")) {
                        c.Response.StatusCode = fail ? 403 : 200;
                        response = new { version = "1", rankings = true, calendar = new { enabled = true }, library_matching = new { sources = new[] { "tmdb", "bangumi" } } };
                    } else if (path.EndsWith("rankings")) response = new { items = new[] { new { id = "hot", name = "热门", source = "tmdb", enabled = true } } };
                    else if (path.EndsWith("/hot/items")) response = new { page_size = 20, items = c.Request.QueryString["page"] == "2" ? Array.Empty<object>() : new[] { Entry("榜单作品", "tmdb", 101) } };
                    else if (path.EndsWith("calendar")) response = new { items = new[] { Entry("周历作品", "bangumi", 42) } };
                    else if (path.EndsWith("library/matches")) {
                        matches.Enqueue(await new StreamReader(c.Request.InputStream).ReadToEndAsync());
                        response = new { items = new[] { new { key = "item", matches = new[] { new { item_id = "library-item", item_type = "Series" } } } } };
                    } else response = new { Id = "library-item", Name = "媒体详情", Type = "Series", Items = Array.Empty<object>() };
                    var bytes = JsonSerializer.SerializeToUtf8Bytes(response);
                    c.Response.ContentType = "application/json";
                    c.Response.ContentLength64 = bytes.Length;
                    await c.Response.OutputStream.WriteAsync(bytes); c.Response.Close();
                }
            } catch (Exception) when (!server.IsListening) { }
        });
        var previous = Nav.Session;
        var previousHost = Nav.Host;
        var window = new Window { Width = 1100, Height = 800 };
        try {
            Nav.Session = new Sess(address.TrimEnd('/'), "fixture", "fixture", "fixture");
            Nav.Host = p => window.Content = p;
            var ranking = new RankingPage(core);
            Nav.Root(ranking); window.Show();
            Wait(() => Text(ranking, "榜单作品"), "榜单未从扩展接口显示");
            Click(ranking, "下一页");
            Wait(() => Text(ranking, "本页没有更多内容"), "榜单第二页未显示空页");
            Check(!FindButton(ranking, "下一页").IsEnabled, "空页仍允许下一页");
            Click(ranking, "上一页");
            Wait(() => Text(ranking, "榜单作品"), "上一页没有恢复");
            var entry = ranking.GetVisualDescendants().OfType<Button>().First(b => b.Content is PodiumCard);
            entry.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Wait(() => Nav.Current is DetailPage, "精确匹配后未打开详情");
            Check(matches.TryPeek(out var body) && body.Contains("\"external_id\":\"101\"") && !body.Contains("title"), "匹配身份错误");
            var calendar = new CalendarPage(core); Nav.Root(calendar);
            Wait(() => Text(calendar, "周历作品"), "日历未显示");
            var card = calendar.GetVisualDescendants().OfType<Button>().First(b => b.Content is StackPanel panel && Text(panel, "周历作品"));
            card.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Wait(() => Nav.Current is DetailPage && matches.Count == 2, "日历点击未精确匹配");
            window.Content = null;
            Nav.Session = new Sess(address.TrimEnd('/'), "other-fixture", "other-user", "fixture");
            window.Content = calendar;
            Wait(() => tokens.Contains("other-fixture") && Text(calendar, "周历作品"), "重新挂载未采用当前账号");
            fail = true;
            var denied = new CalendarPage(core); Nav.Root(denied);
            Wait(() => denied.GetVisualDescendants().OfType<TextBlock>().Any(t => t.Text?.Contains("当前账号无权使用榜单与日历") == true), "权限错误未明确展示");
            Check(!Text(denied, "周历作品"), "权限错误保留旧快照");
            Check(!requests.Any(p => p.Contains("SearchTerm")), "发现页仍按标题搜索");
            Console.WriteLine("发现页：真实HTTP/FFI、榜单分页、周历、实时精确匹配和权限撤销通过");
        } finally {
            window.Content = null; window.Close(); Nav.Host = previousHost;
            Nav.Root(new StackPanel()); Nav.Session = previous;
            server.Stop(); worker.GetAwaiter().GetResult(); Dispatcher.UIThread.RunJobs();
        }
    }
    private static object Entry(string title, string source, int id) => new {
        source, title, media_type = "tv", tmdb_id = source == "tmdb" ? id : 0, bangumi_id = source == "bangumi" ? id : 0,
        weekday = ((int)DateTimeOffset.UtcNow.ToOffset(TimeSpan.FromHours(9)).DayOfWeek + 6) % 7 + 1,
    };
    private static bool Text(Control page, string text) => page.GetVisualDescendants().OfType<TextBlock>().Any(t => t.Text == text);
    private static Button FindButton(Control page, string text) => page.GetVisualDescendants().OfType<Button>().First(b => b.Content as string == text);
    private static void Click(Control page, string text) => FindButton(page, text).RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
    private static void Check(bool ok, string message) { if (!ok) throw new Exception(message); }
    private static void Wait(Func<bool> done, string message) {
        var deadline = Environment.TickCount64 + 10000;
        while (!done() && Environment.TickCount64 < deadline) { Dispatcher.UIThread.RunJobs(); Thread.Sleep(10); }
        Dispatcher.UIThread.RunJobs(); Check(done(), message);
    }
}
