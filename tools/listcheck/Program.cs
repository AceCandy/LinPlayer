using System.Diagnostics;
using System.Reflection;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Threading;
using Avalonia.Themes.Fluent;
using Avalonia.VisualTree;
using LinPlayer.Desktop.Views;

AppBuilder.Configure<BenchApp>().UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false }).UseSkia().SetupWithoutStarting();
foreach (var count in new[] { 1000, 10000 })
foreach (var download in new[] { false, true })
{
    using var doc = JsonDocument.Parse("""{"id":"test","name":"测试视频","is_video":true,"status":"completed","title":"测试下载","total_bytes":1024,"received_bytes":1024}""");
    var item = doc.RootElement;
    var page = download ? (Control)new DownloadPage(null!) : new BrowsePage(null!, "本机");
    var flags = BindingFlags.Instance | BindingFlags.NonPublic;
    var row = page.GetType().GetMethod("Row", flags)!;
    var sw = Stopwatch.StartNew();
    var baseline = Enumerable.Range(0, count).Select(_ => (Control)row.Invoke(page, [item])!).ToList();
    Console.WriteLine($"{page.GetType().Name} {count} 项:全量建行 {sw.ElapsedMilliseconds}ms,控件 {baseline.Sum(c => c.GetVisualDescendants().Count() + 1)}");
    baseline.Clear();
    var list = (ItemsControl)page.GetType().GetField("_rows", flags)!.GetValue(page)!;
    var header = page.GetType().GetField("_head", flags)!.GetValue(page)!;
    if (!download)
    {
        page.GetType().GetField("_entries", flags)!.SetValue(page, Enumerable.Repeat(item, count).ToList());
        page.GetType().GetMethod("Render", flags)!.Invoke(page, null);
    }
    else list.ItemsSource = new[] { header }.Concat(Enumerable.Repeat<object>(item, count)).ToList();
    var window = new Window { Width = 800, Height = 600, Content = page };
    sw.Restart();
    window.Show();
    window.UpdateLayout();
    Dispatcher.UIThread.RunJobs();
    var visible = list.GetRealizedContainers().Count();
    Console.WriteLine($"  虚拟列表首屏 {sw.ElapsedMilliseconds}ms,实现行数 {visible}");
    if (visible <= 1 || visible > 100) throw new Exception("列表没有按视口虚拟化");
    list.ScrollIntoView(count);
    window.UpdateLayout();
    Dispatcher.UIThread.RunJobs();
    if (list.ContainerFromIndex(count) == null) throw new Exception("最后一项不可达");
    list.ScrollIntoView(0);
    window.UpdateLayout();
    Dispatcher.UIThread.RunJobs();
    if (list.ContainerFromIndex(0) == null || !page.GetVisualDescendants().Contains((Control)header))
        throw new Exception("滚回顶部后页头不可见");
    if (list.GetRealizedContainers().Count() > 100) throw new Exception("回收后容器数量持续增加");
    window.Close();
    Dispatcher.UIThread.RunJobs();
}
// 已加载第一页后先展示第二页缓存，再用不同长度的实时页替换，不能重复追加。
var paged = new MediaGrid(null!, "fixture", false);
CardItem Row(string id) => CardItem.From(JsonSerializer.SerializeToElement(new { id, name = id, type_ = "Movie" }));
paged.Append(new[] { Row("first"), Row("second") });
paged.ReplaceFrom(2, new[] { Row("old-a"), Row("old-b") });
paged.ReplaceFrom(2, new[] { Row("fresh") });
var records = (List<CardItem>)typeof(MediaGrid).GetField("_items", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(paged)!;
if (!records.Select(it => it.Id).SequenceEqual(new[] { "first", "second", "fresh" })) throw new Exception("缓存页刷新重复或损坏已加载前页");
paged.ReplaceFrom(2, Array.Empty<CardItem>());
if (paged.Count != 2) throw new Exception("成功空页未撤掉旧分页快照");
Console.WriteLine("缓存分页预览与实时替换检查通过");
Console.WriteLine("本机目录及下载列表规模检查通过");

sealed class BenchApp : Application
{
    public override void Initialize() => Styles.Add(new FluentTheme());
}
