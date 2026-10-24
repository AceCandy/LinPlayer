using System.Text.Json;
using LinPlayer.Desktop.Views;

List<JsonElement> Items(string id) => [JsonSerializer.SerializeToElement(new { id, series_id = (string?)null })];
Task<List<JsonElement>> Success(string id) => Task.FromResult(Items(id));
Task<List<JsonElement>> Fail() => throw new InvalidOperationException("请求失败");
void Check(bool ok, string why) { if (!ok) throw new Exception(why); }

var result = await ResumeFeed.Load(() => Success("new-resume"), Items("old-resume"));
Check(result.Items.Count == 1 && result.Items[0].GetProperty("id").GetString() == "new-resume" && result.Error is null, "实时续播未替换缓存");
result = await ResumeFeed.Load(Fail, Items("old-resume"));
Check(result.Items.Count == 1 && result.Error is not null, "失败丢失缓存或伪装成功");
result = await ResumeFeed.Load(Fail, null);
Check(result.Items.Count == 0 && result.Error is not null, "无缓存失败被当成没有内容");
result = await ResumeFeed.Load(() => Task.FromResult(new List<JsonElement>()), Items("old-resume"));
Check(result.Items.Count == 0 && result.Error is null, "成功空结果不能清空缓存");
var duplicate = JsonSerializer.SerializeToElement(new { id = "ep", series_id = "series" });
result = await ResumeFeed.Load(() => Task.FromResult(new List<JsonElement> { duplicate, duplicate }), null);
Check(result.Items.Count == 1, "同剧续播重复");
var streamed = new List<JsonElement>();
async Task<List<JsonElement>> SnapshotThenFail() {
    await Task.Yield(); streamed.AddRange(Items("cached-resume")); throw new InvalidOperationException("刷新失败");
}
result = await ResumeFeed.Load(SnapshotThenFail, streamed);
Check(result.Items.Count == 1 && result.Error is not null, "核心异步快照在失败时丢失");
Console.WriteLine("首页续播：成功、失败、缓存回退、真实空结果及同剧去重通过");
