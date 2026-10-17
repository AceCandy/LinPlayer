using System.Diagnostics;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Threading;
using Avalonia.VisualTree;

namespace LinPlayer.Desktop.Views;

/// <summary>浏览窗口共用一个流光相位；后台、减少动效或播放页面停止推进。</summary>
internal sealed class PrivateNeon : Control
{
    private readonly Window _window;
    private readonly Control _root;
    private readonly Stopwatch _clock = new();
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromMilliseconds(33) };
    private readonly ConicGradientBrush _brush;
    private readonly Pen[] _pens;
    private Control[] _targets = [];
    private bool _enabled, _playing, _attached;

    internal PrivateNeon(Window window, Control root)
    {
        _window = window; _root = root;
        IsHitTestVisible = false; ClipToBounds = true; IsVisible = false;
        var cyan = ((ISolidColorBrush)Tok.Of("PrivateNeonCyan")).Color;
        var red = ((ISolidColorBrush)Tok.Of("PrivateNeonRed")).Color;
        _brush = new ConicGradientBrush { GradientStops = new GradientStops {
            new(cyan, 0), new(cyan, .18), new(red, .45), new(red, .68), new(cyan, 1),
        } };
        _pens = [new(_brush, 12), new(_brush, 8), new(_brush, 4), new(_brush, 1.4)];
        _timer.Tick += (_, _) => { UpdateClock(); InvalidateVisual(); };
        AttachedToVisualTree += (_, _) => {
            _attached = true; _root.LayoutUpdated += LayoutChanged;
            _window.Activated += ActivityChanged; _window.Deactivated += ActivityChanged;
            LayoutChanged(null, EventArgs.Empty); UpdateClock();
        };
        DetachedFromVisualTree += (_, _) => {
            _attached = false; _root.LayoutUpdated -= LayoutChanged;
            _window.Activated -= ActivityChanged; _window.Deactivated -= ActivityChanged;
            _targets = []; UpdateClock();
        };
    }

    internal void SetSpace(bool enabled) { _enabled = enabled; UpdateClock(); InvalidateVisual(); }
    internal void SetPlaying(bool playing) { _playing = playing; UpdateClock(); InvalidateVisual(); }
    private void ActivityChanged(object? sender, EventArgs e) => UpdateClock();
    private void LayoutChanged(object? sender, EventArgs e) => _targets = _root.GetVisualDescendants().OfType<Control>()
        .Where(c => c is Button || c.Name == "Sidebar").ToArray();

    private void UpdateClock()
    {
        IsVisible = _enabled && !_playing;
        if (_attached && IsVisible && _window.IsActive && !Motion.Reduced) { _clock.Start(); _timer.Start(); }
        else { _clock.Stop(); _timer.Stop(); }
    }

    public override void Render(DrawingContext context)
    {
        if (!_enabled || _playing) return;
        _brush.Angle = (_clock.Elapsed.TotalSeconds % 10) * 36;
        Glow(context, new Rect(Bounds.Size).Deflate(3), 10, false);
        foreach (var target in _targets) {
            if (!target.IsEffectivelyVisible || target.Bounds.Width <= 0 || target.Bounds.Height <= 0 ||
                target.TranslatePoint(default, this) is not { } origin) continue;
            var rect = new Rect(origin, target.Bounds.Size);
            var clip = new Rect(Bounds.Size);
            foreach (var ancestor in target.GetVisualAncestors())
                if (ancestor.ClipToBounds && ancestor.TranslatePoint(default, this) is { } at)
                    clip = clip.Intersect(new Rect(at, ancestor.Bounds.Size));
            if (!rect.Intersects(clip)) continue;
            using var clipped = context.PushClip(clip);
            var icon = target is Button b && (b.Classes.Contains("caption") || b.Content is PathIcon);
            Glow(context, rect.Deflate(1), icon ? rect.Height / 2 : 6, icon);
        }
    }

    private void Glow(DrawingContext context, Rect rect, double radius, bool icon)
    {
        if (rect.Width <= 0 || rect.Height <= 0) return;
        double[] opacity = [.06, .12, .28, 1];
        for (var i = 0; i < (icon ? 3 : _pens.Length); i++) {
            using var alpha = context.PushOpacity(opacity[i]);
            context.DrawRectangle(null, _pens[i], rect, radius, radius);
        }
    }
}
