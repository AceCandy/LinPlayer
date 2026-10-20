using System.Reflection;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Markup.Xaml.Styling;
using LinPlayer.Desktop.Views;

/// <summary>构建真实主窗口服务器列表，覆盖当前账号的隐私菜单扩展与重复刷新。</summary>
internal static class ServerMenuCheck
{
    internal static void Run()
    {
        var resources = new ResourceInclude(new Uri("avares://LinPlayer/")) {
            Source = new Uri("avares://LinPlayer/Theme/Tokens.axaml") };
        Application.Current!.Resources.MergedDictionaries.Add(resources);
        var window = new MainWindow();
        try {
            using var data = JsonDocument.Parse("""
                [{"server":"active.test","name":"当前服","active":true},
                 {"server":"other.test","name":"其他服","active":false}]
                """);
            var rows = data.RootElement.EnumerateArray().ToList();
            var build = typeof(MainWindow).GetMethod("BuildServerList", BindingFlags.NonPublic | BindingFlags.Instance)!;
            for (var pass = 0; pass < 2; pass++) {
                build.Invoke(window, [rows]);
                var buttons = window.FindControl<StackPanel>("ServerList")!.Children.OfType<Button>().Where(b => b.ContextMenu is not null).ToList();
                if (buttons.Count != 2) throw new Exception("服务器列表条目不正确");
                for (var i = 0; i < buttons.Count; i++) {
                    var count = buttons[i].ContextMenu!.Items.OfType<MenuItem>().Count(m => m.Header as string == "进入隐私空间");
                    if (count != (i == 0 ? 1 : 0)) throw new Exception("隐私入口不符合当前服务器边界");
                }
            }
            Console.WriteLine("主窗口服务器菜单：当前/其他账号及重复构建通过");
        } finally { window.Close(); Application.Current.Resources.MergedDictionaries.Remove(resources); }
    }
}
