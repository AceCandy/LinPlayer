using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>扩展作品只通过服务端实时精确匹配进入既有详情。</summary>
internal static class ExtensionNavigation
{
    internal static async Task Open(CoreClient core, Sess session, JsonElement entry, CancellationToken ct)
    {
        ct.ThrowIfCancellationRequested();
        if (Nav.Session != session) return;
        var hit = await core.CallAsync("emby.extResolve", new {
            session.server, session.token, session.user_id, session.device_id,
            source = RankingPage.Str(entry, "source"), external_id = RankingPage.Str(entry, "external_id"),
            media_type = RankingPage.Str(entry, "media_type"),
        }, ct);
        ct.ThrowIfCancellationRequested();
        if (Nav.Session != session) return;
        var id = RankingPage.Str(hit, "item_id");
        Nav.Push(new DetailPage(core, session.server, id), () => new DetailPage(core, session.server, id));
    }
}
