using System.Text.Json;
using LinPlayer.Desktop.Core;

var root = Path.Combine(Path.GetTempPath(), "metadata-check-" + Guid.NewGuid());
var dir = Path.Combine(root, "cache", "meta");
Directory.CreateDirectory(dir);
try
{
    // 旧快照不按年龄清理；写入第 1001 条时只淘汰最旧的一条。
    for (var i = 0; i < 1000; i++)
    {
        var path = Path.Combine(dir, $"old-{i}.json");
        File.WriteAllText(path, "[]");
        File.SetLastWriteTimeUtc(path, DateTime.UtcNow.AddDays(-30).AddSeconds(i));
    }
    MetaCache.Init(root);
    for (var i = 0; i < 1000; i++)
        if (MetaCache.Peek($"old-{i}") is null) throw new Exception("旧快照读取失败");
    MetaCache.Peek("old-0");
    using var doc = JsonDocument.Parse("[]");
    MetaCache.Put("new", doc.RootElement);
    var until = DateTime.UtcNow.AddSeconds(10);
    while (DateTime.UtcNow < until && (!File.Exists(Path.Combine(dir, "new.json")) || Directory.GetFiles(dir, "*.json").Length != 1000))
        await Task.Delay(10);
    if (Directory.GetFiles(dir, "*.json").Length != 1000 || File.Exists(Path.Combine(dir, "old-0.json")) ||
        !File.Exists(Path.Combine(dir, "old-1.json"))) throw new Exception("旧快照保留或1000条淘汰失败");
    // 移除磁盘回退后检查内存：最近读取的条目保留，最久未读的条目已淘汰。
    if (MetaCache.Peek("old-0") is null) throw new Exception("最近读取的内存快照被淘汰");
    File.Delete(Path.Combine(dir, "old-1.json"));
    if (MetaCache.Peek("old-1") is not null) throw new Exception("内存未遵守1000条上限");
    Console.WriteLine("桌面展示缓存长期保留与1000条淘汰检查通过");
}
finally { Directory.Delete(root, true); }

namespace LinPlayer.Desktop.Core
{
    // 此检查仅加载真实缓存实现，不启动核心或网络服务。
    public sealed class CoreClient
    {
        public Task<JsonElement> CallAsync(string command, object? args) => throw new NotSupportedException();
    }
}
