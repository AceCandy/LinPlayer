using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;

namespace LinPlayer.Desktop.Views;

/// <summary>继续观看只读取续播列表；失败保留展示缓存，不混入未开始的下一集。</summary>
internal static class ResumeFeed
{
    internal sealed record Result(List<JsonElement> Items, Exception? Error);

    internal static async Task<Result> Load(Func<Task<List<JsonElement>>> resume, List<JsonElement>? cachedResume)
    {
        var r = await Read(resume);
        var seen = new HashSet<string>();
        var items = new List<JsonElement>();
        foreach (var item in r.Items ?? cachedResume ?? [])
        {
            var series = Str(item, "series_id");
            var key = series is { Length: > 0 } ? "s:" + series : "i:" + Str(item, "id");
            if (seen.Add(key)) items.Add(item);
        }
        return new(items, r.Error);
    }

    private static async Task<(List<JsonElement>? Items, Exception? Error)> Read(Func<Task<List<JsonElement>>> load)
    {
        try { return (await load(), null); }
        catch (Exception e) { return (null, e); }
    }

    private static string? Str(JsonElement item, string name) =>
        item.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;
}
