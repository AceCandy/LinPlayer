using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text.Json;
using Avalonia.Controls;
using Avalonia.Threading;
using LinPlayer.Desktop.Core;
using LinPlayer.Desktop.Views;

/// <summary>通过真实页面与核心 HTTP 解析验证季内连播，不加载实际视频。</summary>
internal static class SeasonPlaybackCheck
{
    public static void Run(CoreClient core)
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
