using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Threading;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 换源(D233 D259 D261):在其它允许聚合的 Emby 服务器里找同一部,逐服出结果;
/// 按「同一部 / 可能是 / 不像(折叠)」,档内按接口耗时。选中后打开那台服务器上的详情。
/// </summary>
public static class SwitchSource
{
    public static async Task ShowForEmby(Visual anchor, CoreClient core, string itemId)
    {
        if (Nav.Session is not { } s) return;
        try
        {
            var d = await core.EmbyItemDetail(new { s.server, s.token, s.user_id, s.device_id, item_id = itemId, with_children = false });
            if (Nav.Session != s) return;
            var title = Mi.Str(d, "series_name") is { Length: > 0 } sn ? sn : Mi.Str(d, "name");
            Show(anchor, core, s, title, Mi.Num(d, "year"));
        }
        catch (Exception e) { Toast.Error("换源失败:" + LibraryPage.Advice(e)); }
    }

    private static void Show(Visual anchor, CoreClient core, Sess session, string title, double year)
    {
        var serverId = session.server;
        var list = new StackPanel { Spacing = 6, Width = 560 };
        var status = new TextBlock { Text = "正在其它服务器里找…", Classes = { "dim" } };
        var cands = new List<JsonElement>();
        var scroll = new ScrollViewer { MaxHeight = 520, Content = list };
        var body = new StackPanel { Spacing = 10, Children = { status, scroll } };
        Window? dlg = null;
        var window = TopLevel.GetTopLevel(anchor) as MainWindow;
        var picking = false;
        var closed = false;

        void Redraw()
        {
            list.Children.Clear();
            var sorted = cands.OrderBy(c => Mi.Num(c, "tier")).ThenBy(c => Mi.Num(c, "ms")).ToList();
            var folded = sorted.Where(c => (int)Mi.Num(c, "tier") == 2).ToList();
            foreach (var c in sorted.Where(c => (int)Mi.Num(c, "tier") < 2)) list.Children.Add(Row(c));
            if (folded.Count > 0)
            {
                var more = new Expander { Header = $"不像的 {folded.Count} 个", Content = new StackPanel { Spacing = 6 } };
                foreach (var c in folded) ((StackPanel)more.Content!).Children.Add(Row(c));
                list.Children.Add(more);
            }
        }

        Control Row(JsonElement c)
        {
            var tier = (int)Mi.Num(c, "tier");
            var tierText = tier == 0 ? "同一部" : tier == 1 ? "可能是" : "不像";
            var e = c.GetProperty("emby_item");
            var b = new Button
            {
                Classes = { "ghost" }, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Left,
                Content = new StackPanel { Spacing = 2, Children = {
                    new TextBlock { Text = Mi.Str(e, "name"), FontSize = 14 },
                    new TextBlock { Text = $"{Mi.Str(c, "server_name")} · {tierText}", Classes = { "dim" }, FontSize = 12 } } },
            };
            b.Click += async (_, _) =>
            {
                if (picking || closed) return;
                picking = true;
                list.IsEnabled = false;
                var switched = false;
                try
                {
                    var target = Mi.Str(c, "server_id");
                    var id = Mi.Str(e, "id");
                    if (target == "" || id == "") throw new InvalidOperationException("候选缺少服务器或条目标识");
                    if (Nav.Session != session) throw new InvalidOperationException("当前服务器已切换，请重新打开换源");

                    var accounts = await core.CallAsync("account.setActiveServer", new { server_id = target });
                    switched = true;
                    var selected = accounts.EnumerateArray().FirstOrDefault(a => Mi.Str(a, "server") == target && Mi.Bool(a, "active"));
                    var next = Sess.From(await core.CallAsync("emby.currentSession", null));
                    if (next is null || Mi.Str(selected, "line_url") != next.server || Mi.Str(selected, "user_id") != next.user_id)
                        throw new InvalidOperationException("目标服务器会话尚未就绪，请重试");

                    if (Nav.Session != session) return;
                    Nav.Session = next;
                    if (window != null) await window.OnServerSwitched();
                    if (Nav.Session != next) throw new InvalidOperationException("目标服务器会话已失效，请重试");
                    Nav.Root(new HomePage(core), () => new HomePage(core));
                    if (closed) return;
                    picking = false;
                    dlg?.Close();
                    Nav.Push(new DetailPage(core, next.server, id), () => new DetailPage(core, next.server, id));
                }
                catch (Exception ex)
                {
                    if (switched && Nav.Session == session)
                    {
                        Nav.Session = null;
                        if (window != null) await window.OnServerSwitched();
                        Nav.Root(new HomePage(core), () => new HomePage(core));
                    }
                    status.Text = "换源失败:" + LibraryPage.Advice(ex) + (switched ? "。请关闭后重新打开换源。" : "");
                }
                finally { picking = false; list.IsEnabled = true; }
            };
            return b;
        }

        _ = Task.Run(async () =>
        {
            try
            {
                var all = await core.CallStreamAsync("source.switchCandidates", new { server_id = serverId, title, year },
                    part => Dispatcher.UIThread.Post(() =>
                    {
                        if (part.ValueKind == JsonValueKind.Array) cands.AddRange(part.EnumerateArray());
                        Redraw();
                    }));
                Dispatcher.UIThread.Post(() =>
                {
                    cands.Clear();
                    if (all.ValueKind == JsonValueKind.Array) cands.AddRange(all.EnumerateArray());
                    Redraw();
                    status.Text = cands.Count == 0 ? "别的服务器里都没找到这部片。可以在服务器列表里给更多服务器打开「允许聚合」。" : $"找到 {cands.Count} 个候选";
                });
            }
            catch (Exception e) { Dispatcher.UIThread.Post(() => status.Text = "换源失败:" + LibraryPage.Advice(e)); }
        });
        dlg = new Window
        {
            Title = "换源", SizeToContent = SizeToContent.WidthAndHeight, CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Content = new Border { Padding = new Thickness(18), Child = body },
        };
        // 切服已提交时先完成会话交接，不能关窗后留下旧服务器页面。
        dlg.Closing += (_, e) => { if (picking) e.Cancel = true; };
        dlg.Closed += (_, _) => closed = true;
        if (TopLevel.GetTopLevel(anchor) is Window owner) _ = dlg.ShowDialog(owner);
    }
}
