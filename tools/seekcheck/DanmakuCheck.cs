using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text.Json;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Avalonia.VisualTree;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;
using LinPlayer.Desktop.Views;

internal static class DanmakuCheck
{
    internal static void Run(CoreClient core)
    {
        using var socket = new TcpListener(IPAddress.Loopback, 0);
        socket.Start();
        var address = $"http://127.0.0.1:{((IPEndPoint)socket.LocalEndpoint).Port}/";
        socket.Stop();
        using var server = new HttpListener();
        server.Prefixes.Add(address); server.Start();
        var worker = Task.Run(async () => {
            try {
                while (server.IsListening) {
                    var request = await server.GetContextAsync();
                    object result = request.Request.Url!.AbsolutePath.Contains("comment/")
                        ? new { comments = new[] { new { p = "10,1,16777215,user", m = "弹幕回归" } } }
                        : new { animes = Array.Empty<object>() };
                    var bytes = JsonSerializer.SerializeToUtf8Bytes(result);
                    request.Response.ContentType = "application/json";
                    request.Response.ContentLength64 = bytes.Length;
                    await request.Response.OutputStream.WriteAsync(bytes); request.Response.Close();
                }
            } catch (Exception) when (!server.IsListening) { }
        });
        var window = new Window { Width = 1000, Height = 800 };
        PlayerPage? player = null;
        try {
            Await(core.DanmakuSetDanmakuConfig(new { sources = new object[] {
                new { id = "official", official = true, disabled = true },
                new { id = "fixture", name = "自建测试源", api_url = address }
            } }));
            var settings = SettingsSections.Danmaku(core);
            window.Content = settings; window.Show();
            Wait(() => Buttons(settings).Any(b => b.Content as string == "编辑"), "源管理未显示编辑按钮");
            Check(Buttons(settings).Count(b => b.Content as string == "删除" && b.IsVisible) == 1, "官方源暴露删除入口");
            Buttons(settings).Last(b => b.Content as string == "停用").RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Wait(() => Buttons(settings).Count(b => b.Content as string == "启用") == 2, "停用没有回显");
            var config = Await(core.DanmakuGetDanmakuConfig());
            Check(config[1].GetProperty("disabled").GetBoolean(), "启停未落核心配置");
            Buttons(settings).Last(b => b.Content as string == "启用").RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Wait(() => Buttons(settings).Any(b => b.Content as string == "停用"), "启用没有回显");
            player = new PlayerPage(core, "", "本机视频", 0, isSource: true);
            Field<DispatcherTimer>(player, "_poll").Stop();
            var task = (Task<int>)typeof(PlayerPage).GetMethod("ResolveDanmaku", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(player,
                new object[] { new { source_id = "fixture", anime_id = "anime", anime_title = "作品", episode_id = "ep", episode_title = "分集" }, false, false })!;
            Check(Await(task) == 1, "手选没有加载弹幕");
            Check(Field<string>(player, "_dmSource").Contains("自建测试源"), "来源没有随结果回显");
            var offset = (Task)typeof(PlayerPage).GetMethod("SetDanmakuOffset", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(player, new object[] { 1.5 })!;
            Await(offset);
            Check(Await(core.PlayerDanmakuOffset()).GetProperty("seconds").GetDouble() == 1.5, "时间偏移没有进入核心");
            Console.WriteLine("弹幕：桌面真实控件/FFI、官方保护、启停持久化、手选及时间偏移通过");
        } finally {
            if (player != null) Field<CancellationTokenSource>(player, "_dmCancellation").Cancel();
            window.Content = null; window.Close(); server.Stop(); worker.GetAwaiter().GetResult();
        }
    }
    private static IEnumerable<Button> Buttons(Control root) => root.GetVisualDescendants().OfType<Button>();
    private static T Field<T>(object owner, string name) => (T)owner.GetType().GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(owner)!;
    private static T Await<T>(Task<T> task) { Wait(() => task.IsCompleted, "命令超时"); return task.GetAwaiter().GetResult(); }
    private static void Await(Task task) { Wait(() => task.IsCompleted, "命令超时"); task.GetAwaiter().GetResult(); }
    private static void Check(bool ok, string message) { if (!ok) throw new Exception(message); }
    private static void Wait(Func<bool> done, string message) {
        var until = Environment.TickCount64 + 10000;
        while (!done() && Environment.TickCount64 < until) { Dispatcher.UIThread.RunJobs(); Thread.Sleep(10); }
        Dispatcher.UIThread.RunJobs(); Check(done(), message);
    }
}
