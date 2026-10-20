using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 人物详情(<c>UI_PC.md</c> §7.16):头像 + 简介 + 参演作品网格。
///
/// <para>详情与作品<b>并发</b>拉。串起来的话这一页要等两个往返 ——
/// 而它们互不依赖。</para>
///
/// <para>资料与作品分别报错重试，作品按服务端游标分页。</para>
/// </summary>
public sealed class PersonPage : PageBase
{
    private readonly CoreClient _core;
    private readonly string _server;
    private readonly Sess? _session = Nav.Session;
    private bool _detached;
    private int _detailRevision;
    private bool DisplayCurrent => !_detached && SpaceCurrent && Nav.Session == _session;
    private CancellationTokenSource _lifetime = new();
    private CancellationTokenSource? _worksRequest;
    private readonly string _personId;
    private readonly MediaGrid _grid;
    private readonly TextBlock _status = Dim("");
    private readonly Button _retry = new() { Content = "重试作品", IsVisible = false };
    private readonly Button _movies = new() { Content = "电影", Classes = { "ghost" } };
    private readonly Button _series = new() { Content = "剧集", Classes = { "ghost" } };
    private readonly ScrollViewer _scroll;
    private readonly List<CardItem> _items = [];
    private string _type = "Movie";
    private int _next, _generation;
    private bool _more = true, _busy, _detailLoaded;
    private bool _refresh;

    public PersonPage(CoreClient core, string server, string personId, string fallbackName)
    {
        _core = core;
        _server = server;
        _personId = personId;
        _grid = new MediaGrid(core, server, false, LibraryPage.OpenDetail(core, server));

        var body = new StackPanel { Spacing = 18 };
        var back = new Button { Classes = { "ghost" }, Content = "← 返回", HorizontalAlignment = HorizontalAlignment.Left };
        back.Click += (_, _) => Nav.Back();
        body.Children.Add(back);

        var head = new StackPanel { Spacing = 18, Orientation = Orientation.Horizontal };
        var title = new TextBlock { Text = fallbackName, Classes = { "h1" } };
        var meta = Dim("");
        var bio = new TextBlock { FontSize = 13, TextWrapping = TextWrapping.Wrap, LineHeight = 21, MaxWidth = 900 };

        var avatar = new Border
        {
            Width = 140, Height = 140, CornerRadius = new CornerRadius(999), ClipToBounds = true,
            Background = Tok.Of("PanelAlt"),
            VerticalAlignment = VerticalAlignment.Top,
        };
        head.Children.Add(avatar);
        head.Children.Add(new StackPanel
        {
            Spacing = 10, Children = { title, meta, bio },
        });
        body.Children.Add(head);
        var detailRetry = new Button { Content = "重试人物资料", IsVisible = false };
        detailRetry.Click += (_, _) => { _ = LoadDetail(personId, title, meta, bio, avatar, detailRetry); };
        body.Children.Add(detailRetry);

        body.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10,
            Children = { _movies, _series } });
        body.Children.Add(_status);
        body.Children.Add(_grid);
        body.Children.Add(_retry);
        _scroll = Scrolled(body);
        Content = _scroll;
        _movies.Click += (_, _) => SelectType("Movie");
        _series.Click += (_, _) => SelectType("Series");
        _retry.Click += (_, _) => { _retry.IsVisible = false; _ = LoadWorks(); };
        _scroll.ScrollChanged += (_, _) => MaybeLoadMore();
        UpdateTypes();
        DetachedFromVisualTree += (_, _) => {
            _detached = true; _generation++; _detailRevision++;
            _lifetime.Cancel(); _worksRequest?.Cancel(); _busy = false;
            _grid.ReplaceFrom(0, _items);
        };
        AttachedToVisualTree += (_, _) => {
            _detached = false;
            _lifetime.Dispose(); _lifetime = new CancellationTokenSource();
            if (!DisplayCurrent) return;
            if (!_detailLoaded) _ = LoadDetail(personId, title, meta, bio, avatar, detailRetry);
            if (_next == 0 || _refresh) _ = LoadWorks();
            else MaybeLoadMore();
        };
        WatchUserState(core, () => SelectType(_type, refresh: true));
    }

    private async Task LoadDetail(string id, TextBlock title, TextBlock meta, TextBlock bio, Border avatar, Button retry)
    {
        var ct = _lifetime.Token;
        retry.IsVisible = false;
        JsonElement d;
        try
        {
            var s = _session!;
            d = await _core.CallDisplayAsync("emby.personDetail", new { s.server, s.token, s.user_id, s.device_id, person_id = id },
                value => { _ = PaintDetail(value, title, meta, bio, avatar, ct); }, ct, retainOnFailure: false);
        }
        catch (OperationCanceledException) { return; } // 离页取消，不向新页面报错。
        catch (Exception e)
        {
            Dispatcher.UIThread.Post(() => {
                if (!DisplayCurrent || ct.IsCancellationRequested) return;
                meta.Text = LibraryPage.Advice(e); meta.IsVisible = true; retry.IsVisible = true;
            });
            return;
        }

        if (!DisplayCurrent || ct.IsCancellationRequested) return;
        await PaintDetail(d, title, meta, bio, avatar, ct);
        if (DisplayCurrent && !ct.IsCancellationRequested) _detailLoaded = true;
    }

    private async Task PaintDetail(JsonElement d, TextBlock title, TextBlock meta, TextBlock bio, Border avatar, CancellationToken ct)
    {
        if (!DisplayCurrent || ct.IsCancellationRequested) return;
        var revision = ++_detailRevision;
        var name = Str(d, "name");
        var overview = Str(d, "overview");
        // 生卒 / 出生地**空是常态**:很多刮削器根本不写这几项。
        // 空的时候整行不出现,而不是显示「生日:」后面跟一片空白。
        var bits = new List<string>();
        if (Str(d, "birthday") is { Length: > 0 } bd) bits.Add("生于 " + Date(bd));
        if (Str(d, "death_day") is { Length: > 0 } dd) bits.Add("卒于 " + Date(dd));
        if (Str(d, "birth_place") is { Length: > 0 } bp) bits.Add(bp);

        Dispatcher.UIThread.Post(() =>
        {
            if (!DisplayCurrent || ct.IsCancellationRequested || revision != _detailRevision) return;
            if (name.Length > 0) title.Text = name;
            meta.Text = string.Join("  ·  ", bits);
            meta.IsVisible = bits.Count > 0;
            // 「没有生平」要说出来,别留一片空白让人以为没加载完
            bio.Text = overview.Length > 0 ? overview : "这个人物没有生平简介。";
            bio.Opacity = overview.Length > 0 ? 1 : 0.55;
        });

        if (Bool(d, "has_primary"))
        {
            var im = new Image { Stretch = Stretch.UniformToFill };
            var bmp = await Images.LoadAsync(_core, Images.EmbyImageUrl(_server, Str(d, "id"), "Primary"), 280);
            if (bmp is null) return;
            Dispatcher.UIThread.Post(() =>
            {
                if (!DisplayCurrent || ct.IsCancellationRequested || revision != _detailRevision) return;
                im.Source = bmp;
                avatar.Child = im;
            });
        }
    }

    private void UpdateTypes()
    {
        _movies.IsEnabled = _type != "Movie";
        _series.IsEnabled = _type != "Series";
    }

    private void SelectType(string type, bool refresh = false)
    {
        if (!refresh && type == _type) return;
        _worksRequest?.Cancel(); _generation++; _busy = false;
        _refresh = refresh;
        if (!refresh) {
            _type = type; _next = 0; _more = true; _items.Clear(); _grid.Clear();
            _scroll.Offset = default;
        }
        _retry.IsVisible = false; UpdateTypes();
        _ = LoadWorks();
    }

    private void MaybeLoadMore()
    {
        if (DisplayCurrent && !_retry.IsVisible &&
            (_items.Count == 0 || _scroll.Offset.Y + _scroll.Viewport.Height >= _scroll.Extent.Height - 600))
            _ = LoadWorks();
    }

    private async Task LoadWorks()
    {
        if (!DisplayCurrent || _busy || (!_more && !_refresh)) return;
        _busy = true;
        var generation = _generation;
        _worksRequest?.Dispose();
        _worksRequest = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        var ct = _worksRequest.Token;
        var offset = _refresh ? 0 : _items.Count;
        bool Current() => DisplayCurrent && generation == _generation && !ct.IsCancellationRequested;
        _status.Text = "加载中…";
        try
        {
            var s = _session!;
            var start = _refresh ? 0 : _next;
            var fresh = new List<CardItem>();
            var seen = _items.Take(offset).Select(it => it.Id).ToHashSet();
            bool more;
            string? total;
            do {
                var page = await _core.CallDisplayAsync("emby.personItemsPage", new
                {
                    s.server, s.token, s.user_id, s.device_id, person_id = _personId,
                    type = _type, start_index = start, limit = 30,
                }, value => {
                    if (Current() && !_refresh) _grid.ReplaceFrom(offset, Items(value));
                }, ct, retainOnFailure: false);
                if (!Current()) return;
                var raw = Items(page);
                var got = raw.Where(it => seen.Add(it.Id)).ToList();
                var next = page.GetProperty("next_index").GetInt32();
                more = Bool(page, "has_more");
                if ((more && next <= start) || (raw.Count > 0 && got.Count == 0))
                    throw new InvalidOperationException("服务器没有推进人物作品分页");
                fresh.AddRange(got); start = next;
                total = page.TryGetProperty("total", out var count) && count.ValueKind == JsonValueKind.Number
                    ? count.GetInt64().ToString() : null;
            } while (_refresh && more && start < _next);
            // 刷新已加载范围全部成功后才替换，失败保留旧列表和滚动位置。
            var restoreOffset = _refresh ? _scroll.Offset : (Vector?)null;
            if (_refresh) _items.Clear();
            _items.AddRange(fresh); _next = start; _more = more; _refresh = false;
            _grid.ReplaceFrom(offset, fresh);
            if (restoreOffset is { } saved) Dispatcher.UIThread.Post(() => {
                if (Current()) _scroll.Offset = saved;
            }, DispatcherPriority.Loaded);
            _status.Text = _items.Count == 0 && !_more ? "没有找到该分类的作品。" :
                $"已加载 {_items.Count}" + (total is null ? "" : $" / {total}") + " · 上映时间从新到旧";
        }
        catch (OperationCanceledException) { } // 离页或切分类取消，旧页不再交付。
        catch (Exception e)
        {
            if (!Current()) return;
            if (e is CoreException { Code: "E_AUTH" or "E_PERMISSION" or "E_NOTFOUND" or "E_INVALID" or "E_UNSUPPORTED" }) {
                _items.Clear(); _next = 0; _more = true;
            }
            _grid.ReplaceFrom(0, _items);
            _status.Text = LibraryPage.Advice(e); _retry.IsVisible = true;
        }
        finally {
            if (Current()) {
                _busy = false;
                Dispatcher.UIThread.Post(MaybeLoadMore, DispatcherPriority.Background);
            }
        }
    }

    private static List<CardItem> Items(JsonElement page) =>
        page.TryGetProperty("items", out var items) && items.ValueKind == JsonValueKind.Array
            ? items.EnumerateArray().Select(CardItem.From).ToList() : [];

    /// <summary>ISO 日期只取 yyyy-MM-dd —— 服务端给的是带时区的完整时间戳。</summary>
    private static string Date(string iso) => iso.Length >= 10 ? iso[..10] : iso;

    private static string Str(JsonElement e, string k) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() ?? "" : "";
    private static bool Bool(JsonElement e, string k) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.True;
}
