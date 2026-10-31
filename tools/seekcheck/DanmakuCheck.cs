using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text.Json;
using Avalonia.Controls;
using Avalonia;
using Avalonia.Headless;
using Avalonia.Media;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Avalonia.VisualTree;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;
using LinPlayer.Desktop.Views;

internal static class DanmakuCheck
{
    internal static void Run(CoreClient core, string dataDir)
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
            Await(core.PlayerSetDanmakuStyle(new { heatmap = true }));
            Await((Task)typeof(PlayerPage).GetMethod("StartDanmaku", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(player, null)!);
            Check(Field<bool>(player, "_dmHeat"), "起播未读取已保存热力图偏好");
            Await(core.PlayerSetDanmakuStyle(new { heatmap = false }));
            var task = (Task<int>)typeof(PlayerPage).GetMethod("ResolveDanmaku", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(player,
                new object[] { new { source_id = "fixture", anime_id = "anime", anime_title = "作品", episode_id = "ep", episode_title = "分集" }, false, false })!;
            Check(Await(task) == 1, "手选没有加载弹幕");
            Check(Field<string>(player, "_dmSource").Contains("自建测试源"), "来源没有随结果回显");
            var offset = (Task)typeof(PlayerPage).GetMethod("SetDanmakuOffset", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(player, new object[] { 1.5 })!;
            Await(offset);
            Check(Await(core.PlayerDanmakuOffset()).GetProperty("seconds").GetDouble() == 1.5, "时间偏移没有进入核心");
            Await(core.PlayerSetDanmakuStyle(new { roll_lines = 0, top_lines = 0, bottom_lines = 0, merge = true }));
            var style = (Control)typeof(PlayerPage).GetMethod("DanmakuStyleBlock", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(player, null)!;
            window.Content = style;
            Await((Task)typeof(PlayerPage).GetMethod("LoadDanmakuStyle", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(player, null)!);
            Wait(() => style.IsEnabled, "显示设置读取后未启用");
            var area = Buttons(style).Single(b => b.Content is string s && s.StartsWith("显示区域"));
            foreach (var expected in new[] { .25, .5, .75, 1.0 }) {
                area.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                Wait(() => style.IsEnabled, "区域保存未完成");
                var saved = Await(core.PlayerGetDanmakuStyle());
                Check(saved.GetProperty("area").GetDouble() == expected, "区域四档未保存");
                Check(new[] { "roll_lines", "top_lines", "bottom_lines" }.All(k => saved.GetProperty(k).GetInt32() == 20), "旧行数仍限制区域");
                Check(saved.GetProperty("merge").GetBoolean(), "调整区域覆盖了合并偏好");
            }
            var keys = new[] { "opacity", "scale", "speed" };
            var controls = Buttons(style).Where(b => b.Content as string == "−").ToArray();
            Check(controls.Length == 3 && Buttons(style).Any(b => b.Content is string s && s.StartsWith("更多设置")), "常用设置或更多入口不完整");
            for (var i = 0; i < controls.Length; i++) {
                controls[i].RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                Wait(() => style.IsEnabled, "样式保存未完成");
                Check(Math.Abs(Await(core.PlayerGetDanmakuStyle()).GetProperty(keys[i]).GetDouble() - .9) < .001, "样式步进未保存");
            }
            var advancedKeys = new[] { ("粗体", "bold"), ("合并重复", "merge"), ("防止重叠", "prevent_overlap"), ("进度条热力图", "heatmap") };
            var advanced = Buttons(style).Single(b => b.Content is string s && s.StartsWith("粗体"));
            Check(!advanced.IsEffectivelyVisible, "高级项没有默认折叠");
            Buttons(style).Single(b => b.Content is string s && s.StartsWith("更多设置")).RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Check(advanced.IsEffectivelyVisible, "展开后高级项不可见");
            typeof(PlayerPage).GetField("_duration", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(player, 100.0);
            foreach (var (label, key) in advancedKeys) {
                var before = Await(core.PlayerGetDanmakuStyle()).GetProperty(key).GetBoolean();
                var button = Buttons(style).Single(b => b.Content is string s && s.StartsWith(label));
                button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                Wait(() => style.IsEnabled, "高级项保存未完成");
                Check(Await(core.PlayerGetDanmakuStyle()).GetProperty(key).GetBoolean() != before, "高级项没有保存: " + key);
                Check(button.Content as string == label + (before ? " · 已关闭" : " · 已开启"), "高级项回显不匹配");
            }
            Check(Field<PlayerBar>(player, "_bar").Heat.Any(v => v > 0), "开启热力图后没有密度");
            Await((Task<int>)typeof(PlayerPage).GetMethod("ResolveDanmaku", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(player,
                new object?[] { null, false, true })!);
            Check(Field<PlayerBar>(player, "_bar").Heat.Any(v => v > 0), "仅清除匹配记忆不应清空当前热力图");
            window.Width = 360; window.Height = 580; window.Background = Brushes.Black;
            Dispatcher.UIThread.RunJobs(); AvaloniaHeadlessPlatform.ForceRenderTimerTick(3);
            using (var capture = window.CaptureRenderedFrame()) capture?.Save(Path.Combine(AppContext.BaseDirectory, "danmaku-advanced.png"));
            // 读取或保存尚未返回时重开菜单，也必须共用同一条事务队列。
            var gate = Field<SemaphoreSlim>(player, "_dmStyleGate");
            var load = typeof(PlayerPage).GetMethod("LoadDanmakuStyle", BindingFlags.Instance | BindingFlags.NonPublic)!;
            var block = typeof(PlayerPage).GetMethod("DanmakuStyleBlock", BindingFlags.Instance | BindingFlags.NonPublic)!;
            gate.Wait();
            Task firstRead, lastRead;
            try {
                firstRead = (Task)load.Invoke(player, null)!;
                style = (Control)block.Invoke(player, null)!; window.Content = style;
                lastRead = (Task)load.Invoke(player, null)!;
                Check(!style.IsEnabled && !firstRead.IsCompleted && !lastRead.IsCompleted, "慢读取时重开提前启用");
                Await(core.PlayerSetDanmakuStyle(new { scale = 1.7 }));
            } finally { gate.Release(); }
            Await(Task.WhenAll(firstRead, lastRead));
            Check(style.IsEnabled && Field<double>(player, "_dmScale") == 1.7, "重开没有回显最新设置");
            Check(Field<bool>(player, "_dmBold") && !Field<bool>(player, "_dmMerge") && !Field<bool>(player, "_dmPreventOverlap") && Field<bool>(player, "_dmHeat"), "重开丢失高级设置");
            Buttons(style).Single(b => b.Content is string s && s.StartsWith("更多设置")).RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Buttons(style).Single(b => b.Content is string s && s.StartsWith("进度条热力图")).RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Wait(() => style.IsEnabled, "关闭热力图未完成");
            Check(Field<PlayerBar>(player, "_bar").Heat.Count == 0, "关闭热力图后未清空");
            gate.Wait();
            try {
                Buttons(style).Where(b => b.Content as string == "+").ElementAt(1).RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                style = (Control)block.Invoke(player, null)!; window.Content = style;
                lastRead = (Task)load.Invoke(player, null)!;
                Check(!style.IsEnabled, "前一菜单保存中重开提前启用");
            } finally { gate.Release(); }
            Await(lastRead);
            Check(style.IsEnabled && Field<double>(player, "_dmScale") == 1.8, "保存中重开被旧值覆盖");
            var configFile = Path.Combine(dataDir, "config.json");
            var original = File.ReadAllBytes(configFile);
            File.Delete(configFile); Directory.CreateDirectory(configFile);
            try {
                var bold = Buttons(style).Single(b => b.Content is string s && s.StartsWith("粗体"));
                bold.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                Wait(() => style.IsEnabled, "高级设置失败后仍被锁住");
                Check(Field<bool>(player, "_dmBold") && bold.Content as string == "粗体 · 已开启", "高级设置失败改变了回显");
                Buttons(style).First(b => b.Content as string == "+").RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                Wait(() => style.IsEnabled, "保存失败后设置仍被锁住");
                Check(Field<double>(player, "_dmOpacity") == .9, "保存失败修改了回显");
                Check(Await(core.PlayerGetDanmakuStyle()).GetProperty("opacity").GetDouble() == .9, "保存失败修改了核心偏好");
            } finally { Directory.Delete(configFile); File.WriteAllBytes(configFile, original); }
            CheckDrawing(window);
            CheckContinuous(core, window, player);
            Console.WriteLine("弹幕：源管理/手选/偏移、四项设置、实际测宽/区域绘制、连续同轨及迟到测量通过");
        } finally {
            if (player != null) Field<CancellationTokenSource>(player, "_dmCancellation").Cancel();
            window.Content = null; window.Close(); server.Stop(); worker.GetAwaiter().GetResult();
        }
    }

    private static void CheckContinuous(CoreClient core, Window window, PlayerPage player)
    {
        Await(core.PlayerSetDanmakuStyle(new { scale = 1, speed = 1, opacity = 1, area = .25,
            roll_lines = 1, top_lines = 0, bottom_lines = 0, merge = false, prevent_overlap = true }));
        Await(core.PlayerDanmakuOffset(new { seconds = 0 }));
        Await(core.PlayerDanmakuSet(new { items = Enumerable.Range(0, 16).Select(i =>
            new { time = i, mode = 1, text = "flow", color = 0xFFFFFF }).ToArray() }));
        var prepared = DmLayout.Parse(Await(core.PlayerDanmakuLayout(new { prepare = true })))!;
        Check(prepared.NeedsMeasurement && prepared.Items.Count == 16, "测量前遗漏完整候选");
        var dm = new DanmakuLayer();
        var resolver = typeof(DanmakuLayer).GetField("ResolveLayout", BindingFlags.Instance | BindingFlags.NonPublic)!;
        resolver.SetValue(dm, resolver.GetValue(Field<DanmakuLayer>(player, "_dm")));
        window.Width = 640; window.Height = 360; window.Content = dm;
        dm.Layout = prepared; dm.Sync(4, true, 1);
        Wait(() => Field<DmLayout?>(dm, "_renderLayout") != null, "实测排版未完成");
        var rendered = Field<DmLayout>(dm, "_renderLayout");
        Check(!rendered.NeedsMeasurement && rendered.Items.Count == 16 && rendered.Items.All(i => i.Lane == 0 && i.W > 0),
            "实际FFI仍按整批占轨或没有使用实测宽度");
        window.Width = 320; window.Height = 600;
        Wait(() => Field<DmLayout?>(dm, "_renderLayout") is { } changed && !ReferenceEquals(changed, rendered), "窗口变化未重测");
        var pending = new TaskCompletionSource<DmLayout?>();
        resolver.SetValue(dm, (Func<DmLayout, Size, CancellationToken, Task<DmLayout?>>)((_, _, _) => pending.Task));
        dm.Layout = prepared;
        Check(Field<DmLayout?>(dm, "_renderLayout") == null, "重测期间仍绘制旧布局");
        dm.Layout = null;
        pending.SetResult(rendered);
        Dispatcher.UIThread.RunJobs();
        Check(Field<DmLayout?>(dm, "_renderLayout") == null, "迟到测量恢复了已清空弹幕");
    }

    private static void CheckDrawing(Window window)
    {
        window.Width = 640; window.Height = 360; window.Background = Brushes.Black;
        var dm = new DanmakuLayer();
        window.Content = dm;
        var item = new DmItem { T = 0, Mode = 5, W = 104, Text = "MMMMMMMM", Color = 0xFFFFFF };
        var layout = new DmLayout { AreaHeight = 270, Items = [item] };
        dm.Layout = layout; dm.Sync(1, true, 1);
        (List<int> X, List<int> Y, int W, int H) Pixels()
        {
            Dispatcher.UIThread.RunJobs();
            AvaloniaHeadlessPlatform.ForceRenderTimerTick(3);
            using var bitmap = window.CaptureRenderedFrame() ?? throw new Exception("弹幕未生成画面");
            var w = bitmap.PixelSize.Width; var h = bitmap.PixelSize.Height;
            var bytes = new byte[w * h * 4];
            var pin = GCHandle.Alloc(bytes, GCHandleType.Pinned);
            try { bitmap.CopyPixels(new PixelRect(bitmap.PixelSize), pin.AddrOfPinnedObject(), bytes.Length, w * 4); }
            finally { pin.Free(); }
            var xs = new List<int>(); var ys = new List<int>();
            for (var y = 0; y < h; y++) for (var x = 0; x < w; x++)
                if (bytes[(y * w + x) * 4 + 1] > 127) { xs.Add(x); ys.Add(y); }
            return (xs, ys, w, h);
        }
        var fixedText = Pixels();
        Check(fixedText.X.Count > 0, "固定弹幕未绘制");
        Check(Math.Abs((fixedText.X.Min() + fixedText.X.Max()) / 2.0 - fixedText.W / 2.0) <= 2, "固定弹幕未按实际宽度居中");
        item.Mode = 4; dm.Layout = layout;
        var bottom = Pixels();
        Check(bottom.Y.Count > 0 && bottom.Y.Max() < bottom.H * .25 && bottom.Y.Min() > bottom.H * .15, "底部弹幕越出区域");
        item.Mode = 1; dm.Layout = layout; dm.Sync(7.99, true, 1);
        var tail = Pixels();
        Check(tail.X.Distinct().Count() <= 3, "滚动结束前仍有大段尾部将被截断");
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
