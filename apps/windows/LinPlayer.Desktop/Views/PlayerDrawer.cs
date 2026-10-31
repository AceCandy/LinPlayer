using System;
using System.Collections.Generic;
using System.Linq;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Input;
using Avalonia.Automation;
using Avalonia.Media;
using Avalonia.Media.Transformation;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>播放页右侧的章节与插件面板。</summary>
internal sealed class PlayerDrawer : Border
{
    public const double DrawerWidth = 420;

    private readonly ContentControl _body = new();
    private readonly List<Button> _tabs = [];
    private Action? _focusContent;

    public PlayerDrawer(CoreClient core, IReadOnlyList<(double At, string Label)> chapters, Action<double> onChapter,
        Action onClose, IReadOnlyList<PlayerSurfaceInfo>? panels = null)
    {
        Width = DrawerWidth;
        HorizontalAlignment = HorizontalAlignment.Right;
        // 叠在画面上的遮罩,不走主题 token:底下永远是片子,换浅色主题它也得是暗的
        Background = new SolidColorBrush(Color.Parse("#f80b0d12"));
        BorderBrush = new SolidColorBrush(Color.Parse("#1fffffff"));
        BorderThickness = new Thickness(1, 0, 0, 0);
        Padding = new Thickness(0, 14, 0, 0);
        KeyboardNavigation.SetTabNavigation(this, KeyboardNavigationMode.Cycle);

        var close = new Button
        {
            Classes = { "osd" }, Content = "\uE711", FontSize = 14, Width = 34, Height = 34,
            Focusable = true, HorizontalAlignment = HorizontalAlignment.Right,
        };
        ToolTip.SetTip(close, "收起(Esc)");
        AutomationProperties.SetName(close, "收起侧栏");
        close.Click += (_, _) => onClose();

        var tabs = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        void Tab(object content, Func<Control> make)
        {
            var b = new Button { Classes = { "chip" }, Content = content, Focusable = true };
            b.Click += (_, _) =>
            {
                foreach (var t in _tabs) t.Classes.Set("on", t == b);
                _focusContent = null;
                _body.Content = make();
            };
            b.KeyDown += (_, e) =>
            {
                if (e.Key == Key.Down) { _focusContent?.Invoke(); e.Handled = true; }
                else if (e.Key is Key.Left or Key.Right)
                {
                    var next = _tabs[Math.Clamp(_tabs.IndexOf(b) + (e.Key == Key.Left ? -1 : 1), 0, _tabs.Count - 1)];
                    next.Focus();
                    next.RaiseEvent(new Avalonia.Interactivity.RoutedEventArgs(Button.ClickEvent));
                    e.Handled = true;
                }
            };
            _tabs.Add(b);
            tabs.Children.Add(b);
        }
        if (chapters.Count > 0)
            Tab($"章节 · {chapters.Count}", () => ChapterList(chapters, onChapter));
        foreach (var p in panels ?? [])
        {
            // 造一次留着:make 每次点标签都调,不存的话来回切两下就是两次挂载 + 两次插件重渲染
            Control? made = null;
            Tab(TabHead(p), () => made ??= new PluginSurface(core, p.Plugin, p.Target, "panel"));
        }

        var head = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto"),
            Margin = new Thickness(18, 0, 10, 10),
            Children = { tabs, close },
        };
        Grid.SetColumn(close, 1);
        DockPanel.SetDock(head, Dock.Top);
        Child = new DockPanel { Children = { head, _body } };

        // 使用标签的同一创建路径，保留插件面板的按需挂载。
        if (_tabs.Count > 0) _tabs[0].RaiseEvent(new Avalonia.Interactivity.RoutedEventArgs(Button.ClickEvent));
    }

    internal void FocusInitial() => _tabs.FirstOrDefault()?.Focus();

    /// <summary>插件侧栏页的标签:manifest 给了 <c>icon</c> 就图标 + 名字(D300)。</summary>
    private static object TabHead(PlayerSurfaceInfo p)
    {
        var title = p.Title.Length > 0 ? p.Title : p.Plugin;
        if (PluginIcon.GlyphOf(p.Icon) is not { } cp) return title;
        return new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 6,
            Children =
            {
                new TextBlock { Text = cp, FontFamily = Glyph.Font, VerticalAlignment = VerticalAlignment.Center },
                new TextBlock { Text = title, VerticalAlignment = VerticalAlignment.Center },
            },
        };
    }

    private Control ChapterList(IReadOnlyList<(double At, string Label)> chapters, Action<double> onChapter)
    {
        var col = new StackPanel { Spacing = 2 };
        foreach (var (at, label) in chapters)
        {
            var b = new Button
            {
                Classes = { "eprow" }, Focusable = true,
                Content = new TextBlock { Text = label, FontSize = 13.5, Foreground = Brushes.White },
            };
            b.Click += (_, _) => onChapter(at);
            b.GotFocus += (_, _) => b.BringIntoView();
            b.KeyDown += (_, e) =>
            {
                if (e.Key is not (Key.Up or Key.Down)) return;
                var index = Math.Clamp(col.Children.IndexOf(b) + (e.Key == Key.Up ? -1 : 1), 0, col.Children.Count - 1);
                col.Children[index].Focus();
                e.Handled = true;
            };
            col.Children.Add(b);
        }
        _focusContent = () => col.Children.FirstOrDefault()?.Focus();
        return new ScrollViewer { Content = col, Padding = new Thickness(10, 0, 10, 14) };
    }

    /// <summary>滑进 / 滑出。位移只在 RenderTransform 上动,不进布局。</summary>
    public static void Slide(Control c, bool open)
    {
        c.RenderTransform = TransformOperations.Parse(open ? "translateX(0px)" : $"translateX({DrawerWidth}px)");
        c.Opacity = open ? 1 : 0;
        c.IsHitTestVisible = open;
        c.IsEnabled = open;
    }
}
