using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>只播放可见入库作品的原生流影；元数据不承担播放授权。</summary>
public sealed class NativeLiuyingPage : PageBase
{
    private readonly CoreClient _core;
    private readonly Sess? _session = Nav.Session;
    private readonly CancellationTokenSource _lifetime = new();
    private CancellationTokenSource? _request;
    private readonly ContentControl _video = new();
    private readonly StackPanel _tags = new() { Orientation = Orientation.Horizontal, Spacing = 8 };
    private readonly TextBlock _status = Dim("读取服务器能力…");
    private readonly Button _feed = new() { Content = "普通 / 切换18+", Classes = { "primary" } };
    private readonly Button _tab = new() { Content = "舞蹈 / 切换直播", Classes = { "ghost" } };
    private readonly Button _favorites = new() { Content = "全部作品", Classes = { "ghost" } };
    private readonly Button _favorite = new() { Content = "收藏", Classes = { "ghost" } };
    private readonly Button _next = new() { Content = "换一个", Classes = { "primary" } };
    private readonly Button _retry = new() { Content = "重试", Classes = { "ghost" }, IsVisible = false };
    private List<JsonElement> _rows = [];
    private List<JsonElement> _labels = [];
    private int _index, _offset;
    private string _seed = Guid.NewGuid().ToString("N"), _feedKey = "normal", _tabKey = "dance", _channel = "", _exposure = "";
    private bool _enabled, _loading, _more, _onlyFavorite, _favoriteBusy, _started;
    private bool Current => !_lifetime.IsCancellationRequested && SpaceCurrent && BrowseSpace.IsPrivate && Nav.Session == _session;

    public NativeLiuyingPage(CoreClient core)
    {
        _core = core;
        var back = new Button { Content = "返回", Classes = { "ghost" } };
        back.Click += (_, _) => Nav.Back();
        var controls = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8,
            Children = { back, _feed, _tab, _favorites, _favorite, _next, _retry } };
        var layout = new Grid { RowDefinitions = new("Auto,Auto,Auto,*") };
        var header = new ScrollViewer { Content = controls, HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto, Margin = new Thickness(18, 8) };
        layout.Children.Add(header);
        var tags = new ScrollViewer { Content = _tags, HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto, Margin = new Thickness(18, 0) };
        Grid.SetRow(tags, 1); layout.Children.Add(tags);
        _status.Margin = new Thickness(18, 8); Grid.SetRow(_status, 2); layout.Children.Add(_status);
        Grid.SetRow(_video, 3); layout.Children.Add(_video);
        Content = layout;
        _feed.Click += async (_, _) => {
            _feedKey = _feedKey == "normal" ? "adult" : "normal";
            _tabKey = _feedKey == "normal" ? "dance" : ""; _channel = ""; _exposure = "";
            await ResetAsync();
        };
        _tab.Click += async (_, _) => { _tabKey = _tabKey == "dance" ? "live" : "dance"; _channel = ""; _exposure = ""; await ResetAsync(); };
        _favorites.Click += async (_, _) => { _onlyFavorite = !_onlyFavorite; await ResetAsync(); };
        _favorite.Click += async (_, _) => await FavoriteAsync();
        _next.Click += async (_, _) => await NextAsync();
        _retry.Click += async (_, _) => await LoadAsync();
        AttachedToVisualTree += async (_, _) => { if (!_started) { _started = true; await InitializeAsync(); } };
        DetachedFromVisualTree += (_, _) => { _lifetime.Cancel(); _request?.Cancel(); _video.Content = null; };
        UpdateControls();
    }

    private static bool IsTrue(JsonElement value, string key) => value.TryGetProperty(key, out var flag) && flag.ValueKind == JsonValueKind.True;

    private object Credentials() => new { _session!.server, _session.token, _session.user_id, _session.device_id };

    private async Task InitializeAsync()
    {
        if (_session is null || !Current) { _status.Text = "请先登录服务器并进入隐私空间"; return; }
        try {
            var caps = await _core.EmbyExtCapabilities(Credentials(), _lifetime.Token);
            if (!Current) return;
            _enabled = caps.TryGetProperty("liuying", out var feature) && feature.TryGetProperty("enabled", out var enabled) && enabled.ValueKind == JsonValueKind.True;
            if (!_enabled) { _status.Text = "服务器未提供流影能力，请刷新普通首页或检查账号权限"; return; }
            var labels = await _core.EmbyExtLiuYingLabels(Credentials(), _lifetime.Token);
            if (!Current) return;
            _labels = labels.EnumerateArray().ToList();
            DrawTags(); await LoadAsync();
        } catch (OperationCanceledException) { }
        catch (Exception e) { if (Current) _status.Text = e.Message; }
        finally { UpdateControls(); }
    }

    private void UpdateControls()
    {
        _feed.Content = _feedKey == "normal" ? "普通 / 切换18+" : "18+ / 切换普通";
        _tab.IsVisible = _feedKey == "normal";
        _tab.Content = _tabKey == "dance" ? "舞蹈 / 切换直播" : "直播 / 切换舞蹈";
        _favorites.Content = _onlyFavorite ? "只看收藏" : "全部作品";
        _feed.IsEnabled = _tab.IsEnabled = _favorites.IsEnabled = _enabled;
        _next.IsEnabled = _enabled && !_loading && _rows.Count > _index;
        _favorite.IsEnabled = _next.IsEnabled && !_favoriteBusy;
        _favorite.Content = _rows.Count > _index && IsTrue(_rows[_index], "favorite") ? "取消收藏" : "收藏";
    }

    private void DrawTags()
    {
        _tags.Children.Clear();
        if (_feedKey != "normal" || _tabKey != "dance") return;
        var clear = new Button { Content = "全部标签", Classes = { "ghost" } };
        clear.Click += async (_, _) => { _channel = ""; _exposure = ""; await ResetAsync(); };
        _tags.Children.Add(clear);
        foreach (var label in _labels) {
            var name = Mi.Str(label, "name");
            var button = new Button { Content = Mi.Str(label, "title"), Classes = { name == _channel || name == _exposure ? "primary" : "ghost" } };
            button.Click += async (_, _) => {
                if (Mi.Str(label, "kind") == "channel") { _channel = _channel == name ? "" : name; _exposure = ""; }
                else _exposure = _exposure == name ? "" : name;
                await ResetAsync();
            };
            _tags.Children.Add(button);
        }
    }

    private async Task ResetAsync()
    {
        _offset = 0; _seed = Guid.NewGuid().ToString("N"); _rows = []; _index = 0;
        _video.Content = null; DrawTags(); await LoadAsync();
    }

    private async Task LoadAsync()
    {
        if (!_enabled || !Current || _session is not { } s) return;
        _request?.Cancel(); _request?.Dispose();
        var request = _request = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        _loading = true; _retry.IsVisible = false; _status.Text = "加载入库资源…"; UpdateControls();
        try {
            var result = await _core.EmbyExtLiuYingItems(new { s.server, s.token, s.user_id, s.device_id,
                feed = _feedKey, tab = _tabKey, channel = _channel, exposure = _exposure, seed = _seed, offset = _offset, favorite = _onlyFavorite }, request.Token);
            if (!Current || request.IsCancellationRequested || _request != request) return;
            _rows = result.GetProperty("items").EnumerateArray().ToList(); _index = 0;
            _more = result.GetProperty("has_more").GetBoolean();
            ShowCurrent();
        } catch (OperationCanceledException) { }
        catch (Exception e) { if (Current && _request == request) { _status.Text = e.Message; _retry.IsVisible = true; } }
        finally { if (_request == request) { _loading = false; UpdateControls(); } }
    }

    private void ShowCurrent()
    {
        _video.Content = null;
        if (_rows.Count <= _index) { _status.Text = "暂无可播放的入库资源，可以切换来源或筛选；资料中心不直接提供视频"; return; }
        var item = _rows[_index];
        _status.Text = Mi.Str(item, "title") + " · " + Mi.Str(item, "author");
        _video.Content = new PlayerPage(_core, Mi.Str(item, "item_id"), Mi.Str(item, "title"), -1, onCompleted: NextAsync);
        UpdateControls();
    }

    private async Task NextAsync()
    {
        if (_loading || !Current) return;
        if (_index + 1 < _rows.Count) { _index++; ShowCurrent(); return; }
        _offset = _more ? _offset + _rows.Count : 0;
        if (!_more) _seed = Guid.NewGuid().ToString("N");
        _video.Content = null; _rows = []; _index = 0;
        await LoadAsync();
    }

    private async Task FavoriteAsync()
    {
        if (_favoriteBusy || _rows.Count <= _index || _session is not { } s || !Current) return;
        var item = _rows[_index]; var id = Mi.Str(item, "item_id"); var want = !IsTrue(item, "favorite");
        _favoriteBusy = true; UpdateControls();
        try {
            await _core.EmbySetFavorite(new { s.server, s.token, s.user_id, s.device_id, item_id = id, fav = want }, _lifetime.Token);
            if (!Current) return;
            _rows = _rows.Select(row => {
                if (Mi.Str(row, "item_id") != id) return row;
                var fields = JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(row.GetRawText())!;
                fields["favorite"] = JsonSerializer.SerializeToElement(want);
                return JsonSerializer.SerializeToElement(fields);
            }).ToList();
        } catch (OperationCanceledException) { }
        catch (Exception e) { if (Current) _status.Text = e.Message; }
        finally { _favoriteBusy = false; UpdateControls(); }
    }
}
