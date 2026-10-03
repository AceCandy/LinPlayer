using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace LinPlayer.Desktop;

internal static class Privacy
{
    private const string Keys = "api_key|apikey|x-emby-token|x-mediabrowser-token|token|access_token|refresh_token|pw|password|passwd|sign|authorization";
    private static readonly Regex Secret = new($@"(?i)\b({Keys})([""']?\s*[=:]\s*[""']?)(?:bearer\s+)?[^&\s""'<>,;]+");
    private static readonly Regex SecretKey = new($"^(?:{Keys})$", RegexOptions.IgnoreCase);
    private static readonly Regex JsonSecret = new($@"(?i)(""(?:{Keys})""\s*:\s*"")(?:[^""\\]|\\.)*("")");
    private static readonly Regex Host = new(@"(?i)\b(https?|wss?)://[^/\s""'<>]+");
    private static readonly Regex LocalAsset = new(@"(?i)(https?://(?:localhost|127\.0\.0\.1|\[::1\])(?::[0-9]+)?/p/)[^/\s""'<>]+");

    internal static string? Scrub(string? value, string home, string dataRoot)
    {
        if (string.IsNullOrEmpty(value)) return value;
        foreach (var (path, replacement) in new[] { (dataRoot, "<data>"), (home, "~") })
            if (path.Length >= 4) value = value.Replace(path, replacement, StringComparison.OrdinalIgnoreCase);
        value = JsonSecret.Replace(value, "${1}<redacted>${2}");
        value = Secret.Replace(value, "${1}${2}<redacted>");
        value = LocalAsset.Replace(value, "${1}<redacted>");
        return Host.Replace(value, m => Uri.TryCreate(m.Value, UriKind.Absolute, out var u) && u.UserInfo.Length == 0 &&
            (u.Host.Equals("localhost", StringComparison.OrdinalIgnoreCase) || u.Host is "127.0.0.1" or "[::1]" or "::1")
            ? m.Value : m.Groups[1].Value + "://<host>");
    }

    // 遍历最终事件的全部字段,避免仅处理 message 而漏掉请求、面包屑或嵌套数据。
    internal static JsonNode? ScrubJson(JsonNode? node, string home, string dataRoot)
    {
        if (node is JsonObject obj)
            foreach (var (key, value) in obj.ToArray())
            {
                var clean = SecretKey.IsMatch(key) ? JsonValue.Create("<redacted>") : ScrubJson(value, home, dataRoot);
                if (!ReferenceEquals(value, clean)) obj[key] = clean;
            }
        else if (node is JsonArray array)
            for (var i = 0; i < array.Count; i++)
            {
                var clean = ScrubJson(array[i], home, dataRoot);
                if (!ReferenceEquals(array[i], clean)) array[i] = clean;
            }
        else if (node is JsonValue val && val.TryGetValue<string>(out var text)) return JsonValue.Create(Scrub(text, home, dataRoot));
        return node;
    }
}
