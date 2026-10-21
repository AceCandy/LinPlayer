using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Layout;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>只播放当前筛选内的可见入库作品；复用原生输出，不保存播放地址或断点。</summary>
public sealed class NativeLiuyingPage : PageBase
{
    private const double SidebarWidth = 210;
    private readonly CoreClient _core;
    private readonly Sess? _session = Nav.Session;
    private readonly CancellationTokenSource _lifetime = new();
    private CancellationTokenSource? _request;
    private readonly Panel _layout = new();
    private readonly Grid _chrome = new() { RowDefinitions = new("Auto,*,Auto"), Margin = new Thickness(18) };
    private readonly ContentControl _video = new();
    private readonly StackPanel _tags = new() { Spacing = 6 };
    private readonly StackPanel _actions = new() { Spacing = 6 };
    private readonly TextBlock _status = Dim("读取服务器能力…");
    private readonly TextBlock _title = new() { TextTrimming = Avalonia.Media.TextTrimming.CharacterEllipsis };
    private readonly Button _feed = new() { Content = "卍解", Classes = { "ghost" } };
    private readonly Button _dance = new() { Content = "舞蹈", Classes = { "primary" } };
    private readonly Button _live = new() { Content = "直播", Classes = { "ghost" } };
    private readonly Button _favorites = new() { Content = "全部作品", Classes = { "ghost" } };
    private readonly Button _favorite = new() { Content = "收藏", Classes = { "ghost" } };
    private readonly Button _previous = new() { Content = "上一条", Classes = { "ghost" } };
    private readonly Button _next = new() { Content = "下一条", Classes = { "primary" } };
    private readonly Button _mode = new() { Content = "自动下一条", Classes = { "ghost" } };
    private readonly Button _fold = new() { Content = "收起", Classes = { "ghost" } };
    private readonly Button _retry = new() { Content = "重试", Classes = { "ghost" }, IsVisible = false };
    private readonly Button _pause = new() { Content = "暂停", Classes = { "ghost" }, IsEnabled = false };
    private readonly Button _mute = new() { Content = "静音", Classes = { "ghost" }, IsEnabled = false };
    private readonly Slider _progress = new() { Minimum = 0, Maximum = 1, IsEnabled = false };
    private readonly TextBlock _time = Dim("");
    private readonly StackPanel _bottom = new() { Spacing = 6, IsVisible = false };
    private List<JsonElement> _rows = [];
    private List<JsonElement> _labels = [];
    private sealed record PageWindow(string Seed, int Offset, List<JsonElement> Rows, bool More);
    private PageWindow? _previousWindow;
    private long _wheelAt = long.MinValue / 2;
    private int _index, _offset, _requestedOffset, _playbackVersion;
    private string _seed = Guid.NewGuid().ToString("N"), _requestedSeed = "", _feedKey = "normal", _tabKey = "dance", _channel = "", _exposure = "";
    private bool _enabled, _loading, _more, _onlyFavorite, _favoriteBusy, _started, _requestLast, _seeking, _autoAdvance = true;
    private bool Current => !_lifetime.IsCancellationRequested && SpaceCurrent && BrowseSpace.IsPrivate && Nav.Session == _session;
    private PlayerPage? Player => _video.Content as PlayerPage;

    public NativeLiuyingPage(CoreClient core)
    {
        _core = core; _requestedSeed = _seed;
        var back = new Button { Content = "返回", Classes = { "ghost" } };
        back.Click += (_, _) => Nav.Back();
        var controls = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6,
            Children = { back, _feed, _dance, _live } };
        _chrome.Children.Add(Plate(new ScrollViewer { Content = controls,
            HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto }));
        _actions.Children.Add(_mode); _actions.Children.Add(_previous); _actions.Children.Add(_next);
        _actions.Children.Add(_favorite); _actions.Children.Add(_favorites); _actions.Children.Add(_tags);
        var sidebar = new Grid { RowDefinitions = new("Auto,*"), Width = SidebarWidth, HorizontalAlignment = HorizontalAlignment.Right };
        sidebar.Children.Add(_fold);
        var scroll = new ScrollViewer { Content = _actions, HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled };
        Grid.SetRow(scroll, 1); sidebar.Children.Add(scroll);
        var middle = new Panel { Margin = new Thickness(0, 10) };
        var status = new StackPanel { Spacing = 10, VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, SidebarWidth + 18, 0), Children = { _status, _retry } };
        _status.TextWrapping = Avalonia.Media.TextWrapping.Wrap;
        var sidePlate = Plate(sidebar); sidePlate.HorizontalAlignment = HorizontalAlignment.Right;
        middle.Children.Add(status); middle.Children.Add(sidePlate);
        Grid.SetRow(middle, 1); _chrome.Children.Add(middle);
        _bottom.Children.Add(_title); _bottom.Children.Add(_progress);
        _bottom.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10,
            Children = { _pause, _mute, _time } });
        var bottom = Plate(_bottom); Grid.SetRow(bottom, 2); _chrome.Children.Add(bottom);
        _layout.Children.Add(_video); _layout.Children.Add(_chrome); Content = _layout;
        _feed.Click += async (_, _) => {
            _feedKey = _feedKey == "normal" ? "adult" : "normal";
            _tabKey = _feedKey == "normal" ? "dance" : ""; _channel = ""; _exposure = "";
            await ResetAsync();
        };
        _dance.Click += async (_, _) => await SetTabAsync("dance");
        _live.Click += async (_, _) => await SetTabAsync("live");
        _favorites.Click += async (_, _) => { _onlyFavorite = !_onlyFavorite; await ResetAsync(); };
        _favorite.Click += async (_, _) => await FavoriteAsync();
        _previous.Click += async (_, _) => await MoveAsync(-1);
        _next.Click += async (_, _) => await MoveAsync(1);
        _mode.Click += (_, _) => { _autoAdvance = !_autoAdvance; UpdateControls(); };
        _fold.Click += (_, _) => { _actions.IsVisible = !_actions.IsVisible; _fold.Content = _actions.IsVisible ? "收起" : "展开"; };
        _retry.Click += async (_, _) => await LoadAsync();
        _pause.Click += async (_, _) => { if (Player is { } player) await player.FeedPause(); };
        _mute.Click += async (_, _) => { if (Player is { } player) await player.FeedMute(); };
        _progress.AddHandler(PointerPressedEvent, (_, _) => _seeking = true, RoutingStrategies.Tunnel);
        _progress.AddHandler(PointerReleasedEvent, async (_, _) => {
            if (_seeking && Player is { } player) await player.FeedSeek(_progress.Value);
            _seeking = false;
        }, RoutingStrategies.Bubble, handledEventsToo: true);
        _progress.KeyUp += async (_, e) => {
            if (e.Key is Key.Left or Key.Right or Key.Home or Key.End && Player is { } player) await player.FeedSeek(_progress.Value);
        };
        AttachedToVisualTree += async (_, _) => { if (!_started) { _started = true; await InitializeAsync(); } };
        DetachedFromVisualTree += (_, _) => { _lifetime.Cancel(); _request?.Cancel(); RemovePlayer(); };
        UpdateControls();
    }

    private static Border Plate(Control content) => new() { Background = Tok.Of("Panel"), Opacity = .94, CornerRadius = new CornerRadius(10), Child = content };
    private static bool IsTrue(JsonElement value, string key) => value.TryGetProperty(key, out var flag) && flag.ValueKind == JsonValueKind.True;
    private object Credentials() => new { _session!.server, _session.token, _session.user_id, _session.device_id };

    private async Task InitializeAsync()
    {
        if (_session is null || !Current) { _status.Text = "请先登录服务器并进入隐私空间"; return; }
        try {
            var caps = await _core.EmbyExtCapabilities(Credentials(), _lifetime.Token);
            if (!Current) return;
            _enabled = caps.TryGetProperty("liuying", out var feature) && IsTrue(feature, "enabled");
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
        _feed.Content = _feedKey == "normal" ? "卍解" : "卍解 · 开";
        _feed.Classes.Set("primary", _feedKey == "adult");
        _dance.IsVisible = _live.IsVisible = _feedKey == "normal";
        _dance.Classes.Set("primary", _tabKey == "dance"); _live.Classes.Set("primary", _tabKey == "live");
        _favorites.Content = _onlyFavorite ? "只看收藏" : "全部作品";
        _mode.Content = _autoAdvance ? "自动下一条" : "循环当前";
        _feed.IsEnabled = _dance.IsEnabled = _live.IsEnabled = _favorites.IsEnabled = _enabled;
        _next.IsEnabled = _enabled && !_loading && !_retry.IsVisible && _rows.Count > _index;
        _previous.IsEnabled = _enabled && !_loading && !_retry.IsVisible &&
            (_previousWindow != null || _rows.Count > _index && (_index > 0 || _offset > 0));
        _favorite.IsEnabled = _next.IsEnabled && !_favoriteBusy;
        _favorite.Content = _rows.Count > _index && IsTrue(_rows[_index], "favorite") ? "取消收藏" : "收藏";
    }

    private async Task SetTabAsync(string tab)
    {
        if (_tabKey == tab) return;
        _tabKey = tab; _channel = ""; _exposure = ""; await ResetAsync();
    }

    private void DrawTags()
    {
        _tags.Children.Clear();
        if (_feedKey != "normal" || _tabKey != "dance") return;
        var clear = new Button { Content = "全部标签", Classes = { "ghost" } };
        clear.Click += async (_, _) => { _channel = ""; _exposure = ""; await ResetAsync(); };
        _tags.Children.Add(clear);
        foreach (var (kind, title) in new[] { ("channel", "频道"), ("exposure", "衣着") }) {
            _tags.Children.Add(Dim(title));
            foreach (var label in _labels.Where(l => Mi.Str(l, "kind") == kind)) {
                var name = Mi.Str(label, "name");
                var count = label.TryGetProperty("count", out var value) && value.TryGetInt32(out var n) ? $" · {n}" : "";
                var button = new Button { Content = Mi.Str(label, "title") + count,
                    HorizontalAlignment = HorizontalAlignment.Stretch, Classes = { name == _channel || name == _exposure ? "primary" : "ghost" } };
                button.Click += async (_, _) => {
                    if (kind == "channel") { _channel = _channel == name ? "" : name; _exposure = ""; }
                    else _exposure = _exposure == name ? "" : name;
                    await ResetAsync();
                };
                _tags.Children.Add(button);
            }
        }
    }

    private void RemovePlayer()
    {
        Player?.ReleaseFeedControls();
        _video.Content = null;
        if (!_lifetime.IsCancellationRequested && !_layout.Children.Contains(_chrome)) _layout.Children.Add(_chrome);
        _bottom.IsVisible = false; _pause.IsEnabled = _mute.IsEnabled = _progress.IsEnabled = false;
        _seeking = false;
    }

    private async Task ResetAsync()
    {
        _playbackVersion++; _previousWindow = null;
        _offset = _requestedOffset = 0; _seed = _requestedSeed = Guid.NewGuid().ToString("N");
        _requestLast = false; _rows = []; _index = 0;
        RemovePlayer(); DrawTags(); await LoadAsync();
    }

    private async Task LoadAsync()
    {
        if (!_enabled || !Current || _session is not { } s) return;
        _request?.Cancel(); _request?.Dispose();
        var request = _request = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        var seed = _requestedSeed; var offset = _requestedOffset; var last = _requestLast;
        _loading = true; _retry.IsVisible = false; RemovePlayer(); _status.Text = "加载入库资源…"; UpdateControls();
        try {
            var result = await _core.EmbyExtLiuYingItems(new { s.server, s.token, s.user_id, s.device_id,
                feed = _feedKey, tab = _tabKey, channel = _channel, exposure = _exposure, seed, offset, favorite = _onlyFavorite }, request.Token);
            if (!Current || request.IsCancellationRequested || _request != request) return;
            _rows = result.GetProperty("items").EnumerateArray().ToList();
            _seed = seed; _offset = offset; _index = last ? Math.Max(0, _rows.Count - 1) : 0;
            _more = result.GetProperty("has_more").GetBoolean();
            ShowCurrent();
        } catch (OperationCanceledException) { }
        catch (Exception e) { if (Current && _request == request) { _status.Text = e.Message; _retry.IsVisible = true; } }
        finally { if (_request == request) { _loading = false; UpdateControls(); } }
    }

    private void ShowCurrent()
    {
        RemovePlayer();
        var version = ++_playbackVersion;
        if (_rows.Count <= _index) { _status.Text = "暂无可播放的入库资源，可以切换来源或筛选；资料中心不直接提供视频"; return; }
        var item = _rows[_index];
        _status.Text = ""; _title.Text = Mi.Str(item, "title") + " · " + Mi.Str(item, "author");
        var player = new PlayerPage(_core, Mi.Str(item, "item_id"), Mi.Str(item, "title"), -1, onCompleted: async () => {
            if (!Current || version != _playbackVersion || _loading) return;
            if (_autoAdvance) await MoveAsync(1); else ShowCurrent();
        });
        _layout.Children.Remove(_chrome);
        player.UseFeedControls(_chrome, (direction, wheel) => {
            if (!Current || version != _playbackVersion) return;
            if (wheel) {
                var now = Environment.TickCount64;
                if (now - _wheelAt < 330) return;
                _wheelAt = now;
            }
            _ = MoveAsync(direction);
        },
            (position, duration, paused, muted) => {
                if (!Current || version != _playbackVersion) return;
                _pause.IsEnabled = _mute.IsEnabled = true; _pause.Content = paused ? "播放" : "暂停"; _mute.Content = muted ? "开声音" : "静音";
                _progress.IsEnabled = duration > 0; _progress.Maximum = Math.Max(1, duration);
                if (!_seeking) _progress.Value = Math.Clamp(position, 0, _progress.Maximum);
                _time.Text = $"{TimeSpan.FromSeconds(position):mm\\:ss} / {TimeSpan.FromSeconds(Math.Max(0, duration)):mm\\:ss}";
            });
        _bottom.IsVisible = true; _video.Content = player;
        UpdateControls();
    }

    private async Task MoveAsync(int delta)
    {
        if (_loading || _retry.IsVisible || !Current) return;
        if (_rows.Count == 0 && !(delta < 0 && _previousWindow != null)) return;
        if (delta < 0 && _index == 0 && _offset == 0 && _previousWindow == null) return;
        _playbackVersion++;
        if (_index + delta >= 0 && _index + delta < _rows.Count) { _index += delta; ShowCurrent(); return; }
        var old = new PageWindow(_seed, _offset, _rows, _more);
        if (delta < 0 && _previousWindow is { } previous) {
            _seed = previous.Seed; _offset = previous.Offset; _rows = previous.Rows; _more = previous.More;
            _index = _rows.Count - 1; _previousWindow = null; ShowCurrent(); return;
        }
        _requestedSeed = delta > 0 && !_more ? Guid.NewGuid().ToString("N") : _seed;
        _requestedOffset = delta < 0 ? Math.Max(0, _offset - 12) : _more ? _offset + _rows.Count : 0;
        _requestLast = delta < 0; _previousWindow = delta > 0 ? old : null;
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
            JsonElement Update(JsonElement row) {
                if (Mi.Str(row, "item_id") != id) return row;
                var fields = JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(row.GetRawText())!;
                fields["favorite"] = JsonSerializer.SerializeToElement(want);
                return JsonSerializer.SerializeToElement(fields);
            }
            _rows = _rows.Select(Update).ToList();
            if (_previousWindow is { } previous) _previousWindow = previous with { Rows = previous.Rows.Select(Update).ToList() };
        } catch (OperationCanceledException) { }
        catch (Exception e) { if (Current) _status.Text = e.Message; }
        finally { _favoriteBusy = false; UpdateControls(); }
    }
}
