using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;

namespace LinPlayer.Desktop.Views;

/// <summary>继续观看的两路加载结果。失败只回退对应缓存,不能冒充成功空结果。</summary>
internal static class ResumeFeed
{
    internal sealed record Result(List<JsonElement> Items, List<JsonElement>? Resume,
        List<JsonElement>? NextUp, Exception? Error);

    internal static async Task<Result> Load(Func<Task<List<JsonElement>>> resume,
        Func<Task<List<JsonElement>>> nextUp, List<JsonElement>? cachedResume,
        List<JsonElement>? cachedNextUp)
    {
        var a = Read(resume);
        var b = Read(nextUp);
        var r = await a;
        var n = await b;
        var seen = new HashSet<string>();
        var items = new List<JsonElement>();
        foreach (var item in (r.Items ?? cachedResume ?? []).Concat(n.Items ?? cachedNextUp ?? []))
        {
            var series = Str(item, "series_id");
            var key = series is { Length: > 0 } ? "s:" + series : "i:" + Str(item, "id");
            if (seen.Add(key)) items.Add(item);
        }
        return new(items, r.Items, n.Items, r.Error ?? n.Error);
    }

    private static async Task<(List<JsonElement>? Items, Exception? Error)> Read(Func<Task<List<JsonElement>>> load)
    {
        try { return (await load(), null); }
        catch (Exception e) { return (null, e); }
    }

    private static string? Str(JsonElement item, string name) =>
        item.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;
}
