using System.Reflection;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Avalonia.Themes.Fluent;
using Avalonia.VisualTree;
using LinPlayer.Desktop.Core;
using LinPlayer.Desktop.Views;

void Check(bool ok, string why) { if (!ok) throw new Exception(why); }
long now = 1000;
var release = new TaskCompletionSource();
var calls = new List<double>();
var seek = new PlaybackSeek(async target => {
    calls.Add(target);
    if (calls.Count == 1) await release.Task;
}, () => now);
var first = seek.SeekBy(10, 40, 100);
var second = seek.SeekBy(10, 40, 100);
var third = seek.SeekBy(10, 40, 100);
try {
    Check(seek.Target == 70, "连续三次+10未累加到70秒");
    seek.Observe(70, false, seek.Revision);
    Check(seek.Target == 70, "未提交目标被状态提前解除");
} finally { release.TrySetResult(); }
await Task.WhenAll(first, second, third);
Check(calls.SequenceEqual(new[] { 50.0, 70.0 }), "等待中的中间请求未合并");
seek.Observe(50, false, seek.Revision);
Check(seek.Target == 70, "旧落点覆盖新目标");
seek.Observe(70, true, seek.Revision);
Check(seek.Target == 70, "缓冲中解除目标");
seek.Observe(70, false, seek.Revision - 1);
Check(seek.Target == 70, "旧查询确认了新请求");
seek.Observe(70.5, false, seek.Revision);
Check(seek.Target is null, "真实位置到达后目标未解除");
await seek.SeekBy(-100, 70.5, 100);
Check(seek.Target == 0, "未钳制零点");
await seek.SeekBy(200, 70.5, 100);
Check(seek.Target == 100, "未钳制已知时长");
now += 14_999;
Check(!seek.Expire(), "超时过早释放");
now++;
Check(seek.Expire() && seek.Target is null, "15秒超时未释放");
await seek.SeekBy(10, 45, 100);
Check(seek.Target == 55, "超时后未恢复真实基准");
var count = calls.Count;
await seek.Seek(double.NaN, 100);
await seek.Seek(10, 0);
Check(calls.Count == count, "无效位置或未知量程提交跳转");

release = new TaskCompletionSource();
var events = new List<string>();
seek = new PlaybackSeek(async _ => { events.Add("seek"); await release.Task; });
first = seek.Seek(50, 100);
second = seek.Seek(70, 100);
var stop = seek.Stop(() => { events.Add("stop"); return Task.CompletedTask; });
try {
    await seek.Seek(90, 100);
    Check(seek.Target is null && events.SequenceEqual(new[] { "seek" }), "停止未失效队列或越过在途seek");
    Check(ReferenceEquals(stop, seek.Stop(() => throw new Exception("重复stop"))), "重复停止未共用回执");
} finally { release.TrySetResult(); }
await Task.WhenAll(first, second, stop);
Check(events.SequenceEqual(new[] { "seek", "stop" }), "旧排队请求越过停止");

release = new TaskCompletionSource();
count = 0;
seek = new PlaybackSeek(async _ => { if (++count == 1) { await release.Task; throw new Exception("跳转失败"); } });
first = seek.Seek(10, 100);
second = seek.Seek(20, 100);
release.SetResult();
try { await first; throw new Exception("失败被吞"); } catch (Exception e) when (e.Message == "跳转失败") { }
await second;
Check(seek.Target == 20, "旧失败清除了新目标");
seek = new PlaybackSeek(_ => throw new Exception("失败"));
try { await seek.Seek(20, 100); } catch (Exception e) when (e.Message == "失败") { }
Check(seek.Target is null, "失败未释放目标");
Console.WriteLine("跳转状态：累加/合并、边界、回执/缓冲/代数、超时、失败及停止屏障通过");

// 真页面和控件入口，命令回执可挂起；不把Headless验证当作实际mpv视频呈现。
AppBuilder.Configure<CheckApp>().UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false }).UseSkia().SetupWithoutStarting();
var root = Path.Combine(Path.GetTempPath(), "linplayer-seek-" + Guid.NewGuid());
Directory.CreateDirectory(root);
try {
    using var core = new CoreClient(Path.GetFullPath(args[0]), root, "test");
    var page = new PlayerPage(core, "", "跳转回归", 0, isSource: true);
    Get<DispatcherTimer>(page, "_poll").Stop();
    Set(Get<object>(page, "_view"), "OnReady", null);
    now = 1000;
    release = new TaskCompletionSource();
    calls.Clear();
    var state = new PlaybackSeek(async target => { calls.Add(target); if (calls.Count == 1) await release.Task; }, () => now);
    Set(page, "_seek", state);
    Set(page, "_duration", 100.0);
    Set(page, "_position", 40.0);
    var window = new Window { Width = 900, Height = 700, Content = page };
    try {
        window.Show(); Jobs();
        var forward = page.GetVisualDescendants().OfType<Button>().Single(b => ToolTip.GetTip(b) as string == "前进 10 秒(→)");
        forward.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        page.KeyFallback(Key.Right, KeyModifiers.None);
        page.KeyFallback(Key.Right, KeyModifiers.None);
        Check(state.Target == 70, "页面按钮/键盘未接累加入口");
        Check(Get<double>(page, "_position") == 40, "待跳转目标污染实际位置");
        Check(Get<TextBlock>(page, "_time").Text == "1:10", "待跳转时间未即时显示");
        release.SetResult(); Wait(() => calls.Count == 2, "页面排队命令未完成");
        Check(calls.SequenceEqual(new[] { 50.0, 70.0 }), "页面未合并过期请求");
        var bar = Get<PlayerBar>(page, "_bar");
        bar.Sync(40, 100, 80);
        window.UpdateLayout(); Jobs();
        window.MouseDown(new Point(bar.TranslatePoint(new Point(bar.Bounds.Width * .6, bar.Bounds.Height / 2), window)!.Value.X,
            bar.TranslatePoint(new Point(0, bar.Bounds.Height / 2), window)!.Value.Y), MouseButton.Left);
        bar.Sync(41, 100, 80);
        window.MouseUp(new Point(bar.TranslatePoint(new Point(bar.Bounds.Width * .6, bar.Bounds.Height / 2), window)!.Value.X,
            bar.TranslatePoint(new Point(0, bar.Bounds.Height / 2), window)!.Value.Y), MouseButton.Left);
        Jobs();
        Check(Math.Abs(calls.Last() - 60) < .1, "进度条指针松手未接绝对seek");
        now += 15_000;
        state.Expire();
        Invoke(page, "SyncSeekDisplay");
        Check(Get<TextBlock>(page, "_time").Text == "0:40", "超时后显示未恢复真实位置");
        Console.WriteLine("真实页面：按钮/键盘累加及合并、进度条指针、真实位置隔离和超时显示恢复通过");
    } finally { release.TrySetResult(); window.Content = null; window.Close(); Jobs(); }
} finally { Directory.Delete(root, true); }

static FieldInfo Field(object value, string name) => value.GetType().GetField(name, BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public)!;
static T Get<T>(object value, string name) => (T)Field(value, name).GetValue(value)!;
static void Set(object value, string name, object? data) => Field(value, name).SetValue(value, data);
static void Invoke(object value, string name) => value.GetType().GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(value, null);
static void Jobs() => Dispatcher.UIThread.RunJobs();
static void Wait(Func<bool> done, string error) {
    var deadline = Environment.TickCount64 + 5000;
    while (!done() && Environment.TickCount64 < deadline) { Jobs(); Thread.Sleep(10); }
    Jobs();
    if (!done()) throw new Exception(error);
}
sealed class CheckApp : Application {
    public override void Initialize() => Styles.Add(new FluentTheme());
}
