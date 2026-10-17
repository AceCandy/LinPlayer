using System.Reflection;
using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Platform;
using Avalonia.Threading;
using LinPlayer.Desktop.Core;
using Microsoft.Web.WebView2.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>私密发现页的双网页；按需加载，不安装插件脚本桥，离页释放。</summary>
public sealed class LiuyingPage : PageBase
{
    private readonly LiuyingWebHost?[] _views = new LiuyingWebHost?[2];
    private readonly string[] _statuses = ["", ""];
    private readonly string[] _urls;
    private readonly Grid _web = new();
    private readonly TextBlock _status = Dim("");
    private readonly Button _switch = new() { Content = "卍解", Classes = { "ghost" } };
    private Window? _window;
    private int _selected;
    private bool _attached;
    private readonly string _profile = "liuying" + Guid.NewGuid().ToString("N");

    public LiuyingPage() : this([Configured("LiuyingUrl"), Configured("LiuyingAdultUrl")]) { }

    internal LiuyingPage(string[] urls)
    {
        _urls = urls;
        var back = new Button { Content = "← 返回", Classes = { "ghost" } };
        back.Click += (_, _) => Nav.Back();
        _switch.Click += (_, _) => { _selected = 1 - _selected; Select(); };
        var header = new Grid { ColumnDefinitions = new("Auto,*,Auto"), Margin = new Thickness(18, 10) };
        header.Children.Add(back);
        var title = H1("流影"); title.HorizontalAlignment = HorizontalAlignment.Center;
        Grid.SetColumn(title, 1); header.Children.Add(title);
        Grid.SetColumn(_switch, 2); header.Children.Add(_switch);
        var layout = new Grid { RowDefinitions = new("Auto,Auto,*") };
        layout.Children.Add(header);
        _status.Margin = new Thickness(18, 0, 18, 6);
        Grid.SetRow(_status, 1); layout.Children.Add(_status);
        Grid.SetRow(_web, 2); layout.Children.Add(_web);
        Content = layout;
        AttachedToVisualTree += (_, _) => {
            _attached = true;
            _window = TopLevel.GetTopLevel(this) as Window;
            if (_window is not null) {
                _window.Activated += VisibilityChanged; _window.Deactivated += VisibilityChanged;
                _window.PropertyChanged += WindowChanged;
            }
            Select();
        };
        DetachedFromVisualTree += (_, _) => {
            _attached = false;
            if (_window is not null) {
                _window.Activated -= VisibilityChanged; _window.Deactivated -= VisibilityChanged;
                _window.PropertyChanged -= WindowChanged;
            }
            foreach (var view in _views) view?.Close();
            Array.Clear(_views); Array.Fill(_statuses, ""); _web.Children.Clear(); _window = null;
        };
    }

    private static string Configured(string key) => typeof(LiuyingPage).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>()
        .FirstOrDefault(a => a.Key == key)?.Value ?? "";

    internal static bool WebUrl(string url) => Uri.TryCreate(url, UriKind.Absolute, out var u) && u.Scheme is "http" or "https";

    private void VisibilityChanged(object? sender, EventArgs e) => UpdateActive();
    private void WindowChanged(object? sender, AvaloniaPropertyChangedEventArgs e)
    {
        if (e.Property == IsVisibleProperty || e.Property == Window.WindowStateProperty) UpdateActive();
    }

    private void UpdateActive()
    {
        var foreground = _window is { IsVisible: true } window && window.WindowState != WindowState.Minimized &&
            (window.IsActive || _views.Any(v => v?.HasNativeFocus == true));
        for (var i = 0; i < _views.Length; i++)
            _views[i]?.SetActive(_attached && SpaceCurrent && i == _selected && foreground);
    }

    private void Select()
    {
        if (!_attached || !SpaceCurrent || !BrowseSpace.IsPrivate) return;
        _switch.Classes.Set("primary", _selected == 1);
        if (!WebUrl(_urls[_selected])) _status.Text = "当前安装包尚未配置此页面";
        else if (!(OperatingSystem.IsLinux() ? LinuxLiuyingWebHost.Available : WebViewHost.Available))
            _status.Text = OperatingSystem.IsLinux() ? "系统缺少 WebKitGTK 4.1 网页组件（需 2.40 或以上版本）" : "系统没有可用的 WebView2 组件";
        else {
            if (_views[_selected] is null) {
                var index = _selected;
                void Status(string text) {
                    if (!_attached || !SpaceCurrent) return;
                    _statuses[index] = text;
                    if (_selected == index) _status.Text = text;
                }
                LiuyingWebHost view = OperatingSystem.IsLinux()
                    ? new LinuxLiuyingWebHost(_urls[index], Status) : new WebHost(_urls[index], _profile, Status);
                view.NativeFocusChanged = UpdateActive;
                _views[index] = view; _web.Children.Add(view);
            }
            _status.Text = _statuses[_selected];
        }
        for (var i = 0; i < _views.Length; i++) if (_views[i] is { } view) view.IsVisible = i == _selected;
        UpdateActive();
    }

    internal bool BackInPage() => _views[_selected]?.BackInPage() == true;

    private sealed class WebHost(string url, string profile, Action<string> status) : LiuyingWebHost
    {
        private CoreWebView2Controller? _controller;
        private IntPtr _hwnd;
        private int _generation;
        private int _visibilityRevision;
        private bool _active;

        internal override bool BackInPage()
        {
            if (_controller?.CoreWebView2.CanGoBack != true) return false;
            _controller.CoreWebView2.GoBack(); return true;
        }

        protected override IPlatformHandle CreateNativeControlCore(IPlatformHandle parent)
        {
            _hwnd = CreateWindowExW(0, "Static", "", 0x40000000 | 0x10000000 | 0x02000000,
                0, 0, 1, 1, parent.Handle, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
            _ = Start(_hwnd, ++_generation);
            return new PlatformHandle(_hwnd, "HWND");
        }

        private async Task Start(IntPtr hwnd, int generation)
        {
            try {
                var env = await WebViewHost.Env();
                if (generation != _generation || hwnd != _hwnd) return;
                var options = env.CreateCoreWebView2ControllerOptions();
                options.ProfileName = profile; options.IsInPrivateModeEnabled = true;
                var controller = await env.CreateCoreWebView2ControllerAsync(hwnd, options);
                if (generation != _generation || hwnd != _hwnd) { controller.Close(); return; }
                _controller = controller;
                controller.AcceleratorKeyPressed += (_, e) => {
                    if (e.KeyEventKind is not (CoreWebView2KeyEventKind.KeyDown or CoreWebView2KeyEventKind.SystemKeyDown) ||
                        !(e.VirtualKey == 27 || e.VirtualKey == 37 && (e.KeyEventLParam & (1 << 29)) != 0)) return;
                    e.Handled = true;
                    if (e.PhysicalKeyStatus.WasKeyDown == 0) Dispatcher.UIThread.Post(() => {
                        if (generation == _generation && IsEffectivelyVisible) Nav.Back();
                    });
                };
                var web = controller.CoreWebView2;
                web.Settings.AreDevToolsEnabled = false;
                web.Settings.AreHostObjectsAllowed = false;
                web.Settings.IsWebMessageEnabled = false;
                web.Settings.IsPasswordAutosaveEnabled = false;
                web.Settings.IsGeneralAutofillEnabled = false;
                web.NavigationStarting += (_, e) => { if (!WebUrl(e.Uri)) e.Cancel = true; else status("正在加载…"); };
                web.NewWindowRequested += (_, e) => { e.Handled = true; if (WebUrl(e.Uri)) web.Navigate(e.Uri); };
                web.DownloadStarting += (_, e) => e.Cancel = true;
                web.PermissionRequested += (_, e) => e.State = CoreWebView2PermissionState.Deny;
                web.NavigationCompleted += (_, e) => {
                    if (generation != _generation) return;
                    status(e.IsSuccess ? "" : "网页加载失败，请检查网络后重新进入");
                    SetActive(_active);
                };
                Resize(); web.Navigate(url); SetActive(_active);
            } catch {
                // 不展示异常中的网页地址或本地用户数据路径。
                if (generation == _generation) status("网页组件启动失败，请检查系统组件后重新进入");
            }
        }

        internal override async void SetActive(bool active)
        {
            _active = active;
            var revision = ++_visibilityRevision;
            if (_controller is not { } controller) return;
            try {
                var web = controller.CoreWebView2;
                if (active) { web.Resume(); controller.IsVisible = true; }
                await web.ExecuteScriptAsync(MediaScript(active));
                if (revision != _visibilityRevision || _controller != controller || _active) return;
                controller.IsVisible = false;
                await web.TrySuspendAsync();
                // 挂起过程中可能已切回；旧回执不得再暂停当前页。
                if (_controller == controller && _active) web.Resume();
            } catch (COMException) { /* 控制器关闭或导航期间，媒体随页面释放。 */ }
            catch (InvalidOperationException) { /* 控制器已释放，不能再调用脚本。 */ }
        }

        internal override void Close()
        {
            ++_generation; ++_visibilityRevision;
            _controller?.Close(); _controller = null;
        }

        protected override void DestroyNativeControlCore(IPlatformHandle control)
        {
            Close(); _hwnd = IntPtr.Zero; DestroyWindow(control.Handle);
        }

        protected override Size ArrangeOverride(Size size) { var result = base.ArrangeOverride(size); Resize(); return result; }
        private void Resize()
        {
            if (_controller is null) return;
            var scale = VisualRoot?.RenderScaling ?? 1;
            _controller.Bounds = new System.Drawing.Rectangle(0, 0, (int)(Bounds.Width * scale), (int)(Bounds.Height * scale));
        }

        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateWindowExW(int exStyle, string cls, string name, int style,
            int x, int y, int w, int h, IntPtr parent, IntPtr menu, IntPtr inst, IntPtr param);
        [DllImport("user32.dll")] private static extern bool DestroyWindow(IntPtr hwnd);
    }
}
