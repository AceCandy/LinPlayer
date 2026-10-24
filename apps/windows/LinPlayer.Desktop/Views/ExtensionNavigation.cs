using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Controls;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>扩展作品只通过服务端实时精确匹配进入既有详情。</summary>
internal static class ExtensionNavigation
{
    internal static async Task<string> PrimaryServer(CoreClient core, CancellationToken ct)
    {
        var accounts = await core.CallAsync("account.listAccounts", null, ct);
        if (accounts.ValueKind == JsonValueKind.Null) return "";
        foreach (var account in accounts.EnumerateArray())
            if (Mi.Bool(account, "aggregate_primary")) return Mi.Str(account, "server");
        return "";
    }

    internal static async Task Open(CoreClient core, Sess session, JsonElement entry, CancellationToken ct,
        string serverId = "", Control? owner = null)
    {
        ct.ThrowIfCancellationRequested();
        if (Nav.Session != session) return;
        var hit = await core.CallAsync("emby.extResolve", new {
            session.server, session.token, session.user_id, session.device_id,
            server_id = serverId,
            source = RankingPage.Str(entry, "source"), external_id = RankingPage.Str(entry, "external_id"),
            media_type = RankingPage.Str(entry, "media_type"),
        }, ct);
        ct.ThrowIfCancellationRequested();
        if (Nav.Session != session) return;
        var id = RankingPage.Str(hit, "item_id");
        if (id == "") throw new System.InvalidOperationException("服务器未返回匹配条目");
        if (serverId != "") {
            var window = owner is null ? null : TopLevel.GetTopLevel(owner) as MainWindow;
            // 切服是已提交的全局操作；完成宿主同步后再决定是否继续本页导航。
            var accounts = await core.CallAsync("account.setActiveServer", new { server_id = serverId });
            var target = accounts.EnumerateArray().First(a => Mi.Str(a, "server") == serverId && Mi.Bool(a, "active"));
            var next = Sess.From(await core.CallAsync("emby.currentSession", null));
            if (Nav.Session != session) return;
            Nav.Session = next;
            if (window != null) await window.OnServerSwitched();
            ct.ThrowIfCancellationRequested();
            if (Nav.Session != next) return;
            if (next is null || next.server != Mi.Str(target, "line_url") || next.user_id != Mi.Str(target, "user_id"))
                throw new System.InvalidOperationException("目标服务器会话尚未就绪，请重试");
            Nav.Root(new HomePage(core), () => new HomePage(core));
            session = next;
        }
        Nav.Push(new DetailPage(core, session.server, id), () => new DetailPage(core, session.server, id));
    }
}
