using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Avalonia.VisualTree;
using LinPlayer.Desktop.Core;
using LinPlayer.Desktop.Views;

/// <summary>真实页面经 HTTP/FFI 写入后，当前详情及返回列表必须重验。</summary>
internal static class UserStateCheck
{
    internal static void Run(CoreClient core)
    {
        using var port = new TcpListener(IPAddress.Loopback, 0);
        port.Start();
        var address = $"http://127.0.0.1:{((IPEndPoint)port.LocalEndpoint).Port}/";
        port.Stop();
        using var server = new HttpListener();
        server.Prefixes.Add(address); server.Start();
        var favorite = false;
        var played = false;
        var deny = false;
        var reads = 0;
        var listReads = 0;
        var watchedTypes = new System.Collections.Concurrent.ConcurrentQueue<string>();
        var libraryChanged = false;
        var libraryFail = false;
        var libraryOffsets = new System.Collections.Concurrent.ConcurrentQueue<int>();
        object Item() => new { Id = "movie", Name = "回归影片", Type = "Movie", RunTimeTicks = 36000000000L,
            UserData = new { IsFavorite = favorite, Played = played, PlaybackPositionTicks = played ? 0L : 6000000000L } };
        var worker = Task.Run(async () => {
            try {
                while (server.IsListening) {
                    var c = await server.GetContextAsync();
                    c.Response.KeepAlive = false;
                    var path = c.Request.Url!.AbsolutePath;
                    object response;
                    if (path.Contains("FavoriteItems/")) {
                        if (deny) c.Response.StatusCode = 403;
                        else favorite = c.Request.HttpMethod == "POST";
                        response = new { };
                    } else if (path.Contains("PlayedItems/")) {
                        if (deny) c.Response.StatusCode = 403;
                        else played = c.Request.HttpMethod == "POST";
                        response = new { };
                    } else if (path.EndsWith("/movie")) { Interlocked.Increment(ref reads); response = Item(); }
                    else if (path.EndsWith("/Items") && c.Request.QueryString["ParentId"] == "library") {
                        var offset = int.Parse(c.Request.QueryString["StartIndex"] ?? "0");
                        libraryOffsets.Enqueue(offset);
                        if (libraryFail && offset == 1) c.Response.StatusCode = 500;
                        response = new { Items = new[] { new { Id = $"row{offset}", Name = $"{(libraryChanged ? "刷新" : "原有")}影片{offset}", Type = "Movie" } }, TotalRecordCount = 2 };
                    } else if (path.EndsWith("/Items") && c.Request.QueryString["Filters"] == "IsPlayed") {
                        var kind = c.Request.QueryString["IncludeItemTypes"] ?? "";
                        watchedTypes.Enqueue(kind);
                        Check(c.Request.QueryString["ParentId"] is null && path == "/Users/fixture/Items", "历史已看不应跨服或按库查询");
                        response = new { Items = new[] { new { Id = "watched-" + kind, Name = "已看" + kind, Type = kind,
                            UserData = new { Played = true, UnplayedItemCount = 0, IsFavorite = false } } }, TotalRecordCount = 1 };
                    } else if (path.EndsWith("/Items")) {
                        Interlocked.Increment(ref listReads);
                        response = new { Items = new[] { Item() }, TotalRecordCount = 1 };
                    } else response = new { Items = Array.Empty<object>(), TotalRecordCount = 0, MediaSources = Array.Empty<object>() };
                    var bytes = JsonSerializer.SerializeToUtf8Bytes(response);
                    c.Response.ContentType = "application/json"; c.Response.ContentLength64 = bytes.Length;
                    await c.Response.OutputStream.WriteAsync(bytes); c.Response.Close();
                }
            } catch (Exception) when (!server.IsListening) { }
        });
        var previous = Nav.Session;
        var previousHost = Nav.Host;
        var window = new Window { Width = 1100, Height = 800 };
        try {
            var s = new Sess(address.TrimEnd('/'), "fixture", "fixture", "fixture");
            Nav.Session = s; Nav.Host = p => window.Content = p;
            var favorites = new FavoritesPage(core);
            Nav.Root(favorites); window.Show();
            Wait(() => listReads > 0 && favorites.GetVisualDescendants().OfType<MediaGrid>().Any(), "收藏页未加载");
            var detail = new DetailPage(core, s.server, "movie");
            Nav.Push(detail);
            Wait(() => Button(detail, "♡ 收藏") is not null, "详情未就绪");
            var before = reads;
            Button(detail, "♡ 收藏")!.RaiseEvent(new RoutedEventArgs(Avalonia.Controls.Button.ClickEvent));
            Wait(() => reads > before && Button(detail, "♥ 已收藏") is not null, "收藏成功未重取详情");
            before = reads;
            var mark = typeof(DetailPage).GetMethod("MarkPlayed", System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!;
            var write = (Task)mark.Invoke(detail, new object[] { "movie", true })!;
            Wait(() => write.IsCompleted && reads > before, "标记已看未重取详情");
            write.GetAwaiter().GetResult();
            var previousListReads = listReads;
            Nav.Back();
            Wait(() => listReads > previousListReads, "返回收藏页未消费离页变更");
            Check(ReferenceEquals(Nav.Current, favorites), "刷新重建了原列表实例");
            Wait(() => !Text(favorites, "回归影片"), "已看影片仍留在未看完收藏");
            Button(favorites, "历史已看")!.RaiseEvent(new RoutedEventArgs(Avalonia.Controls.Button.ClickEvent));
            var watched = (FavoritesPage)Nav.Current!;
            Wait(() => Text(watched, "已看Movie"), "历史已看电影未加载");
            Button(watched, "电视剧")!.RaiseEvent(new RoutedEventArgs(Avalonia.Controls.Button.ClickEvent));
            Wait(() => Text(watched, "已看Series"), "历史已看电视剧未加载");
            Check(!Text(watched, "已看Movie") && watchedTypes.SequenceEqual(new[] { "Movie", "Series" }), "历史已看分类混用");
            var library = new LibraryGridPage(core, s.server, "library", "测试媒体库");
            Nav.Root(library);
            Wait(() => Text(library, "原有影片0") && Text(library, "原有影片1"), "媒体库两页未就绪");
            libraryChanged = true; libraryFail = true;
            var refresh = core.CallAsync("emby.setPlayed", new { s.server, s.token, s.user_id, s.device_id, item_id = "movie", played = false });
            Wait(() => refresh.IsCompleted && library.GetVisualDescendants().OfType<TextBlock>().Any(t => t.Text?.StartsWith("加载失败:") == true), "后页失败未反馈");
            refresh.GetAwaiter().GetResult();
            Check(Text(library, "原有影片0") && Text(library, "原有影片1") && !Text(library, "刷新影片0"), "刷新失败提交了半份列表");
            Check(libraryOffsets.TakeLast(2).SequenceEqual(new[] { 0, 1 }), "未重验已加载的后续页");
            libraryFail = false;
            refresh = core.CallAsync("emby.setPlayed", new { s.server, s.token, s.user_id, s.device_id, item_id = "movie", played = true });
            Wait(() => refresh.IsCompleted && Text(library, "刷新影片0") && Text(library, "刷新影片1"), "多页刷新未完整提交");
            refresh.GetAwaiter().GetResult();
            var revision = core.UserStateRevision(s.server, s.user_id, s.token);
            deny = true;
            var failed = core.CallAsync("emby.setPlayed", new { s.server, s.token, s.user_id, s.device_id, item_id = "movie", played = false });
            Wait(() => failed.IsCompleted, "失败写入未完成");
            Check(failed.IsFaulted && core.UserStateRevision(s.server, s.user_id, s.token) == revision, "失败写入仍改变代次");
            deny = false;
            var other = new Sess(s.server, "other", "other", s.device_id);
            var otherRevision = core.UserStateRevision(other.server, other.user_id, other.token);
            var oldWrite = core.CallAsync("emby.setFavorite", new { s.server, s.token, s.user_id, s.device_id, item_id = "movie", fav = false });
            Nav.Session = other;
            Wait(() => oldWrite.IsCompleted, "旧会话写入未完成");
            try { oldWrite.GetAwaiter().GetResult(); throw new Exception("旧会话结果未取消"); }
            catch (OperationCanceledException) { }
            Check(core.UserStateRevision(other.server, other.user_id, other.token) == otherRevision, "旧会话写入污染新账号代次");
            Console.WriteLine("用户状态：详情/未看完收藏/已看分类、多页刷新失败保留及重验、写入失败与旧会话隔离通过");
        } finally {
            window.Content = null; window.Close(); Nav.Host = previousHost;
            Nav.Root(new StackPanel()); Nav.Session = previous;
            server.Stop(); worker.GetAwaiter().GetResult(); Dispatcher.UIThread.RunJobs();
        }
    }

    private static Button? Button(Control page, string text) => page.GetVisualDescendants().OfType<Button>().FirstOrDefault(b => b.Content as string == text);
    private static bool Text(Control page, string text) => page.GetVisualDescendants().OfType<TextBlock>().Any(t => t.Text == text);
    private static void Check(bool ok, string message) { if (!ok) throw new Exception(message); }
    private static void Wait(Func<bool> done, string message)
    {
        var deadline = Environment.TickCount64 + 10000;
        while (!done() && Environment.TickCount64 < deadline) { Dispatcher.UIThread.RunJobs(); Thread.Sleep(10); }
        Dispatcher.UIThread.RunJobs(); Check(done(), message);
    }
}
