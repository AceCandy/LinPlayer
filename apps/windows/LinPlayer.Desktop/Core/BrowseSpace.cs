using System.Text.Json;

namespace LinPlayer.Desktop.Core;

/// <summary>本进程的浏览空间；分类与会话代次同时约束请求、流式结果和展示缓存。</summary>
internal static class BrowseSpace
{
    internal sealed record Scope(long Revision, bool IsPrivate, string Server, string User, string Token)
    {
        public string CachePrefix => $"{(IsPrivate ? "adult" : "normal")}-v1-{Revision}-";
        public bool IsCurrent => ReferenceEquals(this, Current);

        public void EnsureCurrent()
        {
            if (!IsCurrent) throw new OperationCanceledException("浏览空间或账号已切换");
        }

        public object? Arguments(string command, object? args)
        {
            var fields = args is null ? new Dictionary<string, JsonElement>()
                : JsonSerializer.SerializeToElement(args).EnumerateObject().ToDictionary(p => p.Name, p => p.Value.Clone());
            if (IsPrivate && (command.StartsWith("emby.watchHistory", StringComparison.Ordinal) ||
                command.StartsWith("download.", StringComparison.Ordinal) || command.StartsWith("plugin.", StringComparison.Ordinal) ||
                command.StartsWith("source.", StringComparison.Ordinal) ||
                command is "emby.aggregateOverview" or "emby.aggregateSearch" or "emby.aggregateVersions" or
                    "player.playLocal" or "prefs.pushSearch"))
                throw new CoreException("E_UNSUPPORTED", "隐私空间仅支持当前服务器，不使用本地记录、下载或插件", false);
            var filtered = command.StartsWith("emby.", StringComparison.Ordinal) || command is
                "source.aggregateSearch" or "prefs.preloadItem" or "player.play" or "player.prepareNext" or "player.queuePrepared";
            if (!filtered) return args;
            if (IsPrivate)
            {
                foreach (var (key, expected) in new[] { ("server", Server), ("user_id", User), ("token", Token), ("server_id", Server) })
                    if (fields.TryGetValue(key, out var value) && value.ValueKind == JsonValueKind.String &&
                        value.GetString() is { Length: > 0 } actual && actual != expected)
                        throw new CoreException("E_UNSUPPORTED", "隐私空间仅支持当前服务器和账号", false);
                fields["server"] = JsonSerializer.SerializeToElement(Server);
                fields["user_id"] = JsonSerializer.SerializeToElement(User);
                fields["token"] = JsonSerializer.SerializeToElement(Token);
            }
            fields["is_adult"] = JsonSerializer.SerializeToElement(IsPrivate);
            return fields;
        }
    }

    private static readonly object Gate = new();
    private static Scope _current = new(0, false, "", "", "");
    public static Scope Current => Volatile.Read(ref _current);
    public static bool IsPrivate => Current.IsPrivate;

    public static void SetSession(string server, string user, string token)
    {
        lock (Gate)
        {
            var old = Current;
            if ((old.Server, old.User, old.Token) == (server, user, token)) return;
            Volatile.Write(ref _current, new Scope(old.Revision + 1, false, server, user, token));
        }
        MetaCache.ClearMemory();
    }

    public static void Change(bool isPrivate, Scope? expected = null)
    {
        lock (Gate)
        {
            var old = Current;
            expected?.EnsureCurrent();
            if (old.IsPrivate == isPrivate) return;
            Volatile.Write(ref _current, old with { Revision = old.Revision + 1, IsPrivate = isPrivate });
        }
        MetaCache.ClearMemory();
    }
}
