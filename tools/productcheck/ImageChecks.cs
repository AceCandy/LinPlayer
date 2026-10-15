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
        using var server = new ImageServer();
        var core = (CoreClient)RuntimeHelpers.GetUninitializedObject(typeof(CoreClient));
        typeof(CoreClient).GetProperty(nameof(CoreClient.LocalBaseUrl))!.SetValue(core, server.Address);
        typeof(CoreClient).GetProperty(nameof(CoreClient.LocalToken))!.SetValue(core, "fixture");
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
                .Select(id => JsonSerializer.SerializeToElement(new { id, name = id, type = "Movie" })).ToList());
            Border Front() => (Border)typeof(Hero).GetField("_front", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(hero)!;
            Bitmap? Visible() => Front().GetVisualDescendants().OfType<Image>().Select(i => i.Source).OfType<Bitmap>().FirstOrDefault();
            Wait(() => Visible()?.PixelSize.Height == 800);
            var layer = Front();
            Check(!server.Requests.Any(r => r.Path == "/img" && r.Source.Contains("hero-c")), "轮播提前下载了整组原图");
            server.Release.TrySetResult();
            Wait(() => Visible() is { PixelSize.Height: not 800 });
            Check(ReferenceEquals(layer, Front()), "缓存升级原图错误地翻转图层");
            Check(Front().Opacity == 1, "原图更新隐藏了当前图层");
        }
        finally { server.Release.TrySetResult(); window.Close(); Dispatcher.UIThread.RunJobs(); }
        Console.WriteLine("轮播图片:缓存探测不回源、旧缓存预览、原图无缩放参数、按显示尺寸解码、鉴权与真实Hero预览升级通过");
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
            if (path == "/img-cache" && height != "800") context.Response.StatusCode = 404;
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
