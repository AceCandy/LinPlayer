using System.Reflection;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Markup.Xaml.Styling;
using Avalonia.Threading;
using Avalonia.VisualTree;
using LinPlayer.Desktop.Views;
using LinPlayer.Desktop.Core;

/// <summary>在真实下载页反复刷新和重新挂载，验证虚拟列表锚点。</summary>
internal static class ScrollAnchorCheck
{
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
