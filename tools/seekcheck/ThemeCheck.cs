using Avalonia;
using Avalonia.Controls;
using Avalonia.Markup.Xaml.Styling;
using Avalonia.Media;
using Avalonia.Styling;
using Avalonia.Threading;
using LinPlayer.Desktop.Views;

static class ThemeCheck
{
    public static void Run()
    {
        var app = Application.Current!;
        app.Resources.MergedDictionaries.Add(new ResourceInclude(new Uri("avares://LinPlayer/")) {
            Source = new Uri("avares://LinPlayer/Theme/Tokens.axaml"),
        });
        app.RequestedThemeVariant = ThemeVariant.Dark;
        var text = new TextBlock { Text = "主题回归", Foreground = Tok.Of("Ink") };
        var panel = new Border { Background = Tok.Of("Bg"), Child = text };
        var window = new Window { Content = panel };
        window.Show();
        var dataDir = typeof(Tok).Assembly.GetType("LinPlayer.Desktop.Program")!.GetProperty("DataDir")!;
        var originalDir = dataDir.GetValue(null);
        var root = Path.Combine(Path.GetTempPath(), "linplayer-theme-" + Guid.NewGuid());
        Directory.CreateDirectory(root);
        dataDir.SetValue(null, root);
        void Check(bool ok, string message) { if (!ok) throw new Exception(message); }
        Color ColorOf(IBrush? brush) => ((ISolidColorBrush)brush!).Color;
        try
        {
            Check(ColorOf(panel.Background) == Color.Parse("#0D0C12"), "默认深色背景未对齐手机");
            Check(ColorOf(Tok.Of("Accent")) == Color.Parse("#F4AD39"), "默认强调色未对齐手机");
            app.RequestedThemeVariant = ThemeVariant.Light;
            Dispatcher.UIThread.RunJobs();
            Check(ColorOf(panel.Background) == Color.Parse("#FAF7FC"), "已创建面板未跟随浅色切换");
            Check(ColorOf(text.Foreground) == Color.Parse("#1A1622"), "已创建文字未跟随浅色切换");
            app.RequestedThemeVariant = ThemeVariant.Dark;
            Dispatcher.UIThread.RunJobs();
            Check(ColorOf(panel.Background) == Color.Parse("#0D0C12"), "已创建面板未恢复深色");
            void Save(string mode)
            {
                var task = Dispatcher.UIThread.InvokeAsync(() => Appearance.Set(mode));
                var deadline = Environment.TickCount64 + 5000;
                while (!task.IsCompleted && Environment.TickCount64 < deadline) {
                    Dispatcher.UIThread.RunJobs(); Thread.Sleep(5);
                }
                Check(task.IsCompleted, "外观保存超时");
                task.GetAwaiter().GetResult();
            }
            Save("light");
            app.RequestedThemeVariant = ThemeVariant.Dark;
            Appearance.Load();
            Check(app.RequestedThemeVariant == ThemeVariant.Light, "重载未恢复本机明暗偏好");
            var privacy = typeof(Tok).Assembly.GetType("LinPlayer.Desktop.Views.PrivacyTheme")!;
            privacy.GetMethod("Apply")!.Invoke(null, new object[] { true });
            Check(ColorOf(panel.Background) == Color.Parse("#EFE5F7"), "浅色私密空间未覆盖已有面板");
            Save("dark");
            Check(ColorOf(panel.Background) == Color.Parse("#21152D"), "私密空间未跟随明暗切换");
            privacy.GetMethod("Apply")!.Invoke(null, new object[] { false });
            Check(ColorOf(panel.Background) == Color.Parse("#0D0C12"), "退出私密未恢复普通模式");
            Save("system");
            Check(app.RequestedThemeVariant == ThemeVariant.Default, "跟随系统未解除强制明暗");
            var stored = File.ReadAllText(Path.Combine(root, "appearance.txt"));
            try { Save("invalid"); throw new Exception("非法模式被接受"); }
            catch (ArgumentException) { }
            Check(File.ReadAllText(Path.Combine(root, "appearance.txt")) == stored, "非法模式覆盖了偏好");
            dataDir.SetValue(null, Path.Combine(root, "missing"));
            try { Save("dark"); throw new Exception("写入失败未上报"); }
            catch (DirectoryNotFoundException) { }
            Check(Appearance.Mode == "system", "写入失败改变了当前模式");
            Console.WriteLine("主题：手机默认配色、已创建控件明暗往返、持久化、私密恢复与保存失败通过");
        }
        finally {
            window.Close();
            dataDir.SetValue(null, originalDir);
            Directory.Delete(root, true);
        }
    }
}
