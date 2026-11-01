using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text.Json;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.VisualTree;
using Avalonia.LogicalTree;
using LinPlayer.Core;
using Avalonia.Threading;
using LinPlayer.Desktop.Core;
using LinPlayer.Desktop.Views;

/// <summary>通过真实页面与核心 HTTP 解析验证季内连播，不加载实际视频。</summary>
internal static class SeasonPlaybackCheck
{
    public static void Run(CoreClient core, bool markersOnly = false)
    {
        using var port = new TcpListener(IPAddress.Loopback, 0);
        port.Start();
        var number = ((IPEndPoint)port.LocalEndpoint).Port;
        port.Stop();
        using var server = new HttpListener();
        var address = $"http://127.0.0.1:{number}/";
        server.Prefixes.Add(address);
        server.Start();
        var fail = false;
        var parents = new System.Collections.Concurrent.ConcurrentQueue<string>();
        var release = new TaskCompletionSource();
        release.SetResult();
        var worker = Task.Run(async () => {
            try {
                while (server.IsListening) {
                    var context = await server.GetContextAsync();
                    var request = context.Request;
                    var id = request.Url!.AbsolutePath.Split('/').Last();
                    object response;
                    if (id == "AuthenticateByName") response = new { AccessToken = "fixture", User = new { Id = "fixture", Name = "fixture" } };
                    else if (id == "Items") {
                        parents.Enqueue(request.QueryString["ParentId"] ?? "");
                        await release.Task;
                        context.Response.StatusCode = fail ? 500 : 200;
                        response = new { Items = new[] { Episode("e1", 1, 1), Episode("e2", 1, 2), Episode("e3", 1, 3), Episode("s2e1", 2, 1) }, TotalRecordCount = 4 };
                    } else response = new { Id = id, Name = id, Type = "Episode", SeasonId = "season", SeriesId = "series", ParentIndexNumber = 1, IndexNumber = 1 };
                    var data = JsonSerializer.SerializeToUtf8Bytes(response);
                    context.Response.KeepAlive = false;
                    context.Response.ContentType = "application/json";
                    context.Response.ContentLength64 = data.Length;
                    await context.Response.OutputStream.WriteAsync(data);
                    context.Response.Close();
                }
            } catch (Exception) when (!server.IsListening) { }
        });
        var previous = Nav.Session;
        try {
            Await(core.CallAsync("emby.login", new { server = address, username = "fixture", password = "fixture", device_id = "fixture" }));
            Nav.Session = new Sess(address.TrimEnd('/'), "fixture", "fixture", "fixture");
            Nav.Root(new StackPanel());
            var page = Page("e1");
            Nav.Push(page);
            if (markersOnly) { CheckMarkers(core, page); return; }
            for (var next = 2; next <= 3; next++) {
                Await(Invoke(page, "LoadEpisodes"));
                Check(parents.All(p => p == "season"), "分集请求未限定当前季");
                Check(Get<CardItem?>(page, "_next")?.Id == $"e{next}", "切集后下一集链路断开");
                Await(Invoke(page, "GoNext", next == 2));
                page = (PlayerPage)Nav.Current!;
                Check(Get<string>(page, "_itemId") == $"e{next}", "完播未切到下一集");
                Check(Get<string>(page, "_serverId") == address.TrimEnd('/'), "切集丢失服务器");
                SuppressStart(page);
            }
            Await(Invoke(page, "LoadEpisodes"));
            Check(Get<CardItem?>(page, "_next") is null, "季末选择了下季条目");
            Await(Invoke(page, "GoNext", true));
            Check(Nav.Current is not PlayerPage && Nav.Depth == 1, "季末未退回或切集累积了播放页");

            page = Page("e1"); Nav.Push(page);
            release = new TaskCompletionSource();
            var ending = Invoke(page, "GoNext", true);
            Jobs();
            Check(!ending.IsCompleted && ReferenceEquals(Nav.Current, page), "分集未返回时误判季末");
            release.SetResult(); Await(ending);
            Check(Get<string>(Nav.Current!, "_itemId") == "e2", "分集迟到后没有继续连播");

            page = Page("e1"); Nav.Replace(page); fail = true;
            var failedLoad = Invoke(page, "LoadEpisodes");
            page.GetType().GetField("_episodesTask", BindingFlags.NonPublic | BindingFlags.Instance)!.SetValue(page, failedLoad);
            Await(failedLoad);
            Check(Get<Button>(page, "_nextBtn").IsVisible, "启动分集加载失败没有重试入口");
            fail = false;
            Await(Invoke(page, "GoNext", false));
            Check(Get<string>(Nav.Current!, "_itemId") == "e2", "第一次点击未重新请求分集");
            page = Page("e1"); Nav.Replace(page); fail = true;
            Await(Invoke(page, "GoNext", true));
            Check(ReferenceEquals(Nav.Current, page), "分集失败被当作季末退出");
            Check(Get<Button>(page, "_nextBtn").IsVisible, "加载失败没有重试入口");
            fail = false;
            Await(Invoke(page, "GoNext", true));
            Check(Get<string>(Nav.Current!, "_itemId") == "e2", "重试未恢复连播");
            Console.WriteLine("季内连播：连续三集、服务器保留、季末、加载等待及失败重试通过");
        } finally {
            release.TrySetResult();
            if (Nav.Current is PlayerPage current) Invoke(current, "Stop");
            Nav.Root(new StackPanel());
            Nav.Session = previous;
            server.Stop();
            worker.GetAwaiter().GetResult();
        }

        PlayerPage Page(string id) {
            var page = new PlayerPage(core, id, id, 0, serverId: address.TrimEnd('/'));
            SuppressStart(page);
            return page;
        }
    }

    private static void CheckMarkers(CoreClient core, PlayerPage page)
    {
        void Set(string name, object value) => typeof(PlayerPage).GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(page, value);
        void Call(string name, params object[] values) => typeof(PlayerPage).GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(page, values);
        void Until(Func<bool> done) {
            var until = Environment.TickCount64 + 10000;
            while (!done() && Environment.TickCount64 < until) { Jobs(); Thread.Sleep(10); }
            Check(done(), "标记面板操作超时"); Jobs();
        }
        Await(core.PlayerSetPlaybackPrefs(new { skip_auto = true, skip_use_online = false }));
        Await(Invoke(page, "LoadEpisodes"));
        Check(!Get<Button>(page, "_drawerBtn").IsVisible, "仅有分集队列时不能显示选集入口");
        Check(Actions.All.All(a => a.Id != "player.episodes"), "旧选集快捷键仍注册");
        double chapterTarget = -1;
        var drawer = new PlayerDrawer([(30.0, "测试章节")], at => chapterTarget = at, () => { });
        var chapterButton = drawer.GetLogicalDescendants().OfType<Button>()
            .Single(b => b.Content is TextBlock text && text.Text == "测试章节");
        chapterButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        Check(chapterTarget == 30.0, "移除选集后章节选择失效");
        Set("_duration", 1400.0); Set("_position", 90.0);
        var seeks = new List<double>();
        Set("_seek", new PlaybackSeek(value => { seeks.Add(value); return Task.CompletedTask; }, () => 0));
        var window = new Window { Width = 1000, Height = 800, Content = page };
        window.Show();
        try {
            var anchor = page.GetVisualDescendants().OfType<Button>().First(b => b.Content as string == "片头片尾");
            anchor.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Until(() => Get<Flyout?>(page, "_openFlyout") != null);
            var fly = Get<Flyout>(page, "_openFlyout");
            var body = (StackPanel)((ScrollViewer)fly.Content!).Content!;
            IEnumerable<Button> Buttons() => body.GetVisualDescendants().OfType<Button>();
            Until(() => Buttons().Any(b => b.Content as string == "应用范围 · 本季"));
            void Click(string label) {
                Buttons().Single(b => b.Content as string == label).RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                Until(() => body.IsEnabled && Get<int>(page, "_markerWrites") == 0);
            }
            Click("当前位置设为片头结束");
            Set("_position", 1200.0); Click("当前位置设为片尾开始");
            Check(seeks.Count == 0 && Get<string>(page, "_itemId") == "e1", "保存触发了跳转或切集");
            Call("SyncSkip", 1200.0);
            Check(Get<Button>(page, "_skip").Content as string == "下一集", "尾段没有展示下一集");
            Click("自定义区间 · 展开");
            Set("_position", 1360.0); Click("当前位置设为片尾结束");
            Check(Get<(double Start, double End)?>(page, "_outro") == (1200.0, 1360.0), "自定义片尾未刷新");
            Set("_position", 1300.0); Set("_skipped", "outro");
            Await(Invoke(page, "LoadChapters", true));
            fly.Hide(); Call("SyncSkip", 1300.0);
            Check(seeks.Count == 0 && Get<Button>(page, "_skip").IsVisible, "重标后立即跳转或旧跳过状态屏蔽提示");
            Check(Get<Button>(page, "_skip").Content as string == "跳过片尾", "有后续内容仍显示下一集");
            Call("DoSkip");
            Check(seeks.SequenceEqual(new[] {1360.0}), "跳过片尾未保留后续内容");
            Console.WriteLine("手动标记：真实面板/FFI，本季默认、当前位置保存、刷新不跳转、片尾保留内容通过");
        } finally { window.Content = null; window.Close(); }
    }

    private static object Episode(string id, int season, int episode) => new {
        Id = id, Name = id, Type = "Episode", ParentIndexNumber = season, IndexNumber = episode,
    };
    private static T Get<T>(object page, string name) => (T)page.GetType().GetField(name, BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(page)!;
    private static Task Invoke(object page, string name, params object[] args) =>
        page.GetType().GetMethod(name, BindingFlags.NonPublic | BindingFlags.Instance)!.Invoke(page, args) as Task ?? Task.CompletedTask;
    private static void SuppressStart(PlayerPage page) {
        Get<DispatcherTimer>(page, "_poll").Stop();
        var view = Get<object>(page, "_view");
        view.GetType().GetField("OnReady")!.SetValue(view, null);
    }
    private static void Jobs() => Dispatcher.UIThread.RunJobs();
    private static void Await(Task task) {
        var deadline = Environment.TickCount64 + 30_000;
        while (!task.IsCompleted && Environment.TickCount64 < deadline) { Jobs(); Thread.Sleep(10); }
        Check(task.IsCompleted, "连播操作超时");
        task.GetAwaiter().GetResult(); Jobs();
    }
    private static void Check(bool ok, string why) { if (!ok) throw new Exception(why); }
}
