using System.Text.Json;
using LinPlayer.Desktop;

internal static class Program
{
    public static string DataDir => "";
    public static void Main()
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "privacy.json")));
        var root = doc.RootElement;
        foreach (var item in root.GetProperty("cases").EnumerateArray())
            if (Privacy.Scrub(item.GetProperty("input").GetString(), root.GetProperty("home").GetString()!, root.GetProperty("data_root").GetString()!) != item.GetProperty("expected").GetString())
                throw new Exception("三端脱敏语料不一致");
        if (!Telemetry.Probe()) throw new Exception("最终信封脱敏失败");
        Console.WriteLine("Windows 共用隐私语料及最终事件信封通过");
    }
}

namespace LinPlayer.Desktop.Views { internal static class Report { internal static bool Off => false; } }
