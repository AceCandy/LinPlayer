using System.Collections.Concurrent;
using System.Collections.Specialized;
using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Avalonia.VisualTree;
using LinPlayer.Desktop.Core;
using LinPlayer.Desktop.Views;

/// <summary>原生流影经过实际核心与HTTP验证入库空态、组合筛选、缓存读取和离页取消。</summary>
internal static class LiuYingCheck
{
    internal static void Run(CoreClient core)
    {
        using var port = new TcpListener(IPAddress.Loopback, 0);
        port.Start();
        var address = $"http://127.0.0.1:{((IPEndPoint)port.LocalEndpoint).Port}/";
        port.Stop();
        using var server = new HttpListener();
        server.Prefixes.Add(address); server.Start();
        var queries = new ConcurrentQueue<NameValueCollection>();
        var errors = new ConcurrentQueue<Exception>();
        var handlers = new ConcurrentBag<Task>();
        var caps = 0;
        var held = new TaskCompletionSource();
        var release = new TaskCompletionSource();
        var delivered = new TaskCompletionSource();
        var worker = Task.Run(async () => {
            try {
                while (server.IsListening) {
                    var context = await server.GetContextAsync();
                    handlers.Add(Task.Run(async () => {
                        var q = context.Request.QueryString;
                        try {
                            object response;
                            switch (context.Request.Url!.AbsolutePath) {
                                case "/emby-ext/v1/capabilities":
                                    Interlocked.Increment(ref caps);
                                    response = new { version = "1", liuying = new { enabled = true, scope = "visible_library" } }; break;
                                case "/emby-ext/v1/liuying/labels":
                                    response = new { items = new[] { new { name = "tg-3398168133", count = 1 }, new { name = "side_breast_exposure", count = 1 } } }; break;
                                case "/emby-ext/v1/liuying/items":
                                    queries.Enqueue(q);
                                    if (q["feed"] == "adult") { held.TrySetResult(); await release.Task; }
                                    response = new { items = Array.Empty<object>(), has_more = false, offset = int.Parse(q["offset"]!), page_size = 12 }; break;
                                default: throw new Exception("意外接口: " + context.Request.Url.AbsolutePath);
                            }
                            var bytes = JsonSerializer.SerializeToUtf8Bytes(response);
                            context.Response.ContentType = "application/json";
                            context.Response.ContentLength64 = bytes.Length;
                            await context.Response.OutputStream.WriteAsync(bytes);
                        } catch (HttpListenerException) when (release.Task.IsCompleted) {
                            // 离页主动取消后允许连接已关闭，但必须给旧响应完成的机会。
                        } catch (Exception e) { errors.Enqueue(e); }
                        finally { context.Response.Close(); if (q["feed"] == "adult") delivered.TrySetResult(); }
                    }));
                }
            } catch (Exception) when (!server.IsListening) { }
        });
        var previous = Nav.Session;
        var previousHost = Nav.Host;
        var window = new Window { Width = 1000, Height = 700 };
        try {
            Nav.Session = new Sess(address.TrimEnd('/'), "fixture", "fixture", "fixture");
            BrowseSpace.Change(false);
            var s = Nav.Session;
            var refresh = core.CallAsync("emby.extCapabilities", new { s.server, s.token, s.user_id, s.device_id, refresh = true });
            Wait(() => refresh.IsCompleted, "能力刷新未完成"); refresh.GetAwaiter().GetResult();
            BrowseSpace.Change(true);
            Nav.Host = page => window.Content = page;
            var page = new NativeLiuyingPage(core);
            Nav.Root(new Border()); Nav.Push(page); window.Show();
            try { Wait(() => queries.Count == 1 && Empty(page), "入库空态未显示"); }
            catch { throw new Exception("入库空态未显示: " + string.Join("; ", page.GetVisualDescendants().OfType<TextBlock>().Select(t => t.Text)) + " / " + string.Join("; ", errors.Select(e => e.Message))); }
            Check(queries.Last()["tab"] == "dance", "默认舞蹈分类丢失");
            Click(page, "抖奶"); Wait(() => queries.Count == 2 && Empty(page), "频道筛选未完成");
            Click(page, "露左右"); Wait(() => queries.Count == 3 && Empty(page), "组合筛选未完成");
            Check(queries.Last()["channel"] == "tg-3398168133" && queries.Last()["exposure"] == "side_breast_exposure", "频道/曝光未组合");
            Click(page, "全部作品"); Wait(() => queries.Count == 4 && Empty(page), "收藏筛选未完成");
            Check(queries.Last()["favorite"] == "true", "收藏筛选丢失");
            Click(page, "普通 / 切换18+"); Wait(() => held.Task.IsCompleted, "18+请求未发出");
            Check(queries.Last()["tab"] == "" && queries.Last()["channel"] == "" && queries.Last()["exposure"] == "", "切来源未清理筛选");
            Nav.Back();
            release.TrySetResult(); Wait(() => delivered.Task.IsCompleted, "离页旧响应未完成");
            Check(Nav.Current is Border && window.Content is Border, "迟到响应恢复旧页面");
            Check(caps == 1, "浏览期间重复获取能力");
            Check(!page.GetVisualDescendants().OfType<PlayerPage>().Any(), "空库创建了播放器");
            Check(errors.IsEmpty, string.Join("; ", errors.Select(e => e.Message)));
            Console.WriteLine("原生流影：真实HTTP/FFI、入库空态、频道曝光联合筛选、收藏过滤、来源重置、能力只读及离页取消通过");
        } finally {
            release.TrySetResult(); window.Content = null; window.Close();
            Nav.Host = previousHost; Nav.Root(new Border()); Nav.Session = previous;
            server.Stop(); worker.GetAwaiter().GetResult(); Task.WhenAll(handlers).GetAwaiter().GetResult();
            Dispatcher.UIThread.RunJobs();
        }
    }
    private static bool Empty(Control page) => page.GetVisualDescendants().OfType<TextBlock>().Any(t => t.Text?.StartsWith("暂无可播放的入库资源") == true);
    private static void Click(Control page, string text) => page.GetVisualDescendants().OfType<Button>().Single(b => b.Content as string == text).RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
    private static void Check(bool ok, string why) { if (!ok) throw new Exception(why); }
    private static void Wait(Func<bool> done, string why) {
        var deadline = Environment.TickCount64 + 10000;
        while (!done() && Environment.TickCount64 < deadline) { Dispatcher.UIThread.RunJobs(); Thread.Sleep(10); }
        Dispatcher.UIThread.RunJobs(); Check(done(), why);
    }
}
