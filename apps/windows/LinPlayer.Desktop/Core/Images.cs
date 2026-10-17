using System.Collections.Concurrent;
using Avalonia.Media.Imaging;
using Avalonia.Threading;

namespace LinPlayer.Desktop.Core;

/// <summary>
/// 封面加载。图片不过 FFI、也不过命令队列,走核心层的本地 HTTP(<c>/img?src=…</c>,
/// SPEC §6)—— 一屏几十张封面走命令队列,等于把几十 MB 字节塞进事件队列排队。
///
/// <para>上游地址由这里拼,但凭据不进 URL:核心层按 origin 查白名单自己带 token,
/// 所以缓存键里没有 token,重登一次整盘缓存不会作废。
/// 三条规矩,少一条就会「响应慢」而且查不出来:每个 await 都 ConfigureAwait(false)、
/// 按显示高度解码、解好的位图留一份 —— 见下面各自的注释。</para>
/// </summary>
public static class Images
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(30) };
    private static readonly ConcurrentDictionary<string, Lazy<Task<Bitmap?>>> Inflight = new();

    /// <summary>
    /// 解好的位图留一份。
    ///
    /// <para>核心层那两层缓存存的是压缩字节,省的是回源;而每次翻回首页仍然要
    /// 重走一遍「本地 HTTP + 解码」,而解码在 UI 侧,核心层再快也省不掉。
    /// 按张数封顶而不是字节数:位图尺寸是我们自己按显示高度定的,量级已知。
    /// 400 张 ≈ 60 MB,够铺满好几页网格。</para>
    /// </summary>
    private const int CacheMax = 400;

    private static readonly object CacheLock = new();
    private static readonly Dictionary<string, (Bitmap Bmp, long Used)> Cache = new();
    private static long _tick;

    /// <summary>
    /// 拼一条 Emby 图片地址。<paramref name="kind"/> 只认 Primary / Backdrop / Logo。
    /// </summary>
    /// <summary> 尺寸交给 /img 的 h=,别写进 src —— 写进去等于每种尺寸一个缓存键。</summary>
    public static string EmbyImageUrl(string server, string itemId, string kind, bool original = false)
    {
        // Backdrop 要带序号,Primary/Logo 不带 —— 不带的话某些 fork 直接 404
        var seg = kind == "Backdrop" ? "Backdrop/0" : kind;
        return $"{server.TrimEnd('/')}/Items/{itemId}/Images/{seg}" + (original ? "" : "?quality=90");
    }

    /// <summary>展示时沿集、季、整剧回退，不修改条目的海报归属。</summary>
    public static IEnumerable<string> ArtworkUrls(string server, string id, string type,
        string seasonId = "", string seriesId = "")
    {
        yield return EmbyImageUrl(server, id, "Primary");
        if (type == "Episode")
        {
            yield return EmbyImageUrl(server, id, "Backdrop");
            if (seasonId != "" && seasonId != id)
                yield return EmbyImageUrl(server, seasonId, "Primary");
            if (seriesId != "" && seriesId != id)
                yield return EmbyImageUrl(server, seriesId, "Backdrop");
        }
        if (type is "Episode" or "Season" && seriesId != "" && seriesId != id)
            yield return EmbyImageUrl(server, seriesId, "Primary");
    }

    /// <summary>前一张缺失或加载失败后才请求下一张；全部失败保留调用方文字占位。</summary>
    public static async Task<Bitmap?> LoadFirstAsync(CoreClient core, IEnumerable<string> urls, int maxHeight)
    {
        foreach (var url in urls.Distinct())
        {
            var image = await LoadAsync(core, url, maxHeight).ConfigureAwait(false);
            if (image is not null) return image;
        }
        return null;
    }

    /// <summary>经由本地数据通道取一张图。取不到返回 null —— <b>调用方要能画出「没有图」那一版</b>。</summary>
    public static Task<Bitmap?> LoadAsync(CoreClient core, string upstreamUrl, int maxHeight,
        bool original = false, bool cacheOnly = false)
    {
        if (string.IsNullOrEmpty(core.LocalBaseUrl) || string.IsNullOrEmpty(upstreamUrl))
            return Task.FromResult<Bitmap?>(null);

        var route = cacheOnly ? "img-cache" : "img";
        var url = $"{core.LocalBaseUrl}/{route}?src={Uri.EscapeDataString(upstreamUrl)}" +
            (original ? "" : $"&h={Ladder(maxHeight)}");
        // 原图字节共用核心缓存；解码缓存按实际显示像素分开，不把小窗口位图放大复用。
        var height = original ? maxHeight : Ladder(maxHeight);
        var key = original ? $"{url}#decode={height}" : url;

        // 命中已解码的:**同步返回**,一次 await 都不排。翻回上一页时整屏封面当场就在。
        lock (CacheLock)
        {
            if (Cache.TryGetValue(key, out var hit))
            {
                Cache[key] = (hit.Bmp, ++_tick);
                return Task.FromResult<Bitmap?>(hit.Bmp);
            }
        }

        // 同一张图并发请求合流:一屏几十张卡里同一部剧可能出现好几次
        // GetOrAdd可并发调用工厂；只有成功入字典的Lazy才能启动HTTP。
        return Inflight.GetOrAdd(key, _ => new Lazy<Task<Bitmap?>>(() => Fetch(core, url, height, key))).Value;
    }

    /// <summary>轮播预览只查本地原图及已有尺寸缓存，缺图不触发下载。</summary>
    public static async Task<Bitmap?> LoadCachedHeroAsync(CoreClient core, string server, string itemId, int height)
    {
        foreach (var kind in new[] { "Backdrop", "Primary" })
        {
            var original = await LoadAsync(core, EmbyImageUrl(server, itemId, kind, original: true),
                height, original: true, cacheOnly: true).ConfigureAwait(false);
            if (original is not null) return original;
            foreach (var rung in Rungs.Reverse())
            {
                var cached = await LoadAsync(core, EmbyImageUrl(server, itemId, kind), rung,
                    cacheOnly: true).ConfigureAwait(false);
                if (cached is not null) return cached;
            }
        }
        return null;
    }

    /// <summary>
    /// 解码高度归档。
    ///
    /// <para>缓存键里带着 <c>h=</c>,而它是从卡片实宽算来的 —— 自从卡片改成按行宽
    /// 均分,这就是个连续量:收一下侧栏 158 变 172,h 从 474 变 516,缓存键当场不认,
    /// 整屏封面重取重解(用户 2026-09-03:「收起/展开侧边栏图片都要重新加载」)。
    /// 所以按一张粗档位表取整,相邻档差约 35%,侧栏收放落不出一档去。
    /// 档位只能往上取:往下取会解出比显示尺寸还小的图,拉大是糊的,而且不报错。</para>
    /// </summary>
    private static readonly int[] Rungs = [120, 180, 240, 330, 450, 600, 800, 1080, 1440, 1920];

    internal static int Ladder(int h)
    {
        if (h <= 0) return 0;
        foreach (var r in Rungs)
            if (h <= r) return r;
        return Rungs[^1];
    }

    /// <summary>
    /// 取图,失败**重试两次**。失败大多是瞬时的(上游 502 / 连接被掐 / 抢并发名额超时),
    /// 而卡片只在构造那一刻取一次 —— 不重试就成了「随机有些封面加载不出来」。
    ///
    /// <para><b>不重试 404</b>:没刮削封面的库很常见,为它们空转是白烧并发名额。</para>
    /// </summary>
    private static async Task<Bitmap?> Fetch(CoreClient core, string url, int maxHeight, string key)
    {
        try
        {
            for (var attempt = 0; ; attempt++)
            {
                var (bmp, retry) = await TryFetch(core, url, maxHeight, key).ConfigureAwait(false);
                if (bmp is not null || !retry || attempt >= 2) return bmp;
                // 退避:第一次 300ms,第二次 600ms。瞬时故障多半在这个量级内就过去了
                await Task.Delay(300 * (attempt + 1)).ConfigureAwait(false);
            }
        }
        finally
        {
            Inflight.TryRemove(key, out _);
        }
    }

    /// <summary>取一次。回 <c>(位图, 这次失败值不值得重试)</c>。</summary>
    private static async Task<(Bitmap? Bmp, bool Retry)> TryFetch(CoreClient core, string url, int maxHeight, string key)
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            // 图片通道走请求头鉴权(给 mpv 吃的那几条路才把 token 放 URL 里)
            req.Headers.Add("X-LP-Token", core.LocalToken);

            var t0 = Perf.Ms;
            /* 每个 await 都必须 ConfigureAwait(false)。
               这个方法是被**卡片构造函数**发起的,而卡片在 UI 线程上构造 ——
               不写的话每个 await 的续体都回到 UI 线程,于是:
                 · 解码在 UI 线程上做(一屏几十张 = UI 线程连续被占几百毫秒)
                 · 动画在这期间**不推进**,鼠标点了没反应 —— 这就是「卡顿不跟手」
                 · 连测出来的耗时都是假的:实测 localhost 上一张 0KB 的图「取了 103ms」,
                   那 103ms 全是在排队等 UI 线程,不是网络
               它不报错、不崩,只是整个应用变慢。 */
            using var resp = await Http.SendAsync(req).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode)
                return (null, resp.StatusCode != System.Net.HttpStatusCode.NotFound);
            await using var s = await resp.Content.ReadAsStreamAsync().ConfigureAwait(false);
            var ms = new MemoryStream();
            await s.CopyToAsync(ms).ConfigureAwait(false);
            ms.Position = 0;

            var t1 = Perf.Ms;
            /* 按**显示高度**解码,不要全尺寸解。
               `new Bitmap(stream)` 解出来的是原图分辨率 —— 一张 1000×1500 的海报
               画在 158×237 的槽里,白解了 40 倍的像素,还占着 6 MB 内存。
               而 maxHeight 是给上游的**建议**:实测某 fork 完全无视 maxWidth,
               所以「服务器会帮我们缩」这件事**不能当前提**,自己这一刀必须落。 */
            var bmp = DecodeToHeight(ms, maxHeight);
            if (Perf.On)
                Perf.Log($"图 取{t1 - t0:0}ms 解码{Perf.Ms - t1:0}ms {ms.Length / 1024}KB " +
                         $"→{bmp.PixelSize.Width}x{bmp.PixelSize.Height} " +
                         $"线程={(Dispatcher.UIThread.CheckAccess() ? "★UI★" : "后台")}");

            Remember(key, bmp);
            return (bmp, false);
        }
        catch
        {
            /* 取不到图**不是错误** —— 没刮削封面的库很常见,画占位就行。
               但抛出来的多半是超时 / 连接被掐这类**瞬时**故障,值得再试一次。 */
            return (null, true);
        }
    }

    /// <summary>
    /// 解码到目标高度。图本来就比目标矮就原样解 ——
    /// <b>放大不是缩放器的活</b>,那只会解出一张更糊也更大的图。
    /// </summary>
    private static Bitmap DecodeToHeight(MemoryStream ms, int maxHeight)
    {
        if (maxHeight <= 0) return new Bitmap(ms);
        try
        {
            var bmp = Bitmap.DecodeToHeight(ms, maxHeight, BitmapInterpolationMode.HighQuality);
            return bmp;
        }
        catch
        {
            // 某些格式解码器不支持指定高度。回落成原样解 —— 慢一点总比不显示强。
            ms.Position = 0;
            return new Bitmap(ms);
        }
    }

    /// <summary>放进缓存,超了就把最久没用的挤掉。</summary>
    private static void Remember(string url, Bitmap bmp)
    {
        lock (CacheLock)
        {
            Cache[url] = (bmp, ++_tick);
            if (Cache.Count <= CacheMax) return;
            /* 一次挤掉 1/4 而不是刚好一张:卡着上限的话之后**每存一张都要扫一遍**,
               扫描成本摊到每一次写入上。挤一批,下次扫描要等很久才来。
               挤掉的位图**不 Dispose** —— 屏幕上可能还有 Image 正指着它,
                 Dispose 掉就是当场少一张图(而且不报错)。交给 GC。 */
            foreach (var k in Cache.OrderBy(kv => kv.Value.Used).Take(CacheMax / 4).Select(kv => kv.Key).ToList())
                Cache.Remove(k);
        }
    }
}
