# Linux网页后端核验

- Avalonia固定11.3.11。官方tag的src/Avalonia.X11/X11NativeControlHost.cs第26–57行要求XID；101–118行重挂，163–199行调整物理尺寸。其XEmbed尚有TODO，不依赖GtkPlug/Socket协议。
- 本机Debian12，GTK3 runtime 3.24.38，WebKit未系统安装。Debian签名仓库候选WebKitGTK 2.50.6-1~deb12u2，已用apt下载运行依赖与dev/GIR至忽略的.toolchain/webkitgtk，未改系统。
- 具体API以该版本/usr/include/webkitgtk-4.1/webkit和/usr/share/gir-1.0/WebKit2-4.1.gir为准：new_ephemeral自2.16；evaluate_javascript自2.40；set_is_muted自2.30；load-failed后仍发load-changed FINISHED；decide-policy类型0导航/1新窗口/2响应；C信号末尾包含用户指针。
- 官方reference的new_ephemeral说明所有关联WebView不持久化客户端存储：WebKitGTK reference / ctor.WebContext.new_ephemeral.html（页面标2.42.5，已与本机2.50.6 GIR核对）。
- Gread查询WebKit官方仓库未成功返回；旧Go绑定仅作为定位线索，未以其替代当前头文件。sandbox保持开启；本机bwrap用户命名空间探测通过。

## 实测经验

- NativeLibrary探测WebKit后不能Free：引擎已启动原生线程，卸载会使其访问释放代码，GDB定位到JSC线程；进程期保留句柄后消失。
- GTK网页焦点会让Avalonia Window.IsActive变false，须结合GTK顶层focus-in/out；真实键盘输入与切换窗口的HasNativeFocus断言通过。
- window.open需要处理create信号，只有decide-policy时回归失败；补create后通过。
- 输入工具退出不代表DOM已经收到全部字符，要等待最终值；Window Hide/Show会重复触发Opened，自检启动须防重入。
- HttpListener.Response.Abort不保证浏览器报加载失败，故障夹具改为未监听回环端口。缩窗后的网页宽度可以等于宿主目标宽度，不应使用严格小于断言。
