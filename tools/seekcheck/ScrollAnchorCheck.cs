using System.Reflection;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Markup.Xaml.Styling;
using Avalonia.Threading;
using Avalonia.VisualTree;
using LinPlayer.Desktop.Views;
using LinPlayer.Desktop.Core;

/// <summary>在真实文件浏览页反复筛选、滚动和重新挂载，验证虚拟列表锚点。</summary>
internal static class ScrollAnchorCheck
{
    internal static void Run()
    {
        var resources = new ResourceInclude(new Uri("avares://LinPlayer/")) {
            Source = new Uri("avares://LinPlayer/Theme/Tokens.axaml") };
        Application.Current!.Resources.MergedDictionaries.Add(resources);
        var window = new Window { Width = 900, Height = 650 };
        try {
            // 加载失败路径不需要核心；随后注入纯本地目录夹具，筛选走页面真实Render。
            var page = new BrowsePage(null!, "测试目录");
            using var data = JsonDocument.Parse(JsonSerializer.Serialize(Enumerable.Range(0, 120)
                .Select(i => new { id = i.ToString(), name = "项目" + i, is_dir = true })));
            Field("_entries").SetValue(page, data.RootElement.EnumerateArray().ToList());
            Field("_rawCount").SetValue(page, 120);
            var filter = (TextBox)Field("_filter").GetValue(page)!;
            window.Content = page; window.Show(); Layout();
            for (var pass = 0; pass < 4; pass++) {
                filter.Text = pass % 2 == 0 ? "项目" : "";
                Layout();
                var header = (Control)Field("_head").GetValue(page)!;
                if (header.GetVisualRoot() != window || !header.IsEffectivelyVisible)
                    throw new Exception("筛选后浏览页头部丢失");
                var scroll = page.GetVisualDescendants().OfType<ScrollViewer>().First();
                scroll.Offset = new Vector(0, 800); Layout();
                scroll.Offset = default; Layout();
                window.Content = null; Layout(); window.Content = page; Layout();
            }
            Console.WriteLine("滚动锚点：文件浏览页筛选刷新、滚动和离页返回通过");
        } finally { window.Close(); Application.Current.Resources.MergedDictionaries.Remove(resources); }
        static FieldInfo Field(string name) => typeof(BrowsePage).GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)!;
        void Layout() { window.UpdateLayout(); Dispatcher.UIThread.RunJobs(); }
    }

    internal static void RunDownloads(CoreClient core)
    {
        var resources = new ResourceInclude(new Uri("avares://LinPlayer/")) {
            Source = new Uri("avares://LinPlayer/Theme/Tokens.axaml") };
        Application.Current!.Resources.MergedDictionaries.Add(resources);
        var window = new Window { Width = 900, Height = 650 };
        try {
            var page = new DownloadPage(core);
            var refresh = typeof(DownloadPage).GetMethod("Refresh", BindingFlags.NonPublic | BindingFlags.Instance)!;
            var header = (Control)typeof(DownloadPage).GetField("_head", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(page)!;
            window.Content = page; window.Show();
            for (var pass = 0; pass < 4; pass++) {
                var pending = (Task)refresh.Invoke(page, null)!;
                var deadline = Environment.TickCount64 + 10_000;
                while (!pending.IsCompleted && Environment.TickCount64 < deadline) {
                    Dispatcher.UIThread.RunJobs(); Thread.Sleep(5);
                }
                if (!pending.IsCompleted) throw new Exception("下载列表刷新超时");
                pending.GetAwaiter().GetResult();
                window.UpdateLayout(); Dispatcher.UIThread.RunJobs();
                if (header.GetVisualRoot() != window || !header.IsEffectivelyVisible)
                    throw new Exception("下载刷新后头部丢失");
                window.Content = null; window.UpdateLayout();
                window.Content = page; window.UpdateLayout(); Dispatcher.UIThread.RunJobs();
            }
            Console.WriteLine("滚动锚点：下载页真实核心刷新及离页返回通过");
        } finally { window.Close(); Application.Current.Resources.MergedDictionaries.Remove(resources); }
    }

}
