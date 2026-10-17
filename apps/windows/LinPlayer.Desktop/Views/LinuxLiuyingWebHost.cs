using System.Runtime.InteropServices;
using Avalonia.Platform;
using Avalonia.Threading;

namespace LinPlayer.Desktop.Views;

/// <summary>系统 WebKitGTK 4.1 的私密网页，通过 X11 子窗口嵌入浏览页。</summary>
internal sealed class LinuxLiuyingWebHost(string url, Action<string> status) : LiuyingWebHost
{
    private nint _window, _web, _context;
    private bool _active, _failed, _focused;
    internal override bool HasNativeFocus => _focused && _web != 0;
    private readonly List<(nint Object, ulong Id, Delegate Callback)> _signals = [];
    private static bool _initialized;
    private static int _hosts;
    private static nint _library;
    // 加载引擎会启动原生线程；探测后不能卸载，否则线程可能执行已释放的代码。
    private static readonly Lazy<bool> RuntimeAvailable = new(() => OperatingSystem.IsLinux() &&
        NativeLibrary.TryLoad(Native.WebKit, out _library) &&
        NativeLibrary.TryGetExport(_library, "webkit_web_view_evaluate_javascript", out _));
    private static readonly DispatcherTimer Pump = new() { Interval = TimeSpan.FromMilliseconds(10) };

    static LinuxLiuyingWebHost()
    {
        Pump.Tick += (_, _) => {
            // 限制每帧工作量，不让网页的事件队列阻塞外壳输入。
            for (var i = 0; i < 32 && Native.g_main_context_pending(0) != 0; i++) Native.g_main_context_iteration(0, 0);
        };
    }

    internal static bool Available => RuntimeAvailable.Value;

    protected override IPlatformHandle CreateNativeControlCore(IPlatformHandle parent)
    {
        if (parent.HandleDescriptor != "XID") throw new NotSupportedException("网页组件需要 X11 或 XWayland");
        if (!_initialized) {
            Native.gdk_set_allowed_backends("x11");
            if (Native.gtk_init_check(0, 0) == 0) throw new InvalidOperationException("网页组件无法连接显示服务");
            _initialized = true;
        }
        // popup 不交给窗口管理器；Avalonia 负责重挂、定位和原生窗口可见性。
        _window = Native.gtk_window_new(1);
        Connect(_window, "focus-in-event", new RequestSignal((_, _, _) => { _focused = true; NativeFocusChanged?.Invoke(); return 0; }));
        Connect(_window, "focus-out-event", new RequestSignal((_, _, _) => { _focused = false; NativeFocusChanged?.Invoke(); return 0; }));
        _context = Native.webkit_web_context_new_ephemeral();
        Native.webkit_web_context_set_sandbox_enabled(_context, 1);
        _web = Native.webkit_web_view_new_with_context(_context);

        var settings = Native.webkit_web_view_get_settings(_web);
        Native.webkit_settings_set_enable_developer_extras(settings, 0);
        Native.webkit_settings_set_enable_write_console_messages_to_stdout(settings, 0);
        Connect(_context, "download-started", new ObjectSignal((_, download, _) => Native.webkit_download_cancel(download)));
        Connect(_web, "permission-request", new RequestSignal((_, request, _) => { Native.webkit_permission_request_deny(request); return 1; }));
        Connect(_web, "run-file-chooser", new RequestSignal((_, request, _) => { Native.webkit_file_chooser_request_cancel(request); return 1; }));
        Connect(_web, "decide-policy", new PolicySignal(DecidePolicy));
        Connect(_web, "create", new CreateSignal((view, action, _) => {
            var uri = ActionUrl(action);
            if (LiuyingPage.WebUrl(uri)) Native.webkit_web_view_load_uri(view, uri);
            return 0;
        }));
        Connect(_web, "key-press-event", new RequestSignal((_, ev, _) => {
            Native.gdk_event_get_keyval(ev, out var key);
            Native.gdk_event_get_state(ev, out var modifiers);
            if (key != 0xff1b && !(key == 0xff51 && (modifiers & 8) != 0)) return 0;
            Dispatcher.UIThread.Post(() => { if (_web != 0 && IsEffectivelyVisible) Nav.Back(); });
            return 1;
        }));

        Connect(_web, "load-changed", new StateSignal((_, state, _) => {
            if (state == 0) { _failed = false; status("正在加载…"); }
            if (state == 3) { if (!_failed) status(""); SetActive(_active); }
        }));
        Connect(_web, "load-failed", new FailedSignal((_, _, _, _, _) => {
            _failed = true; status("网页加载失败，请检查网络后重新进入"); return 1;
        }));
        Connect(_web, "web-process-terminated", new StateSignal((_, _, _) => {
            _failed = true; status("网页组件已停止，请重新进入");
        }));
        Native.gtk_container_add(_window, _web);
        Native.gtk_widget_show_all(_window);
        _hosts++; Pump.Start();
        SetActive(_active);
        Native.webkit_web_view_load_uri(_web, url);
        return new PlatformHandle((nint)Native.gdk_x11_window_get_xid(Native.gtk_widget_get_window(_window)), "XID");
    }

    private int DecidePolicy(nint view, nint decision, int kind, nint data)
    {
        if (kind == 2) return 0;
        var action = Native.webkit_navigation_policy_decision_get_navigation_action(decision);
        var uri = ActionUrl(action);
        if (!LiuyingPage.WebUrl(uri) || kind == 1) {
            Native.webkit_policy_decision_ignore(decision);
            if (kind == 1 && LiuyingPage.WebUrl(uri)) Native.webkit_web_view_load_uri(view, uri);
            return 1;
        }
        return 0;
    }

    private static string ActionUrl(nint action) => Marshal.PtrToStringUTF8(
        Native.webkit_uri_request_get_uri(Native.webkit_navigation_action_get_request(action))) ?? "";

    internal override bool BackInPage()
    {
        if (_web == 0 || Native.webkit_web_view_can_go_back(_web) == 0) return false;
        Native.webkit_web_view_go_back(_web); return true;
    }


    internal override void SetActive(bool active)
    {
        _active = active;
        if (_web == 0) return;
        Native.webkit_web_view_set_is_muted(_web, active ? 0 : 1);
        Native.webkit_web_view_evaluate_javascript(_web, MediaScript(active), -1, 0, 0, 0, 0, 0);
    }

    internal override void Close()
    {
        if (_web == 0) return;
        Native.webkit_web_view_set_is_muted(_web, 1);
        Native.webkit_web_view_stop_loading(_web);
        foreach (var signal in _signals) Native.g_signal_handler_disconnect(signal.Object, signal.Id);
        Native.gtk_widget_destroy(_web); _web = 0;
        Native.g_object_unref(_context); _context = 0;
        _signals.Clear();
    }

    protected override void DestroyNativeControlCore(IPlatformHandle control)
    {
        Close();
        Native.gtk_widget_destroy(_window); _window = 0;
        if (--_hosts == 0) Pump.Stop();
    }

    private void Connect(nint instance, string name, Delegate callback) =>
        _signals.Add((instance, Native.g_signal_connect_data(instance, name, Marshal.GetFunctionPointerForDelegate(callback), 0, 0, 0), callback));

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate void ObjectSignal(nint instance, nint value, nint data);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate int RequestSignal(nint instance, nint value, nint data);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate nint CreateSignal(nint instance, nint action, nint data);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate int PolicySignal(nint instance, nint decision, int kind, nint data);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate void StateSignal(nint instance, int state, nint data);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate int FailedSignal(nint instance, int state, nint uri, nint error, nint data);

    private static class Native
    {
        internal const string WebKit = "libwebkit2gtk-4.1.so.0";
        private const string Gtk = "libgtk-3.so.0", Gdk = "libgdk-3.so.0", GLib = "libglib-2.0.so.0", GObject = "libgobject-2.0.so.0";
        [DllImport(Gtk)] internal static extern int gtk_init_check(nint argc, nint argv);
        [DllImport(Gdk)] internal static extern void gdk_set_allowed_backends([MarshalAs(UnmanagedType.LPUTF8Str)] string backends);
        [DllImport(Gtk)] internal static extern nint gtk_window_new(int type);
        [DllImport(Gtk)] internal static extern void gtk_container_add(nint container, nint widget);
        [DllImport(Gtk)] internal static extern void gtk_widget_show_all(nint widget);
        [DllImport(Gtk)] internal static extern void gtk_widget_destroy(nint widget);
        [DllImport(Gtk)] internal static extern nint gtk_widget_get_window(nint widget);
        [DllImport(Gdk)] internal static extern nuint gdk_x11_window_get_xid(nint window);
        [DllImport(Gdk)] internal static extern int gdk_event_get_keyval(nint ev, out uint key);
        [DllImport(Gdk)] internal static extern int gdk_event_get_state(nint ev, out uint modifiers);
        [DllImport(GLib)] internal static extern int g_main_context_pending(nint context);
        [DllImport(GLib)] internal static extern int g_main_context_iteration(nint context, int block);
        [DllImport(GObject)] internal static extern ulong g_signal_connect_data(nint instance, [MarshalAs(UnmanagedType.LPUTF8Str)] string signal, nint callback, nint data, nint destroy, int flags);
        [DllImport(GObject)] internal static extern void g_signal_handler_disconnect(nint instance, ulong id);
        [DllImport(GObject)] internal static extern void g_object_unref(nint instance);
        [DllImport(WebKit)] internal static extern nint webkit_web_context_new_ephemeral();
        [DllImport(WebKit)] internal static extern void webkit_web_context_set_sandbox_enabled(nint context, int enabled);
        [DllImport(WebKit)] internal static extern nint webkit_web_view_new_with_context(nint context);
        [DllImport(WebKit)] internal static extern nint webkit_web_view_get_settings(nint view);
        [DllImport(WebKit)] internal static extern void webkit_settings_set_enable_developer_extras(nint settings, int enabled);
        [DllImport(WebKit)] internal static extern void webkit_settings_set_enable_write_console_messages_to_stdout(nint settings, int enabled);
        [DllImport(WebKit)] internal static extern void webkit_web_view_load_uri(nint view, [MarshalAs(UnmanagedType.LPUTF8Str)] string uri);
        [DllImport(WebKit)] internal static extern void webkit_web_view_stop_loading(nint view);
        [DllImport(WebKit)] internal static extern int webkit_web_view_can_go_back(nint view);
        [DllImport(WebKit)] internal static extern void webkit_web_view_go_back(nint view);
        [DllImport(WebKit)] internal static extern void webkit_web_view_set_is_muted(nint view, int muted);
        [DllImport(WebKit)] internal static extern void webkit_web_view_evaluate_javascript(nint view, [MarshalAs(UnmanagedType.LPUTF8Str)] string script, nint length, nint world, nint source, nint cancel, nint callback, nint data);
        [DllImport(WebKit)] internal static extern nint webkit_navigation_policy_decision_get_navigation_action(nint decision);
        [DllImport(WebKit)] internal static extern nint webkit_navigation_action_get_request(nint action);
        [DllImport(WebKit)] internal static extern nint webkit_uri_request_get_uri(nint request);
        [DllImport(WebKit)] internal static extern void webkit_policy_decision_ignore(nint decision);
        [DllImport(WebKit)] internal static extern void webkit_permission_request_deny(nint request);
        [DllImport(WebKit)] internal static extern void webkit_file_chooser_request_cancel(nint request);
        [DllImport(WebKit)] internal static extern void webkit_download_cancel(nint download);
    }
}
