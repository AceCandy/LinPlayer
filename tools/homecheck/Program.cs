using System.Text.Json;
using LinPlayer.Desktop.Views;

List<JsonElement> Items(string id) => [JsonSerializer.SerializeToElement(new { id, series_id = (string?)null })];
Task<List<JsonElement>> Success(string id) => Task.FromResult(Items(id));
Task<List<JsonElement>> Fail() => throw new InvalidOperationException("请求失败");
void Check(bool ok, string why) { if (!ok) throw new Exception(why); }

var result = await ResumeFeed.Load(() => Success("new-resume"), Fail, Items("old-resume"), Items("old-next"));
Check(result.Items.Count == 2 && result.Items[1].GetProperty("id").GetString() == "old-next", "单路失败丢失另一侧缓存");
Check(result.Resume is not null && result.NextUp is null && result.Error is not null, "部分失败被当成完整成功");

result = await ResumeFeed.Load(Fail, Fail, Items("old-resume"), Items("old-next"));
Check(result.Items.Count == 2 && result.Error is not null && result.Resume is null && result.NextUp is null, "双路失败清空缓存或伪装正常空结果");

result = await ResumeFeed.Load(Fail, Fail, null, null);
Check(result.Items.Count == 0 && result.Error is not null, "无缓存失败被当成没有内容");

result = await ResumeFeed.Load(() => Task.FromResult(new List<JsonElement>()), () => Task.FromResult(new List<JsonElement>()), Items("old-resume"), Items("old-next"));
Check(result.Items.Count == 0 && result.Error is null && result.Resume is not null && result.NextUp is not null, "真实成功空结果不能清空缓存");

result = await ResumeFeed.Load(() => Success("same"), () => Success("same"), null, null);
Check(result.Items.Count == 1, "两路结果重复");
Console.WriteLine("首页两路加载:成功、部分失败、双路失败、缓存回退及去重通过");
