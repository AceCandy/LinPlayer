using System.Collections.Concurrent;
using System.Collections.Specialized;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Interactivity;
using Avalonia.Input;
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
        var populated = 0;
        var emptyNextPage = 0;
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
                                    var offset = int.Parse(q["offset"]!);
                                    var items = Volatile.Read(ref populated) == 0 || offset > 0 && Volatile.Read(ref emptyNextPage) != 0 ? Array.Empty<object>() :
                                        Enumerable.Range(offset + 1, offset == 0 ? 12 : 1)
                                            .Select(i => (object)new { item_id = $"ly-work-{i}", title = $"视频{i}", feed = q["feed"] }).ToArray();
                                    response = new { items, has_more = items.Length == 12, offset, page_size = 12 }; break;
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
            Click(page, "抖奶 · 1"); Wait(() => queries.Count == 2 && Empty(page), "频道筛选未完成");
            Click(page, "露左右 · 1"); Wait(() => queries.Count == 3 && Empty(page), "组合筛选未完成");
            Check(queries.Last()["channel"] == "tg-3398168133" && queries.Last()["exposure"] == "side_breast_exposure", "频道/曝光未组合");
            Click(page, "全部作品"); Wait(() => queries.Count == 4 && Empty(page), "收藏筛选未完成");
            Check(queries.Last()["favorite"] == "true", "收藏筛选丢失");
            Click(page, "自动下一条");
            Check(page.GetVisualDescendants().OfType<Button>().Any(b => b.Content as string == "循环当前"), "循环模式未切换");
            Click(page, "收起");
            Check(!page.GetVisualDescendants().OfType<Button>().Single(b => b.Content as string == "抖奶 · 1").IsEffectivelyVisible, "收起后标签仍显示");
            Click(page, "展开");
            Volatile.Write(ref populated, 1);
            Click(page, "全部标签");
            Wait(() => ActiveId(page) == "ly-work-1", "入库候选未建立播放页");
            var nextButton = page.GetVisualDescendants().OfType<Button>().Single(b => b.Content as string == "下一条");
            nextButton.Focus();
            window.KeyPress(Key.Enter, RawInputModifiers.None);
            window.KeyRelease(Key.Enter, RawInputModifiers.None);
            Wait(() => ActiveId(page) == "ly-work-2", "焦点按钮的回车被播放器快捷键抢走");
            Click(page, "上一条"); Wait(() => ActiveId(page) == "ly-work-1", "键盘操作后回退失败");
            var slider = Field<Slider>(page, "_progress");
            slider.IsEnabled = true; slider.Maximum = 100; slider.Value = 50; slider.Focus();
            window.KeyPress(Key.Right, RawInputModifiers.None);
            Check(slider.Value > 50, "进度条方向键被播放器快捷键抢走");
            window.KeyRelease(Key.Right, RawInputModifiers.None);
            window.MouseWheel(new Point(100, 250), new Vector(0, -1), RawInputModifiers.None);
            Check(ActiveId(page) == "ly-work-2", "视频画面滚轮未换片");
            Dispatcher.UIThread.RunJobs();
            window.MouseWheel(new Point(100, 250), new Vector(0, -1), RawInputModifiers.None);
            Check(ActiveId(page) == "ly-work-2", "新播放器重置滚轮节流，连续事件跳过视频");
            Thread.Sleep(350);
            window.MouseWheel(new Point(100, 250), new Vector(0, -1), RawInputModifiers.None);
            Check(ActiveId(page) == "ly-work-3", "滚轮节流结束后未恢复换片");
            Click(page, "上一条"); Wait(() => ActiveId(page) == "ly-work-2", "滚轮操作后首次回退失败");
            Click(page, "上一条"); Wait(() => ActiveId(page) == "ly-work-1", "滚轮操作后回退失败");
            var first = ActivePlayer(page)!;
            first.RaiseEvent(new KeyEventArgs { RoutedEvent = InputElement.KeyDownEvent, Key = Key.Down, Source = first });
            Wait(() => ActiveId(page) == "ly-work-2", "视频区域下键未换片");
            Check(Field<PlaybackSeek>(first, "_seek").Stopped, "旧播放页未登记停止屏障");
            var second = ActivePlayer(page)!;
            second.RaiseEvent(new KeyEventArgs { RoutedEvent = InputElement.KeyDownEvent, Key = Key.Up, Source = second });
            Wait(() => ActiveId(page) == "ly-work-1", "视频区域上键未返回");
            var seed = queries.Last()["seed"];
            for (var i = 2; i <= 13; i++) {
                Click(page, "下一条");
                var id = $"ly-work-{i}";
                Wait(() => ActiveId(page) == id, "向后换片或翻页失败");
            }
            Check(queries.Last()["offset"] == "12" && queries.Last()["seed"] == seed, "分页改变随机种子或游标");
            var requests = queries.Count;
            Click(page, "上一条"); Wait(() => ActiveId(page) == "ly-work-12", "跨页回退失败");
            Check(queries.Count == requests, "回退已保留页仍重复请求");
            var loopPlayer = ActivePlayer(page)!;
            var completed = Field<Func<Task>>(loopPlayer, "_onCompleted")();
            Wait(() => completed.IsCompleted && ActivePlayer(page) != loopPlayer, "循环未重新起播");
            completed.GetAwaiter().GetResult();
            Check(ActiveId(page) == "ly-work-12", "循环错误地推进候选");
            var oldCompletion = Field<Func<Task>>(loopPlayer, "_onCompleted")();
            Wait(() => oldCompletion.IsCompleted, "旧完成回调未返回");
            Check(ActiveId(page) == "ly-work-12", "旧完成回调改变新目标");
            Volatile.Write(ref emptyNextPage, 1);
            Click(page, "下一条"); Wait(() => Empty(page), "空分页未显示");
            var previousButton = page.GetVisualDescendants().OfType<Button>().Single(b => b.Content as string == "上一条");
            Check(previousButton.IsEnabled, "空分页禁用了已保留页的回退");
            Click(page, "上一条"); Wait(() => ActiveId(page) == "ly-work-12", "空分页不能恢复上一页");
            Click(page, "卍解"); Wait(() => held.Task.IsCompleted, "18+请求未发出");
            Check(queries.Last()["tab"] == "" && queries.Last()["channel"] == "" && queries.Last()["exposure"] == "", "切来源未清理筛选");
            Nav.Back();
            release.TrySetResult(); Wait(() => delivered.Task.IsCompleted, "离页旧响应未完成");
            Check(Nav.Current is Border && window.Content is Border, "迟到响应恢复旧页面");
            Check(caps == 1, "浏览期间重复获取能力");
            Check(!page.GetVisualDescendants().OfType<PlayerPage>().Any(), "空库创建了播放器");
            Check(errors.IsEmpty, string.Join("; ", errors.Select(e => e.Message)));
            var closingPage = new NativeLiuyingPage(core);
            Nav.Push(closingPage); Wait(() => ActiveId(closingPage) == "ly-work-1", "退出回归未进入播放页");
            var closingPlayer = ActivePlayer(closingPage)!;
            Volatile.Write(ref emptyNextPage, 0);
            Click(closingPage, "收藏列表");
            var favorites = (Control)Field<Flyout>(closingPage, "_favoritesFlyout").Content!;
            Wait(() => favorites.GetVisualDescendants().OfType<MediaGrid>().Single().Count == 12, "收藏列表未加载");
            Check(queries.Last()["favorite"] == "true" && queries.Last()["channel"] == "" && queries.Last()["exposure"] == "", "收藏列表沿用随机筛选");
            Click(favorites, "加载更多");
            Wait(() => favorites.GetVisualDescendants().OfType<MediaGrid>().Single().Count == 13, "收藏分页未追加");
            var grid = favorites.GetVisualDescendants().OfType<MediaGrid>().Single();
            var selected = new CardItem("ly-work-13", "视频13", "", "", true, false, 0, 0, 0, 0, 0);
            Field<Action<CardItem>>(grid, "_onOpen")(selected);
            Wait(() => ActiveId(closingPage) == "ly-work-13", "收藏点播没有交接准确条目");
            Check(closingPage.GetVisualDescendants().OfType<Button>().Any(b => b.Content as string == "循环当前"), "收藏点播未切为循环当前");
            Click(closingPage, "下一条");
            Wait(() => ActiveId(closingPage) == "ly-work-1", "收藏点播下一条未回随机");
            Check(queries.Last()["favorite"] == "false" && queries.Last()["offset"] == "0", "收藏点播把分页列表当作队列");
            closingPlayer = ActivePlayer(closingPage)!;
            Nav.Back();
            Check(Field<PlaybackSeek>(closingPlayer, "_seek").Stopped, "播放中直接离页未登记停止");
            Check(!closingPage.GetVisualDescendants().OfType<PlayerPage>().Any(), "离页仍保留播放器");
            Console.WriteLine("原生流影：HTTP/FFI筛选、标签计数/收起、焦点按键、滚轮跨片节流、空页回退、循环/旧回调、停止登记及离页取消通过（未验证解码）");
        } finally {
            release.TrySetResult(); window.Content = null; window.Close();
            Nav.Host = previousHost; Nav.Root(new Border()); Nav.Session = previous;
            server.Stop(); worker.GetAwaiter().GetResult(); Task.WhenAll(handlers).GetAwaiter().GetResult();
            Dispatcher.UIThread.RunJobs();
        }
    }
    private static bool Empty(Control page) => page.GetVisualDescendants().OfType<TextBlock>().Any(t => t.Text?.StartsWith("暂无可播放的入库资源") == true);
    private static PlayerPage? ActivePlayer(Control page) => page.GetVisualDescendants().OfType<PlayerPage>().SingleOrDefault();
    private static string? ActiveId(Control page) => ActivePlayer(page) is { } player ? Field<string>(player, "_itemId") : null;
    private static T Field<T>(object value, string name) => (T)value.GetType().GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(value)!;
    private static void Click(Control page, string text) => page.GetVisualDescendants().OfType<Button>().Single(b => b.Content as string == text).RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
    private static void Check(bool ok, string why) { if (!ok) throw new Exception(why); }
    private static void Wait(Func<bool> done, string why) {
        var deadline = Environment.TickCount64 + 10000;
        while (!done() && Environment.TickCount64 < deadline) { Dispatcher.UIThread.RunJobs(); Thread.Sleep(10); }
        Dispatcher.UIThread.RunJobs(); Check(done(), why);
    }
}
