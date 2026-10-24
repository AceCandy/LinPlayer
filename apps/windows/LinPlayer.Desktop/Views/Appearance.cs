using Avalonia;
using Avalonia.Styling;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>本机明暗偏好；与壁纸外观一样留在 userdata，不修改核心业务偏好。</summary>
public static class Appearance
{
    public static string Mode { get; private set; } = "system";
    private static string FilePath => Path.Combine(Program.DataDir, "appearance.txt");

    public static void Load()
    {
        var mode = "system";
        try { mode = File.ReadAllText(FilePath).Trim(); }
        catch (FileNotFoundException) { /* 首次启动跟随系统。 */ }
        catch (Exception e) { Log.W("主题", "外观偏好读取失败，跟随系统:" + e.Message); }
        Apply(mode is "dark" or "light" ? mode : "system");
    }

    public static async Task Set(string mode)
    {
        if (mode is not ("system" or "dark" or "light"))
            throw new ArgumentException("未知的主题模式", nameof(mode));
        var path = FilePath;
        await Task.Run(() =>
        {
            var temp = path + ".tmp";
            try
            {
                File.WriteAllText(temp, mode);
                File.Move(temp, path, overwrite: true);
            }
            finally { if (File.Exists(temp)) File.Delete(temp); }
        });
        // 写入失败时保留当前外观，设置页显示错误而不是假成功。
        Apply(mode);
    }

    private static void Apply(string mode)
    {
        Mode = mode;
        if (Application.Current is { } app)
            app.RequestedThemeVariant = mode switch {
                "dark" => ThemeVariant.Dark,
                "light" => ThemeVariant.Light,
                _ => ThemeVariant.Default,
            };
    }
}
