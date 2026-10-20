using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Avalonia.VisualTree;
using LinPlayer.Desktop.Core;
using LinPlayer.Desktop.Views;

/// <summary>人物页经过真实核心HTTP解析，验证分类、续页、错误重试及返回。</summary>
internal static class PersonCheck
{
    internal static void Run(CoreClient core)
    {
        using var port = new TcpListener(IPAddress.Loopback, 0);
        port.Start();
        var address = $"http://127.0.0.1:{((IPEndPoint)port.LocalEndpoint).Port}/";
        port.Stop();
        using var server = new HttpListener();
        server.Prefixes.Add(address); server.Start();
        var requests = new ConcurrentQueue<(string Type, int Start)>();
        var pageFailure = true;
        var hold = false;
        var held = new TaskCompletionSource();
        var release = new TaskCompletionSource();
        var delivered = new TaskCompletionSource();
        var handlers = new ConcurrentBag<Task>();
        var worker = Task.Run(async () => {
            try {
                while (server.IsListening) {
                    var context = await server.GetContextAsync();
                    handlers.Add(Task.Run(async () => {
                        var q = context.Request.QueryString;
                        object response;
                        if (!string.IsNullOrEmpty(q["PersonIds"])) {
                            Check(q["PersonIds"] == "person-1", "人物ID错误");
                            Check(q["SortBy"] == "PremiereDate" && q["SortOrder"] == "Descending", "上映排序丢失");
                            Check(q["EnableTotalRecordCount"] == "true", "未请求真实总数");
                            var type = q["IncludeItemTypes"]!;
                            var start = int.Parse(q["StartIndex"]!);
                            requests.Enqueue((type, start));
                            if (hold && type == "Series") {
                                held.TrySetResult(); await release.Task;
                            }
                            if (pageFailure && start == 30) {
                                context.Response.StatusCode = 500;
                                response = new { error = "作品暂时不可用" };
                            } else response = new {
                                Items = Enumerable.Range(start, Math.Min(30, Math.Max(0, 65 - start)))
                                    .Select(i => new { Id = $"{type}-{i}", Name = $"{type}作品{i}", Type = type }).ToArray(),
                                TotalRecordCount = 65,
                            };
                        } else response = new { Id = "person-1", Name = "人物甲", Type = "Person", Overview = "人物简介" };
                        try {
                            var bytes = JsonSerializer.SerializeToUtf8Bytes(response);
                            context.Response.ContentType = "application/json";
                            context.Response.ContentLength64 = bytes.Length;
                            await context.Response.OutputStream.WriteAsync(bytes);
                        } catch (HttpListenerException) {
                            // 测试故意在请求等待期间离页，客户端允许先断开。
                        } finally {
                            context.Response.Close();
                            if (hold && q["IncludeItemTypes"] == "Series") delivered.TrySetResult();
                        }
                    }));
                }
            } catch (Exception) when (!server.IsListening) { }
        });
        var previous = Nav.Session;
        var previousHost = Nav.Host;
        var window = new Window { Width = 1000, Height = 700 };
        try {
            Nav.Session = new Sess(address.TrimEnd('/'), "fixture", "fixture", "fixture");
            Nav.Host = p => window.Content = p;
            var page = new PersonPage(core, address.TrimEnd('/'), "person-1", "备用姓名");
            Nav.Root(page); window.Show();
            var grid = page.GetVisualDescendants().OfType<MediaGrid>().Single();
            var scroll = (ScrollViewer)page.Content!;
            Wait(() => grid.Count == 30 && Text(page, "人物甲"), "人物首屏未显示");
            scroll.Offset = new Vector(0, scroll.Extent.Height);
            Wait(() => Button(page, "重试作品").IsVisible, "续页失败没有重试");
            Check(grid.Count == 30, "续页失败丢失已加载作品");
            pageFailure = false;
            Click(page, "重试作品");
            Wait(() => grid.Count >= 60, "续页重试未完成");
            scroll.Offset = new Vector(0, scroll.Extent.Height);
            Wait(() => grid.Count == 65, "作品未取完");
            Check(requests.Where(x => x.Type == "Movie").Select(x => x.Start).SequenceEqual(new[] { 0, 30, 30, 60 }), "分页游标或重试偏移错误");
            var offset = scroll.Offset.Y;
            var card = page.GetVisualDescendants().OfType<Card>().Last();
            card.RaiseEvent(new RoutedEventArgs(Avalonia.Controls.Button.ClickEvent));
            Wait(() => Nav.Current is DetailPage, "作品未进入详情");
            Nav.Back();
            Wait(() => ReferenceEquals(Nav.Current, page) && grid.Count == 65, "返回丢失人物页");
            Check(Math.Abs(scroll.Offset.Y - offset) < 2, "返回丢失滚动位置");
            Check(requests.Count(x => x.Type == "Movie" && x.Start == 0) == 1, "返回重拉首屏");
            var session = Nav.Session!;
            pageFailure = true;
            var changed = core.CallAsync("emby.setPlayed", new {
                session.server, session.token, session.user_id, session.device_id, item_id = "Movie-0", played = true,
            });
            Wait(() => changed.IsCompleted && Button(page, "重试作品").IsVisible, "状态变化未刷新原已加载范围");
            changed.GetAwaiter().GetResult();
            Check(grid.Count == 65 && Math.Abs(scroll.Offset.Y - offset) < 2, "刷新后页失败丢失原列表或位置");
            pageFailure = false;
            var beforeRetry = requests.Count;
            Click(page, "重试作品");
            Wait(() => requests.Count >= beforeRetry + 3 && Text(page, "已加载 65 / 65 · 上映时间从新到旧"), "刷新重试没有补齐原范围");
            Check(requests.Skip(beforeRetry).Select(x => x.Start).SequenceEqual(new[] { 0, 30, 60 }), "刷新重试未从零重取");
            Check(Math.Abs(scroll.Offset.Y - offset) < 2, $"刷新成功丢失位置：{offset} -> {scroll.Offset.Y}");
            hold = true;
            Click(page, "剧集");
            Wait(() => held.Task.IsCompleted, "剧集请求未发出");
            Click(page, "电影");
            Wait(() => grid.Count == 30, "切回电影未显示");
            release.TrySetResult();
            Wait(() => delivered.Task.IsCompleted, "旧剧集请求没有完成机会");
            Dispatcher.UIThread.RunJobs();
            Check(!page.GetVisualDescendants().OfType<TextBlock>().Any(t => t.Text?.StartsWith("Series作品") == true), "旧分类写回电影");
            Check(grid.Count == 30, "旧分类覆盖当前网格");
            Console.WriteLine("人物页：真实HTTP/FFI、分类分页、续页重试、返回位置和迟到响应隔离通过");
        } finally {
            release.TrySetResult();
            window.Content = null; window.Close(); Nav.Host = previousHost;
            Nav.Root(new StackPanel()); Nav.Session = previous;
            server.Stop(); worker.GetAwaiter().GetResult();
            Task.WhenAll(handlers).GetAwaiter().GetResult();
            Dispatcher.UIThread.RunJobs();
        }
    }

    private static bool Text(Control page, string text) => page.GetVisualDescendants().OfType<TextBlock>().Any(t => t.Text == text);
    private static Button Button(Control page, string text) => page.GetVisualDescendants().OfType<Button>().First(b => b.Content as string == text);
    private static void Click(Control page, string text) => Button(page, text).RaiseEvent(new RoutedEventArgs(Avalonia.Controls.Button.ClickEvent));
    private static void Check(bool ok, string message) { if (!ok) throw new Exception(message); }
    private static void Wait(Func<bool> done, string message) {
        var deadline = Environment.TickCount64 + 10000;
        while (!done() && Environment.TickCount64 < deadline) { Dispatcher.UIThread.RunJobs(); Thread.Sleep(10); }
        Dispatcher.UIThread.RunJobs(); Check(done(), message);
    }
}
