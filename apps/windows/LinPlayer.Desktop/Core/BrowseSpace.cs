using System.Text.Json;

namespace LinPlayer.Desktop.Core;

/// <summary>本进程的浏览空间；分类与会话代次同时约束请求、流式结果和展示缓存。</summary>
internal static class BrowseSpace
{
    internal sealed record Scope(long Revision, bool IsPrivate, string Server, string User, string Token)
    {
        public bool IsCurrent => ReferenceEquals(this, Current);

        public void EnsureCurrent()
        {
            if (!IsCurrent) throw new OperationCanceledException("浏览空间或账号已切换");
        }

        public object? Arguments(string command, object? args)
        {
            var fields = args is null ? new Dictionary<string, JsonElement>()
                : JsonSerializer.SerializeToElement(args).EnumerateObject().ToDictionary(p => p.Name, p => p.Value.Clone());
            fields["request_scope"] = JsonSerializer.SerializeToElement(new {
                id = Revision.ToString(System.Globalization.CultureInfo.InvariantCulture),
                @private = IsPrivate, filter = true, server = Server, user_id = User, token = Token,
            });
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
    }
}
