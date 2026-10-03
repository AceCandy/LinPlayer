using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Threading;
using Avalonia.Themes.Fluent;
using Avalonia.VisualTree;
using LinPlayer.Desktop.Core;
using LinPlayer.Desktop.Views;

// 真核心层 + Avalonia 渲染:验证分页接线、TCP 请求取消及导航返回恢复。
AppBuilder.Configure<CheckApp>().UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false }).UseSkia().SetupWithoutStarting();
var root = Path.Combine(Path.GetTempPath(), "linplayer-product-" + Guid.NewGuid());
Directory.CreateDirectory(root);
using var upstream = new Upstream();
try {
    using var core = new CoreClient(Path.GetFullPath(args[0]), root, "test");
    Nav.Session = new Sess(upstream.BaseUrl, "test", "user", "device");
    var window = new Window { Width = 900, Height = 700 };
    try {
        var favorites = new FavoritesPage(core);
        window.Content = favorites; window.Show();
        Wait(() => Count(favorites) == 1, "收藏第一页未显示");
        More(favorites);
        Wait(() => Count(favorites) == 2, "收藏追加失败");
        window.Content = null; Jobs();
        window.Content = favorites; Jobs();
        More(favorites);
        Wait(() => Count(favorites) == 3, "返回收藏页后不能继续分页");
        if (!upstream.Offsets.SequenceEqual(new[] { 0, 1, 2 })) throw new Exception("收藏游标未按服务端原始页推进");
        Console.WriteLine("收藏:首屏单页、追加、短页游标、返回后继续加载通过");

        var home = new HomePage(core);
        window.Content = home; Jobs();
        Wait(() => upstream.Held >= 3, "首页未发出加载请求");
        var before = upstream.Held;
        window.Content = null; Jobs();
        Wait(() => upstream.Cancelled >= before, "离开首页后 TCP 请求仍未取消");
        window.Content = home; Jobs();
        Wait(() => upstream.Held > before, "返回首页没有恢复已取消的加载");
        var restored = upstream.Held;
        window.Content = null; Jobs();
        Wait(() => upstream.Cancelled >= restored, "返回后再次离页不能取消");
        Console.WriteLine("首页:离页取消真实 TCP 请求、复用页面返回恢复、再次取消通过");

        using var limited = new Upstream(true);
        Nav.Session = new Sess(limited.BaseUrl, "test", "user", "device");
        var library = new LibraryGridPage(core, limited.BaseUrl, "library", "测试媒体库");
        window.Content = library; Jobs();
        Wait(() => library.GetVisualDescendants().OfType<ComboBox>().Count(b => !b.IsEnabled) == 2,
            "不支持的类型/年份筛选仍能操作");
        if (library.GetVisualDescendants().OfType<ComboBox>().Count(b => b.IsEnabled) != 1)
            throw new Exception("能力降级禁用了可用排序");
        Wait(() => library.GetVisualDescendants().OfType<TextBlock>().Any(t => t.Text == "服务端不支持条件筛选,可使用排序和分页。"),
            "筛选降级没有显示原因");
        if (limited.Invalid != 0) throw new Exception("降级仍请求了无效分面");
        window.Content = null; Jobs();
        Console.WriteLine("兼容能力:禁用条件筛选、保留排序、显示原因且不请求无效分面通过");
    } finally { window.Close(); Jobs(); }
} finally {
    Nav.Session = null;
    Directory.Delete(root, true);
}

static int Count(Control page) => page.GetVisualDescendants().OfType<MediaGrid>().Sum(g => g.Count);
static void More(Control page) {
    var button = page.GetVisualDescendants().OfType<Button>().Single(b => b.Content as string == "加载更多");
    button.RaiseEvent(new Avalonia.Interactivity.RoutedEventArgs(Button.ClickEvent));
}
static void Jobs() => Dispatcher.UIThread.RunJobs();
static void Wait(Func<bool> done, string error) {
    var deadline = DateTime.UtcNow.AddSeconds(5);
    while (!done() && DateTime.UtcNow < deadline) { Jobs(); Thread.Sleep(10); }
    Jobs();
    if (!done()) throw new Exception(error);
}

sealed class CheckApp : Application {
    public override void Initialize() => Styles.Add(new FluentTheme());
}

sealed class Upstream : IDisposable {
    private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
    private readonly CancellationTokenSource _stop = new();
    private readonly ConcurrentBag<Task> _connections = [];
    private readonly Task _accept;
    private int _held, _cancelled;
    private readonly bool _restricted;
    private int _invalid;
    public int Invalid => Volatile.Read(ref _invalid);
    public int Held => Volatile.Read(ref _held);
    public int Cancelled => Volatile.Read(ref _cancelled);
    public ConcurrentQueue<int> Offsets { get; } = new();
    public string BaseUrl { get; }
    public Upstream(bool restricted = false) {
        _restricted = restricted;
        _listener.Start();
        BaseUrl = "http://" + _listener.LocalEndpoint;
        _accept = Accept();
    }
    private async Task Accept() {
        try {
            while (!_stop.IsCancellationRequested) {
                var client = await _listener.AcceptTcpClientAsync(_stop.Token).ConfigureAwait(false);
                _connections.Add(Serve(client));
            }
        } catch (OperationCanceledException) { }
    }
    private async Task Serve(TcpClient client) {
        using (client) {
            try {
                var stream = client.GetStream();
                using var reader = new StreamReader(stream, Encoding.UTF8, leaveOpen: true);
                var request = await reader.ReadLineAsync(_stop.Token);
                if (request is null) return;
                while (await reader.ReadLineAsync(_stop.Token) is { Length: > 0 }) { }
                var uri = new Uri(BaseUrl + request.Split(' ')[1]);
                var query = uri.Query.TrimStart('?').Split('&').Select(v => v.Split('=', 2))
                    .ToDictionary(v => Uri.UnescapeDataString(v[0]), v => v.Length > 1 ? Uri.UnescapeDataString(v[1]) : "");
                if (_restricted) {
                    object data;
                    if (uri.AbsolutePath == "/System/Info/Public") data = new { Id = "mediastation-go-001" };
                    else if (uri.AbsolutePath == "/Users/user/Items") data = new { Items = Array.Empty<object>(), TotalRecordCount = 0 };
                    else { Interlocked.Increment(ref _invalid); data = new { Items = Array.Empty<object>() }; }
                    var body = JsonSerializer.SerializeToUtf8Bytes(data);
                    var header = Encoding.ASCII.GetBytes($"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {body.Length}\r\nConnection: close\r\n\r\n");
                    await stream.WriteAsync(header, _stop.Token);
                    await stream.WriteAsync(body, _stop.Token);
                    return;
                }
                if (query.GetValueOrDefault("Filters") == "IsFavorite") {
                    var offset = int.Parse(query["StartIndex"]);
                    Offsets.Enqueue(offset);
                    var body = JsonSerializer.SerializeToUtf8Bytes(new { Items = new[] { new { Id = "item" + offset, Name = "收藏" + offset, Type = "Movie" } }, TotalRecordCount = 3 });
                    var header = Encoding.ASCII.GetBytes($"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {body.Length}\r\nConnection: close\r\n\r\n");
                    await stream.WriteAsync(header, _stop.Token);
                    await stream.WriteAsync(body, _stop.Token);
                } else {
                    Interlocked.Increment(ref _held);
                    // GET 没有后续请求体;客户端取消会关闭连接并返回 EOF。
                    if (await reader.ReadAsync(new char[1], _stop.Token) == 0) Interlocked.Increment(ref _cancelled);
                }
            } catch (OperationCanceledException) { }
            catch (IOException) { }
        }
    }
    public void Dispose() {
        _stop.Cancel();
        _accept.GetAwaiter().GetResult();
        _listener.Stop();
        Task.WhenAll(_connections).GetAwaiter().GetResult();
        _stop.Dispose();
    }
}
