using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Platform.Storage;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using Avalonia.VisualTree;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>页面的公共底子:统一的水槽与封顶宽(UI_PC §3.1)。</summary>
public abstract class PageBase : UserControl
{
    private readonly BrowseSpace.Scope _space = BrowseSpace.Current;
    protected bool SpaceCurrent => _space.IsCurrent;
    internal bool WatchesUserState { get; private set; }

    /// <summary>当前页收到写入时重取；离页期间的变更在重新挂载时补验。</summary>
    protected void WatchUserState(CoreClient core, Action refresh)
    {
        WatchesUserState = true;
        var session = Nav.Session;
        long Revision() => session is null ? 0 : core.UserStateRevision(session.server, session.user_id, session.token);
        var seen = Revision();
        void Check()
        {
            if (!SpaceCurrent || session != Nav.Session || Revision() == seen) return;
            seen = Revision();
            refresh();
        }
        AttachedToVisualTree += (_, _) => {
            core.UserStateChanged += Check;
            Dispatcher.UIThread.Post(() => { if (this.GetVisualRoot() is not null) Check(); });
        };
        DetachedFromVisualTree += (_, _) => core.UserStateChanged -= Check;
    }

    /// <summary>
    /// 页面正文的滚动容器。
    ///
    /// <para>平滑滚动<b>不在这里装</b> —— 装在 <see cref="Smooth.Install"/>(类级处理器,
    /// 全应用一次)。装在这儿的话,自己 new ScrollViewer 的那 6 处页面就漏了。</para>
    /// </summary>
    protected static ScrollViewer Scrolled(Control content)
    {
        var box = new Border
        {
            /* <b>不封顶</b>(2026-09-02 用户点名:「媒体库页面右边有留白,
               媒体库详情页也有…不需要这个留白」)。
               原来是 MaxWidth=1560 + Stretch,也就是「撑满、超过 1560 就居中」——
               在宽窗口上那就是左右各一条空边,而用户要的是内容铺满。
               留下的只有 18 的水槽:贴着边画,卡片会被窗口边缘切齐,看着像裁掉了。 */
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Padding = new Thickness(18, 18, 18, 26),
            Child = content,
        };
        var sv = new ScrollViewer
        {
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Content = box,
        };
        /* 窄窗口把水槽从 18 收到 10(用户 2026-09-11:「窗口做到支持任意大小」)。
           水槽是按 1280 宽定的比例;窗口收到 500 时左右各 18 已经吃掉 7% 的可用宽,
           卡片跟着少一列。挂在这一处 = 全站页面一起变,各页自己写就会漏。 */
        sv.SizeChanged += (_, e) =>
        {
            var g = e.NewSize.Width < 640 ? 10d : 18d;
            if (Math.Abs(box.Padding.Left - g) > 0.5) box.Padding = new Thickness(g, g, g, g + 8);
        };
        return sv;
    }

    protected static TextBlock H1(string t) => new() { Text = t, Classes = { "h1" } };
    protected static TextBlock H2(string t) => new() { Text = t, Classes = { "h2" } };
    protected static Control SectionTitle(string text) => new StackPanel {
        Orientation = Orientation.Horizontal, Spacing = 10,
        Children = {
            new Border { Width = 3, Height = 18, Background = Tok.Of("Accent"), CornerRadius = new CornerRadius(6) },
            H2(text),
        },
    };
    protected static TextBlock Dim(string t) => new() { Text = t, Classes = { "dim" }, TextWrapping = TextWrapping.Wrap };
}

/// <summary>核心层没起来时的页面。**不能白屏** —— 要如实说出原因。</summary>
public sealed class FatalPage : PageBase
{
    public FatalPage(string reason)
    {
        Content = new Border
        {
            Padding = new Thickness(42),
            Child = new StackPanel
            {
                Spacing = 10,
                VerticalAlignment = VerticalAlignment.Center,
                HorizontalAlignment = HorizontalAlignment.Center,
                Children =
                {
                    H1("核心层没能启动"),
                    Dim(reason),
                    Dim("常见原因:lpcore.dll 或 libmpv-2.dll 不在 exe 同级目录。"),
                },
            },
        };
    }
}

/// <summary>
/// 当前账号不是 Emby 时的落地页。
///
/// <para>这不是「懒得做」的占位页。这些页面全都直接用 <c>Nav.Session</c> 取会话,
/// 账号是网盘 / 局域网源时它是 null,解引用就是空引用异常。实测(注入摘掉守卫):
/// 页面自己的 catch 会接住,但显示的是「加载失败:Object reference not set…」——
/// 一句用户看不懂、也不知道该干什么的英文。这条路真实存在:从旧版升上来的用户,
/// 活跃账号本来就可能不是 Emby。</para>
/// </summary>
public sealed class NoSessionPage : PageBase
{
    public NoSessionPage(string what)
    {
        Content = Scrolled(new StackPanel
        {
            Spacing = 10,
            Children =
            {
                H1(what),
                Dim("当前账号不是 Emby 服务器,这一页还没有对应的实现。"),
                Dim("点左上角的服务器信息可以切换到 Emby 账号。"),
            },
        });
    }
}

public sealed class PlaceholderPage : PageBase
{
    public PlaceholderPage(string name)
    {
        Content = Scrolled(new StackPanel
        {
            Spacing = 10,
            Children = { H1(name), Dim("这一页还没做。") },
        });
    }
}

/// <summary>
/// 首登闸口 / 添加服务器(UI_PC §7.6)。
///
/// <para>它和「添加服务器」是**同一个页面的两种版式**,不是两套代码 ——
/// 两套的话新增一种源类型就要改两处,而漏掉的那处就是「某个入口加不了这种源」。</para>
/// </summary>
public sealed class AddServerPage : PageBase
{
    public AddServerPage(CoreClient core, Action onDone)
    {
        var server = new TextBox { Classes = { "field" }, Watermark = "https://你的服务器地址" };
        var user = new TextBox { Classes = { "field" }, Watermark = "用户名" };
        var pass = new TextBox { Classes = { "field" }, Watermark = "密码", PasswordChar = '●' };
        var hint = new TextBlock { Classes = { "dim" }, TextWrapping = TextWrapping.Wrap };
        var test = new Button { Classes = { "ghost" }, Content = "测试连接" };
        var login = new Button { Classes = { "primary" }, Content = "登录" };

        void Busy(bool on, string? msg = null)
        {
            test.IsEnabled = login.IsEnabled = !on;
            if (msg is not null) hint.Text = msg;
        }

        Task Tell(string title, string detail) => Dialogs.Tell(this, title, detail);

        /* 「测试连接」= **真登一次**(用户 2026-09-11)。
           只探 /System/Info/Public 的话,地址对而密码错的服务器照样报「连上了」,
           用户点「登录」才发现不行 —— 两句话自相矛盾。填了账号就连账号一起验。
           ★ 结果走**弹窗**不走那行小字:小字在按钮下面,填完密码一弹键盘就看不见了,
             而用户看到的是「点了没反应」。弹窗要点一下才消失,跑不掉。 */
        test.Click += async (_, _) =>
        {
            Busy(true, "正在连接…");
            try
            {
                var info = await core.AccountTestConnection(new
                {
                    server = WithScheme(server.Text ?? ""),
                    username = user.Text ?? "",
                    password = pass.Text ?? "",
                });
                var who = Get(info, "user_name");
                var head = $"{Get(info, "name")} · 版本 {Get(info, "version")}";
                var tail = who.Length > 0
                    ? $"已用「{who}」登录成功。"
                    : "(没填账号,只验了地址通不通)";
                hint.Text = "连上了:" + head + " " + tail;
                await Tell("连接成功", head + "\n" + tail);
            }
            catch (CoreException e) { hint.Text = e.Advice; await Tell("连不上", e.Advice); }
            catch (Exception e) { hint.Text = e.Message; await Tell("连不上", e.Message); }
            finally { Busy(false); }
        };

        var form = new StackPanel
        {
            Spacing = 10,
            Children =
            {
                H1("连接到你的媒体服务器"),
                Dim("填服务器地址和账号即可。先点「测试连接」可以确认地址对不对。"),
                Label("服务器地址"), server, Label("用户名"), user, Label("密码"), pass,
                new StackPanel
                {
                    Orientation = Orientation.Horizontal, Spacing = 10,
                    Margin = new Thickness(0, 6, 0, 0),
                    Children = { login, test },
                },
                hint,
            },
        };
        login.Click += async (_, _) =>
        {
            Busy(true, "正在登录…");
            try
            {
                await core.EmbyLogin(new
                {
                    server = WithScheme(server.Text ?? ""),
                    username = user.Text ?? "",
                    password = pass.Text ?? "",
                    // 设备 id 必须**持久**:每次换一个会把服务器的设备列表刷满,
                    // 续播会话也对不上。核心层的 config 里有一个,这里先用机器名兜底。
                    device_id = DeviceId(),
                });
                onDone();
            }
            catch (CoreException e) { hint.Text = e.Advice; Busy(false); }
            catch (Exception e) { hint.Text = e.Message; Busy(false); }
        };


        // 真机自检:LP_SELFCHECK_PAGE=login:<地址>|<用户名>|<密码> 直接填好并点登录。
        // 不能靠 SendKeys —— 焦点落在哪儿不确定,实测一个字符都没进去。
        if (Environment.GetEnvironmentVariable("LP_SELFCHECK_PAGE") is { } sc && sc.StartsWith("login:"))
        {
            var parts = sc["login:".Length..].Split('|');
            server.Text = parts.ElementAtOrDefault(0) ?? "";
            user.Text = parts.ElementAtOrDefault(1) ?? "";
            pass.Text = parts.ElementAtOrDefault(2) ?? "";
            AttachedToVisualTree += (_, _) => Dispatcher.UIThread.Post(() =>
                login.RaiseEvent(new Avalonia.Interactivity.RoutedEventArgs(Button.ClickEvent)));
        }

        /* 打开就把光标放进第一个要填的框。首登闸口只有一件事可做,
           还要用户先点一下输入框,那一下点击是白让人做的(和搜索页同一条规矩)。
           必须等挂上可视树 —— 构造函数里 Focus() 是对着还没上屏的控件调,静默无效。 */
        AttachedToVisualTree += (_, _) => Dispatcher.UIThread.Post(() =>
        {
            server.Focus();
        });

        Content = new Border
        {
            Padding = new Thickness(26),
            Child = new Border
            {
                Classes = { "card" },
                Width = 520,
                Padding = new Thickness(26),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Child = form,
            },
        };
    }

    /// <summary>
    /// 地址补全:用户没写 <c>http://</c> 时补一个。
    ///
    /// <para>不补的表现是 Go 的 URL 解析报「first path segment in URL cannot contain
    /// colon」—— 一句纯英文技术话,而且它以前还被盖成「网络不通」。
    /// 补在 UI 侧,不动核心层的 NormServer:那是从黄金实现逐字移植的,动了会破坏
    /// 差分对账基准。默认补 http 不是 https —— 补错协议只会连不上,而补 https 到
    /// 一台只有 http 的服务器上,报的是看不懂的 TLS 错。</para>
    /// </summary>
    internal static string WithScheme(string raw)
    {
        var t = (raw ?? "").Trim();
        if (t.Length == 0) return t;
        if (t.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
            t.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) return t;
        return "http://" + t;
    }

    private static TextBlock Label(string t) => new()
    {
        Text = t, FontSize = 12.5, Foreground = Brushes.Gray,
    };

    private static string DeviceId() =>
        "linplayer-" + Environment.MachineName.GetHashCode().ToString("x8");

    private static string Get(JsonElement e, string k) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) ? v.GetString() ?? "" : "";
}
