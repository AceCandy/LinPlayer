using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Runtime.CompilerServices;
using System.Reflection;
using System.Text.Json;
using Avalonia.Controls;
using Avalonia.VisualTree;
using LinPlayer.Desktop.Views;
using Avalonia;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using LinPlayer.Desktop.Core;

// 使用真实图片加载器与回环HTTP，验证原图请求和尺寸缓存隔离，不初始化播放器核心。
static class ImageChecks
{
    public static void Run()
    {
        var key = typeof(Hero).GetMethod("ArtworkKey", BindingFlags.NonPublic | BindingFlags.Static)!;
        var oldItem = JsonSerializer.SerializeToElement(new { id = "same", has_primary = false });
        var newItem = JsonSerializer.SerializeToElement(new { id = "same", has_primary = true });
        Check(!Equals(key.Invoke(null, [oldItem]), key.Invoke(null, [newItem])), "图片标记更新被同ID缓存吞掉");
        using var server = new ImageServer();
        var core = (CoreClient)RuntimeHelpers.GetUninitializedObject(typeof(CoreClient));
        typeof(CoreClient).GetProperty(nameof(CoreClient.LocalBaseUrl))!.SetValue(core, server.Address);
        typeof(CoreClient).GetProperty(nameof(CoreClient.LocalToken))!.SetValue(core, "fixture");
        ArtworkFallback(core, server);
        var preview = Images.LoadCachedHeroAsync(core, "http://media.test", "film", 360);
        Wait(() => preview.IsCompleted);
        Check(preview.Result is not null, "未找到已有缩略图缓存");
        Check(server.Requests.All(r => r.Path == "/img-cache"), "缓存探测错误地回源");
        Check(server.Requests.Any(r => r.Height == "800"), "未兼容旧桌面缓存档位");

        var source = Images.EmbyImageUrl("http://media.test", "film", "Backdrop", original: true);
        var original = Images.LoadAsync(core, source, 360, original: true);
        Wait(() => server.Requests.Any(r => r.Path == "/img"));
        Check(!original.IsCompleted, "原图等待测试未真正挂起下载");
        Check(preview.Result is not null, "等待原图丢失预览");
        server.Release.TrySetResult();
        Wait(() => original.IsCompleted);
        Check(original.Result?.PixelSize.Height == 360, "未按实际显示高度解码原图");
        var larger = Images.LoadAsync(core, source, 720, original: true);
        Wait(() => larger.IsCompleted);
        Check(larger.Result?.PixelSize.Height == 720, "大尺寸复用了小尺寸解码缓存");
        Check(server.Requests.Where(r => r.Path == "/img").All(r => r.Height is null && new Uri(r.Source).Query == ""),
            "原图请求包含尺寸或quality参数");
        Check(server.Authenticated, "图片请求未保留本地鉴权");
        server.Release = new(TaskCreationOptions.RunContinuationsAsynchronously);
        var concurrentSource = Images.EmbyImageUrl("http://media.test", "parallel", "Backdrop", original: true);
        using var barrier = new Barrier(16);
        var entered = 0;
        var parallel = Enumerable.Range(0, 16).Select(_ => Task.Factory.StartNew(() =>
        {
            barrier.SignalAndWait();
            var task = Images.LoadAsync(core, concurrentSource, 360, original: true);
            Interlocked.Increment(ref entered);
            return task;
        }, CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default).Unwrap()).ToArray();
        Wait(() => Volatile.Read(ref entered) == 16 && server.Requests.Any(r => r.Source == concurrentSource));
        server.Release.TrySetResult();
        var merged = Task.WhenAll(parallel);
        Wait(() => merged.IsCompleted);
        Check(merged.Result.All(bitmap => bitmap is not null), "并发图片加载失败");
        Check(server.Requests.Count(r => r.Source == concurrentSource) == 1, "同键并发启动了重复图片下载");

        server.Release = new(TaskCreationOptions.RunContinuationsAsynchronously);
        server.Requests.Clear();
        var hero = new Hero(core, null);
        // 此回归检查图层目标状态，取消过渡以免把动画中间值当成加载结果。
        foreach (var name in new[] { "_layerA", "_layerB" })
            ((Border)typeof(Hero).GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(hero)!).Transitions = null;
        var window = new Window { Width = 900, Height = 500, Content = hero };
        try
        {
            window.Show();
            Dispatcher.UIThread.RunJobs();
            hero.Show("http://media.test", new[] { "hero-a", "hero-b", "hero-c" }
                .Select(id => JsonSerializer.SerializeToElement(new { id, name = id, type_ = "Movie", has_primary = true, has_backdrop = true })).ToList());
            Border Front() => (Border)typeof(Hero).GetField("_front", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(hero)!;
            Bitmap? Visible() => Front().GetVisualDescendants().OfType<Image>().Select(i => i.Source).OfType<Bitmap>().FirstOrDefault();
            Wait(() => Visible()?.PixelSize.Height == 800);
            var layer = Front();
            Check(!server.Requests.Any(r => r.Path == "/img" && r.Source.Contains("hero-c")), "轮播提前下载了整组原图");
            server.Release.TrySetResult();
            Wait(() => Visible() is { PixelSize.Height: not 800 });
            Check(ReferenceEquals(layer, Front()), "缓存升级原图错误地翻转图层");
            Check(Front().Opacity == 1, "原图更新隐藏了当前图层");
            server.Requests.Clear();
            hero.Show("http://media.test", [JsonSerializer.SerializeToElement(new {
                id = "poster-only", name = "只有封面", type_ = "Movie", has_primary = true,
            })]);
            Wait(() => server.Requests.Any(r => r.Path == "/img" && r.Source.Contains("/poster-only/Images/Primary")));
            Check(server.Requests.All(r => !r.Source.Contains("/Backdrop") && !r.Source.Contains("/Logo")),
                "无背景图/Logo的轮播仍发起探测");
            server.Requests.Clear();
            hero.Show("http://media.test", [JsonSerializer.SerializeToElement(new {
                id = "no-art", name = "没有图片", type_ = "Movie", has_primary = false,
            })]);
            Dispatcher.UIThread.RunJobs();
            Check(server.Requests.IsEmpty, "明确无图的轮播仍发起请求");
            Check(Front().Opacity == 0, "无图作品仍显示上一部作品的背景");
        }
        finally { server.Release.TrySetResult(); window.Close(); Dispatcher.UIThread.RunJobs(); }
        Console.WriteLine("轮播图片:缓存探测不回源、旧缓存预览、原图无缩放参数、按显示尺寸解码、鉴权与真实Hero预览升级通过");
    }

    private static void ArtworkFallback(CoreClient core, ImageServer server)
    {
        var urls = Images.ArtworkUrls("http://media.test", "episode", "Episode", "season", "series").ToArray();
        Check(urls.Select(u => new Uri(u).AbsolutePath).SequenceEqual(new[] {
            "/Items/episode/Images/Primary", "/Items/episode/Images/Backdrop/0",
            "/Items/season/Images/Primary", "/Items/series/Images/Backdrop/0", "/Items/series/Images/Primary",
        }), "分集图片回退顺序错误");
        Check(Images.ArtworkUrls("http://media.test", "series", "Series", "", "other").Count() == 1,
            "整剧不应继承其它条目的海报");
        Check(Images.ArtworkUrls("http://media.test", "season", "Season", "", "series").Count() == 2,
            "季海报未回退整剧");
        Check(!Images.ArtworkUrls("http://media.test", "empty-movie", "Movie",
            hasPrimary: false, hasBackdrop: false).Any(), "明确无图电影仍生成请求");
        var known = CardItem.From(JsonSerializer.SerializeToElement(new {
            id = "known", name = "有图分集", type_ = "Episode", has_primary = true, has_backdrop = true,
        }));
        Check(CardItem.From(CardItem.ToJson(known)).HasBackdrop, "背景图标记往返丢失");
        server.Release.TrySetResult();
        server.Missing = source => !source.Contains("/season/Images/Primary");
        var item = CardItem.From(JsonSerializer.SerializeToElement(new {
            id = "episode", name = "单集", type_ = "Episode", has_primary = false,
            season_id = "season", series_id = "series",
        }));
        Check(CardItem.From(CardItem.ToJson(item)).SeasonId == "season" && !item.HasPrimary && !item.HasBackdrop,
            "图片归属或季ID往返错误");
        var card = new Card(core, "http://media.test", item, true);
        var window = new Window { Width = 400, Height = 300, Content = card };
        try
        {
            window.Show();
            Wait(() => card.GetVisualDescendants().OfType<Image>().Any(i => i.Source is Bitmap));
            Check(server.Requests.Select(r => r.Source).SequenceEqual(urls.Skip(2).Take(1)), "明确缺图仍探测本集或未停在季海报");
            var cached = Images.LoadFirstAsync(core, [urls[2]], 288);
            Check(cached.IsCompletedSuccessfully && cached.Result is not null, "已解码缓存命中不能延后到异步更新");
        }
        finally { window.Close(); Dispatcher.UIThread.RunJobs(); }
        foreach (var drawer in new[] { false, true })
        {
            var rowItem = item with { Id = drawer ? "drawer-episode" : "row-episode" };
            Control row = drawer
                ? (Control)typeof(Card).Assembly.GetType("LinPlayer.Desktop.Views.EpisodeDrawer")!
                    .GetMethod("Row", BindingFlags.NonPublic | BindingFlags.Static)!
                    .Invoke(null, [core, "http://media.test", rowItem, false, (Action<CardItem>)(_ => { })])!
                : new MediaRow(core, "http://media.test", rowItem, null, wide: true);
            var rowWindow = new Window { Width = 500, Height = 300, Content = row };
            try
            {
                rowWindow.Show();
                Wait(() => row.GetVisualDescendants().OfType<Image>().Any(i => i.Source is Bitmap));
            }
            finally { rowWindow.Close(); Dispatcher.UIThread.RunJobs(); }
        }
        server.Requests.Clear();
        server.Missing = _ => true;
        var missing = Images.LoadFirstAsync(core, Images.ArtworkUrls("http://media.test", "empty", "Episode", "empty-season", "empty-series"), 330);
        Wait(() => missing.IsCompleted);
        Check(missing.Result is null && server.Requests.Count == 5, "全部缺图没有结束回退或重试了404");
        server.Requests.Clear();
        server.Missing = null;
        server.Release = new(TaskCreationOptions.RunContinuationsAsynchronously);
        Console.WriteLine("图片回退:本集、季、整剧顺序，成功即停，全部缺图和归属不变通过");
    }

    private static void Check(bool ok, string message) { if (!ok) throw new Exception(message); }
    private static void Wait(Func<bool> done)
    {
        var end = DateTime.UtcNow.AddSeconds(10);
        while (!done() && DateTime.UtcNow < end) { Dispatcher.UIThread.RunJobs(); Thread.Sleep(10); }
        Dispatcher.UIThread.RunJobs();
        Check(done(), "等待图片结果超时");
    }

    private sealed class ImageServer : IDisposable
    {
        private readonly HttpListener listener = new();
        private readonly Task loop;
        private readonly ConcurrentBag<Task> workers = [];
        private readonly byte[] image;
        public readonly ConcurrentQueue<(string Path, string Source, string? Height)> Requests = new();
        public TaskCompletionSource Release = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public string Address { get; }
        public bool Authenticated = true;
        public Func<string, bool>? Missing;
        public ImageServer()
        {
            using var socket = new TcpListener(IPAddress.Loopback, 0);
            socket.Start();
            var port = ((IPEndPoint)socket.LocalEndpoint).Port;
            socket.Stop();
            Address = $"http://127.0.0.1:{port}";
            listener.Prefixes.Add(Address + "/");
            listener.Start();
            using var bitmap = new RenderTargetBitmap(new PixelSize(3840, 2160));
            using var bytes = new MemoryStream();
            bitmap.Save(bytes);
            image = bytes.ToArray();
            loop = Task.Run(Accept);
        }
        private async Task Accept()
        {
            try { while (listener.IsListening) workers.Add(Serve(await listener.GetContextAsync())); }
            catch (HttpListenerException) { /* 测试结束关闭监听。 */ }
            catch (ObjectDisposedException) { /* 测试结束关闭监听。 */ }
        }
        private async Task Serve(HttpListenerContext context)
        {
            var request = context.Request;
            var path = request.Url!.AbsolutePath;
            var height = request.QueryString["h"];
            Requests.Enqueue((path, request.QueryString["src"]!, height));
            if (request.Headers["X-LP-Token"] != "fixture") Authenticated = false;
            if (Missing?.Invoke(request.QueryString["src"]!) == true) context.Response.StatusCode = 404;
            else if (path == "/img-cache" && height != "800") context.Response.StatusCode = 404;
            else
            {
                if (path == "/img") await Release.Task;
                context.Response.ContentType = "image/png";
                context.Response.Headers["X-LP-Cache"] = "hit";
                await context.Response.OutputStream.WriteAsync(image);
            }
            context.Response.Close();
        }
        public void Dispose()
        {
            Release.TrySetResult();
            listener.Close();
            loop.GetAwaiter().GetResult();
            Task.WhenAll(workers).GetAwaiter().GetResult();
        }
    }
}
