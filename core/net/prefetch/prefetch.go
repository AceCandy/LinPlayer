// Package prefetch 是多线程加载(本地缓存预取代理)。
//
// **Rust 版是黄金实现。**
// 起播时在 127.0.0.1:<随机端口> 起本地 HTTP 服务当播放源交给 mpv。代理用 2~4 个并发
// Range 连接对真实播放流「超前」拉取,再**顺序**喂给播放器:
//   - 多连接聚合带宽 → 弱网也能喂满,少卡顿
//   - 播放器从 localhost 读 → 抖动被缓冲吸收
//   - 代理对上游网络错误自带重试,mpv 只面对始终在线的 localhost
//
// # 窗口是「每连接」的,不是全局的
//
// 旧版把取数窗口放在会话上全局**共用**,每条进来的 HTTP 请求都把游标拽到自己的起点。
// mpv 探测 MKV(带大字体附件、索引在末尾的片子)会在旧连接没关时就新开一条 ——
// 后者一重置,前一条正在等的分段就**再也没人去拉了**:响应头已发出、body 一个字节不来
// = 有流量、黑屏无声、永远缓冲。
//
// 现在每条连接持有自己的窗口 + 自己的 worker,连接之间互不干扰;
// 每条连接只**向前顺序**取数(观影场景本就是顺序的),跳转 = mpv 开新连接。
// 共享的只有探测结果、上游地址与那份环形磁盘缓存。
package prefetch

import (
	"linplayer/core/bus"
	"linplayer/core/httpx"
	"linplayer/core/net/tlspolicy"

	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// MaxReadAhead **预取超前窗口**上限:一条连接最多比播放位置提前拉这么多。
//
// ★ 它和「缓存上限」是**两件事**。这两件事以前共用一个值,是个真雷:
// 缓存上限放开到 GB 级后,预取窗口跟着变成 GB 级,一条连接会一路狂拉几个 G。
// 而被播放器丢下的连接**正是照着这个窗口把量拉满才停**,于是「跳一次进度条」的
// 代价从白拉 32MB 升级成白拉几 GB。
//
// 超前量本来也不需要大:真正的大缓冲由 mpv 自己的 demuxer cache 扛。
// 64MB 在最慢的实测链路(~1.3MB/s)上也有 ~45 秒余量。
const MaxReadAhead int64 = 64 * 1024 * 1024

// defaultChunkTimeout 单次取数的**空闲**上限。
//
// ★★ 存在的理由不是「调优」,是**兜底**:上游不回话时请求会**永远**等下去 ——
// 没重试、没日志、没回退,而供给端跟着一起等,mpv 收到 206 头之后一帧不出。
// 用户报的「没画面没声音、完全看不到流量」就是这个形态。宁可慢,不可吊死。
//
// ★★ 必须是**空闲**超时不是**整体**超时。实测最慢的链路(56~143KB/s)拉满一个
// 4MB 分段**合法地要 29~62 秒**,但块与块之间从不空闲 20 秒。
// 整体超时会把这种「慢但能用」的链路误杀,还会一路重试放大负载 ——
// 修一个静默卡死,换来一个更响的。**别改回去。**
const defaultChunkTimeout = 20 * time.Second

// chunkTimeoutOverride 只为测试存在:按真值跑一遍要一分钟,那种测试没人愿意留在门禁里
// (而没门禁的修复迟早退化)。
var chunkTimeoutOverride atomic.Int64

// SetChunkTimeoutForTest 覆盖空闲超时。**只给测试用**,传 0 恢复真值。
func SetChunkTimeoutForTest(d time.Duration) { chunkTimeoutOverride.Store(int64(d)) }

func chunkTimeout() time.Duration {
	if v := chunkTimeoutOverride.Load(); v > 0 {
		return time.Duration(v)
	}
	return defaultChunkTimeout
}

// readAheadBytes 预取超前窗口字节数,钳进 [每 worker 一段, MaxReadAhead]。
//
// ★ 入参是用户设的**缓存上限**,但它在这里只当天花板用(用户把缓存调得很小时,
// 超前量不该超过缓存本身,否则刚拉回来的段立刻被环形覆盖掉)。
//
// ★ Rust 侧原来把 max/min 用反了:用户的缓存上限本该是**天花板**,那样写却成了下限,
// 默认 1GB 直接把窗口顶到硬上限。移植时别照抄那个反了的版本。
func readAheadBytes(threads int, cacheLimit int64) int64 {
	floor := ChunkSize * int64(threads)
	if floor > MaxReadAhead {
		floor = MaxReadAhead
	}
	v := cacheLimit
	if v < floor {
		v = floor
	}
	if v > MaxReadAhead {
		v = MaxReadAhead
	}
	return v
}

// ResignFn 上游签名链失效时的重签回调:重走取流拿新地址。nil = 不支持重签。
type ResignFn func(ctx context.Context) string

// CacheOptions 只接收调用方生成的媒体身份摘要，不保存授权地址或账号原文。
type CacheOptions struct {
	Identity string
	Bitrate  int64
	Budget   int64
}

// StartCached 两种内核共用的媒体代理；readAhead 只决定是否提前取数。
func StartCached(ctx context.Context, u string, threads int, cacheLimit int64, readAhead bool, options CacheOptions) (*Handle, error) {
	if err := ConfigureMediaCache(options.Budget); err != nil {
		return nil, err
	}
	threads = min(max(threads, 2), 4)
	window := int64(1)
	ua := httpx.UA()
	if readAhead {
		limit := MaxReadAhead
		// 码率只用于近似预取时长，不作为媒体版本证据；先钳再乘避免溢出。
		if options.Bitrate > 0 {
			limit = min(limit, min(options.Bitrate, MaxReadAhead*8/60)*60/8)
		}
		window = max64(1, (limit+ChunkSize-1)/ChunkSize)
		ua = httpx.PreloadUA()
	} else {
		threads = 2
	}
	return start(ctx, u, threads, min(cacheLimit, mediaLimit), window, ua, nil, options)
}

// Handle 一个运行中的代理。**Close 即停服**,放行所有连接的 worker 退出。
type Handle struct {
	URL string
	// CachedURL **只读缓存端点**:只吐已经在盘上的字节,一个上游请求都不发。
	//
	// ★★ 给缩略图用。用户 2026-09-03 定的规矩是「缓存了的能用,没缓存的不能用」——
	// 那条规矩落在这条 URL 上,不落在调用方的 if 里。
	CachedURL string
	origin    *origin
	ln        net.Listener
	once      sync.Once
	wg        sync.WaitGroup
	connMu    sync.Mutex
	conns     map[net.Conn]bool
	identity  string
}

// cachedPath 只读端点的路径;status416 「这段没有」的回应。
const (
	cachedPath = "/cached"
	status416  = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
)

// CachedSpans 目前**已经在盘上**的区间,按占全片的比例给(0~1),按位置升序、不重叠。
//
// ★ 为什么给比例不给字节:调用方(进度条)要的是「哪一段有缩略图」,
// 而它手上只有时长。字节→时间的换算这里做不了(需要码率曲线),
// 线性折算的误差在一条 800px 的进度条上小于一个像素,不值得为它引一套索引。
//
// ★ 宁可报少不可报多:报多了 = 用户划过去看见「有」却出不来图,那是坏了;
// 报少了只是保守。所以这里只认**整段就绪**,在飞的段一律不算。
func (h *Handle) CachedSpans() [][2]float64 {
	o := h.origin
	if o.totalSize <= 0 {
		return nil
	}
	total := float64(o.totalSize)
	chunks := (o.totalSize + ChunkSize - 1) / ChunkSize
	var out [][2]float64
	run := int64(-1)
	flush := func(endChunk int64) {
		if run < 0 {
			return
		}
		a := float64(run*ChunkSize) / total
		b := float64(endChunk*ChunkSize) / total
		if b > 1 {
			b = 1
		}
		out = append(out, [2]float64{a, b})
		run = -1
	}
	for c := int64(0); c < chunks; c++ {
		if o.disk.has(c) {
			if run < 0 {
				run = c
			}
			continue
		}
		flush(c)
	}
	flush(chunks)
	return out
}

// Upstream 这个代理正在代理哪条上游地址。预热复用要靠它比对。
func (h *Handle) Upstream() string {
	h.origin.upMu.Lock()
	defer h.origin.upMu.Unlock()
	return h.origin.url
}

// CachePathForTest 缓存文件路径。**只给测试用**(验换片时旧文件真的删了)。
func (h *Handle) CachePathForTest() string { return h.origin.disk.path }

// Identity 是调用方生成的稳定身份摘要，仅供同流代理复用比对。
func (h *Handle) Identity() string { return h.identity }

// Close 取消并等待所有连接与取数退出；完整持久块保留，会话临时缓存删除。
func (h *Handle) Close() {
	h.once.Do(func() {
		h.origin.closed.Store(true)
		h.origin.cancel()
		h.origin.stop.notifyAll()
		_ = h.ln.Close()
		h.connMu.Lock()
		for c := range h.conns {
			_ = c.Close()
		}
		h.connMu.Unlock()
		h.wg.Wait()
		h.origin.workers.Wait()
		h.origin.disk.close()
	})
}

type origin struct {
	ctx         context.Context
	cancel      context.CancelFunc
	workers     sync.WaitGroup
	firstSupply sync.Once
	validator   string
	upMu        sync.Mutex
	url         string
	// resolved 跟随 302 后的**最终**地址(CDN 直链);worker 优先打它。
	//
	// ★ 为什么值得单独存:某类服务端的直传流是 302 跳 CDN,而每段都是一次独立请求 ——
	// 不缓存最终地址,就是**每 4MB 重走一遍 302**。实测 0.67s/段,占单段 TTFB(1.4s)
	// 的一半,并行省下的时间全赔在建连上:3 线程 4.0MB/s 反而**慢于**单连接 4.3MB/s,
	// 多线程加载成了负优化。原版 Emby 无重定向,此字段恒为空,零影响。
	resolved string
	// resolvedExp 落点的到期时刻(已扣掉 resolveMargin)。零值 = 上游没声明有效期,不过期。
	//
	// ★ 值从 302 的 `Cache-Control: max-age` 来,见 resolve.go ——
	//   服主要的「请求直链的间隔」就落在这里:签名多久换一次,我们就多久打一次后端。
	resolvedExp    time.Time
	resignDisabled bool

	totalSize       int64
	contentType     string
	threads         int
	readAheadChunks int64
	// ua 上游请求的 User-Agent。旁路模式下**它就是用户正在看的那一路**,
	// 用 LinPlayerPreload 那条道会让服主在日志里把它错认成一次旁路预取。
	ua        string
	closed    atomic.Bool
	stop      notifier
	client    *http.Client
	onInvalid ResignFn
	disk      *diskCache

	// liveMu / liveMap 正在拉取中的分段(段号 -> 载体)。**「边收边吐」的登记处**。
	liveMu  sync.Mutex
	liveMap map[int64]*live

	// probes 只给测试看:上游被打了几次(验「302 只跟随一次」等)。
	probes atomic.Int64
}

// Start 起代理并返回本地播放 URL。失败返回错误,调用方**回退直连在线地址**。
//
// threads 会被钳进 2~4;cacheLimit 是用户设的视频缓存上限(决定环形槽位数)。
func Start(ctx context.Context, upstreamURL string, threads int, cacheLimit int64, onInvalid ResignFn) (*Handle, error) {
	if threads < 2 {
		threads = 2
	}
	if threads > 4 {
		threads = 4
	}
	readAhead := max64(readAheadBytes(threads, cacheLimit)/ChunkSize, 1)
	return start(ctx, upstreamURL, threads, cacheLimit, readAhead, httpx.PreloadUA(), onInvalid)
}

// StartPassthrough 起一个**只旁路落盘**的代理:mpv 要哪一段就拉哪一段,顺手写进
// 环形缓存,**一段都不超前拉**。
//
// ★ 「多线程加载」关着的时候走这条。缩略图和进度条上那条「哪一段有图」的带子
// 要的是「本地有没有这段字节」,那和「要不要提前拉」是两件事 ——
// 从前它们共用一个开关,于是关掉多线程加载 = 连缩略图一起没了(用户 2026-09-06 报的)。
// ★ worker 仍然是 2 个:超前窗口只有一段时它们本来就只有一段在飞,
// 而把 worker 压到 1 会碰上当初把 threads 钳进 2~4 的那个竞态修复。
func StartPassthrough(ctx context.Context, upstreamURL string, cacheLimit int64, onInvalid ResignFn) (*Handle, error) {
	return start(ctx, upstreamURL, 2, cacheLimit, 1, httpx.UA(), onInvalid)
}

func start(ctx context.Context, upstreamURL string, threads int, cacheLimit int64,
	readAheadChunks int64, ua string, onInvalid ResignFn, cacheOptions ...CacheOptions) (*Handle, error) {
	o := &origin{
		url: upstreamURL, threads: threads, ua: ua,
		readAheadChunks: readAheadChunks,
		// ★ 预取拉上游用 LinPlayerPreload 这条 UA 道(SPEC §14.1):
		//   服主要能把「替 mpv 提前拉的旁路请求」和「用户正在看的那一路」在日志里分开。
		// ★ 走 tlspolicy:不走的话自签名服务器上「多线程加载」一开就取不到流
		// ★ CheckRedirect 不是为了拦跳转,是为了**顺路把 302 的 Cache-Control 收下来**
		//   —— 直链落点能缓存多久,只有那一跳自己知道。见 resolve.go。
		client:    &http.Client{Transport: tlspolicy.Transport(), CheckRedirect: captureRedirectTTL},
		onInvalid: onInvalid,
		liveMap:   map[int64]*live{},
	}
	probeAt := time.Now()
	if err := o.probe(ctx, upstreamURL); err != nil {
		bus.Logf("info", "phase=cache_probe elapsed_ms=%d success=false", time.Since(probeAt).Milliseconds())
		return nil, err
	}
	bus.Logf("info", "phase=cache_probe elapsed_ms=%d success=true version_verified=%t", time.Since(probeAt).Milliseconds(), o.validator != "")
	restoreAt := time.Now()
	var disk *diskCache
	var err error
	if len(cacheOptions) > 0 && cacheOptions[0].Budget > 0 && cacheOptions[0].Identity != "" && o.validator != "" {
		disk, err = newPersistentCache(o.totalSize, cacheOptions[0].Identity, o.validator)
	}
	if disk == nil {
		// 磁盘不可用或没有可信版本时仍保持会话内边收边吐，不复用旧媒体字节。
		disk, err = newDiskCache(o.totalSize, cacheLimit, threads)
		o.validator = ""
	}
	if err != nil {
		return nil, fmt.Errorf("建缓存文件失败: %w", err)
	}
	bus.Logf("info", "phase=cache_restore elapsed_ms=%d persistent=%t restored_blocks=%d", time.Since(restoreAt).Milliseconds(), disk.persistent, len(disk.slots))
	o.disk = disk
	o.ctx, o.cancel = context.WithCancel(context.Background())

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		o.cancel()
		disk.close()
		return nil, fmt.Errorf("起本地代理失败: %w", err)
	}
	h := &Handle{
		// 路径带扩展名:ffmpeg 会拿 URL 尾巴猜容器格式,白送的线索没理由不给
		URL:       "http://" + ln.Addr().String() + "/stream",
		CachedURL: "http://" + ln.Addr().String() + cachedPath,
		origin:    o, ln: ln, conns: map[net.Conn]bool{},
	}
	if len(cacheOptions) > 0 {
		h.identity = cacheOptions[0].Identity
	}
	h.wg.Add(1)
	go func() {
		defer h.wg.Done()
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			// 每条连接一个 goroutine:mpv 会为 seek 另开连接(我们回的是 Connection: close),
			// 串行处理的话新连接要等旧连接把整段喂完 —— 那就是 seek 卡死。
			h.connMu.Lock()
			if o.over() {
				h.connMu.Unlock()
				_ = conn.Close()
				return
			}
			h.conns[conn] = true
			h.wg.Add(1)
			h.connMu.Unlock()
			go func() {
				defer h.wg.Done()
				defer func() { h.connMu.Lock(); delete(h.conns, conn); h.connMu.Unlock() }()
				defer conn.Close()
				_ = o.handle(conn.(*net.TCPConn))
			}()
		}
	}()
	return h, nil
}

// probe 探总大小 + Content-Type:`Range: bytes=0-0` → 206 + `Content-Range: bytes 0-0/<total>`。
func (o *origin) probe(ctx context.Context, u string) error {
	ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	ctx, slot := withTTLSlot(ctx)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Range", "bytes=0-0")
	req.Header.Set("User-Agent", o.ua)
	req.Header.Set("Accept-Encoding", "identity")
	resp, err := o.client.Do(req)
	if err != nil {
		return fmt.Errorf("探测文件大小失败: %w", err)
	}
	defer resp.Body.Close()
	// 探测只需要一个字节；忽略Range的服务器可能返回整片，不能在这里下载完。
	_, _ = io.CopyN(io.Discard, resp.Body, 1)

	// 探测本来就跟完了 302,顺手把落点**和它声明的有效期**一起记下来给 worker 用
	o.noteResolved(u, resp.Request.URL.String(), slot.ttl())
	o.contentType = resp.Header.Get("Content-Type")
	if o.contentType == "" {
		o.contentType = "video/mp4"
	}
	cr := resp.Header.Get("Content-Range")
	if i := strings.LastIndex(cr, "/"); i >= 0 {
		o.totalSize, _ = strconv.ParseInt(strings.TrimSpace(cr[i+1:]), 10, 64)
	}
	if o.totalSize <= 0 {
		// ★ 取不到文件大小就**不能代理**:没有 total 就发不出正确的 Content-Length,
		//   播放器算不出进度条也没法 seek。调用方回退直连。
		return errors.New("上游没给文件大小,无法代理(调用方回退直连)")
	}
	mediaType := strings.ToLower(strings.Split(o.contentType, ";")[0])
	manifest := strings.Contains(mediaType, "mpegurl") || mediaType == "application/dash+xml"
	if !manifest && !strings.Contains(strings.ToLower(resp.Header.Get("Cache-Control")), "no-store") && validRangeResponse(resp, 0, 0, o.totalSize) && strongETag(resp.Header.Get("ETag")) {
		o.validator = resp.Header.Get("ETag")
	}
	return nil
}

func strongETag(s string) bool {
	if len(s) < 2 || s[0] != '"' || s[len(s)-1] != '"' {
		return false
	}
	for i := 1; i < len(s)-1; i++ {
		if s[i] < 0x21 || s[i] == '"' || s[i] == 0x7f {
			return false
		}
	}
	return true
}

func validRangeResponse(r *http.Response, start, end, total int64) bool {
	return r.StatusCode == http.StatusPartialContent &&
		r.Header.Get("Content-Range") == fmt.Sprintf("bytes %d-%d/%d", start, end, total) &&
		(r.ContentLength < 0 || r.ContentLength == end-start+1) &&
		(r.Header.Get("Content-Encoding") == "" || r.Header.Get("Content-Encoding") == "identity")
}

func (o *origin) chunkLen(c int64) int {
	start := c * ChunkSize
	end := start + ChunkSize
	if end > o.totalSize {
		end = o.totalSize
	}
	return int(end - start)
}

func (o *origin) over() bool { return o.closed.Load() }

// liveBegin 开一个「正在拉取」的载体给 worker 喂。
//
// ★ 同一段可能被**两条连接**同时拉,但登记处只认先到的那个:后到者拿回一个
// **没登记**的载体,自己拉自己的 —— 绝不能两个 worker 往同一个载体里喂,
// 那是把两份字节流交错拼进一个 buffer,直接出错帧。
func (o *origin) liveBegin(c int64) (*live, bool) {
	o.liveMu.Lock()
	defer o.liveMu.Unlock()
	if _, taken := o.liveMap[c]; taken {
		return newLive(o.chunkLen(c)), false // 后到者:自己拉自己的,不登记
	}
	l := newLive(o.chunkLen(c))
	o.liveMap[c] = l
	return l, true
}

// liveEnd 喂食结束。
//
// ★ **必须在落盘之后调** —— 先摘牌再落盘会留出一个「两边都查不到」的空档,
// 供给端在那一瞬只能干等 250ms 兜底。
func (o *origin) liveEnd(c int64, me *live, registered bool) {
	me.finish()
	if !registered {
		return
	}
	o.liveMu.Lock()
	defer o.liveMu.Unlock()
	if l, ok := o.liveMap[c]; ok && l == me {
		delete(o.liveMap, c)
	}
}

func (o *origin) liveGet(c int64) *live {
	o.liveMu.Lock()
	defer o.liveMu.Unlock()
	return o.liveMap[c]
}

// fetchChunk 拉一段。返回 nil = 三次都失败。
//
// ★★ **必须校验长度**:分段是按 pos/ChunkSize 定位的,收下一个短包会让供给端
// 写完这几字节后把窗口推到下一段,而 pos 仍落在本段内 → 下一轮又来要这一段,
// 可游标早过了它,**永远没人重拉 → 永远缓冲**。
// 上游/CDN 截断、以及反代在 chunked 路径上遇错后仍补上合法结束块,都会产出
// 这种「格式合法但短」的响应。
func (o *origin) fetchChunk(ctx context.Context, c int64, l *live) []byte {
	start := c*ChunkSize + int64(l.base)
	end := c*ChunkSize + int64(o.chunkLen(c)) - 1
	want := l.cap

	for attempt := 0; attempt < 3; attempt++ {
		if ctx.Err() != nil || o.over() {
			return nil
		}
		u, usedResolved := o.upstreamURL()

		badAddr, retry := o.fetchOnce(ctx, u, start, end, l)
		// 取消可能发生在全部字节已到、最后一次Read尚未返回EOF时。
		// 范围与版本已在喂数据前验证，完整块仍可提交，不能把它当残块丢掉。
		if !badAddr && l.len() == want && (!retry || ctx.Err() != nil) {
			return l.snapshot()
		}
		if !badAddr && !retry {
			// 长度不符:同 URL 重试(短了不能收,见函数头)
			retry = true
		}
		if badAddr {
			/* 4xx/5xx / 空闲超时 = 这个地址不灵(短效签名链到期最常见)。
			   ★ **先怪 CDN 直链,再怪签名链。** CDN 落点通常自带时效签名,过期后
			     只需重走一次 302 就能拿到新落点 —— 这时候去调重签回调(重走取流)
			     是杀鸡用牛刀,还平白给服务端加一次接口压力。 */
			if usedResolved {
				o.dropResolved()
			} else {
				o.refreshUpstream(ctx)
			}
		}
		if attempt < 2 {
			select {
			case <-time.After(time.Duration(300*(attempt+1)) * time.Millisecond):
			case <-ctx.Done():
				return nil
			}
		}
	}
	return nil
}

// fetchOnce 返回 (地址不灵, 该重试同一地址)。
func (o *origin) fetchOnce(ctx context.Context, u string, start, end int64, l *live) (badAddr, retry bool) {
	o.probes.Add(1)
	reqCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	reqCtx, slot := withTTLSlot(reqCtx)
	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, u, nil)
	if err != nil {
		return true, false
	}
	req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", start, end))
	req.Header.Set("User-Agent", o.ua)
	if o.validator != "" {
		req.Header.Set("If-Range", o.validator)
		req.Header.Set("Accept-Encoding", "identity")
	}

	// 建连 + 等响应头:这一段可以用整体超时,它本来就该在几秒内完成
	type res struct {
		r   *http.Response
		err error
	}
	ch := make(chan res)
	go func() {
		r, e := o.client.Do(req)
		select {
		case ch <- res{r, e}:
		case <-reqCtx.Done():
			if r != nil {
				_ = r.Body.Close()
			}
		}
	}()
	var resp *http.Response
	select {
	case v := <-ch:
		if v.err != nil {
			return false, true // 纯网络抖动,重试同一 URL
		}
		resp = v.r
	case <-time.After(chunkTimeout()):
		return true, false // 连响应头都等不到 = 这个地址不灵
	case <-ctx.Done():
		return false, false
	}
	defer resp.Body.Close()
	// 条件Range回200、范围偏移或版本变化时，不能把新版本拼到旧缓存/live里。
	if o.validator != "" && (!validRangeResponse(resp, start, end, o.totalSize) || resp.Header.Get("ETag") != o.validator) {
		return false, false
	}
	if resp.StatusCode != http.StatusPartialContent && (resp.StatusCode < 200 || resp.StatusCode >= 300) {
		return true, false
	}
	// 打的是 Emby 直链、又真跳转了 = 拿到一个新落点,连同新的有效期一起换上
	o.noteResolved(u, resp.Request.URL.String(), slot.ttl())

	/* 收体:**每来一块就重置计时**,只有真的停止吐字节才判死。
	   ★ 收到就 feed —— 这就是「边收边吐」:供给端不再等整段落盘。
	     skip 让重试从上一轮的断点续上。 */
	skip := l.len()
	buf := make([]byte, 64*1024)
	for {
		type rd struct {
			n   int
			err error
		}
		rc := make(chan rd, 1)
		go func() { n, e := resp.Body.Read(buf); rc <- rd{n, e} }()
		select {
		case v := <-rc:
			if v.n > 0 {
				l.feed(&skip, buf[:v.n])
			}
			if v.err == io.EOF {
				return false, false // 正常读完
			}
			if v.err != nil {
				return false, true // 读体出错,重试
			}
		case <-time.After(chunkTimeout()):
			return true, false // 停吐了
		case <-ctx.Done():
			return false, false
		}
	}
}

// refreshUpstream 上游签名链失效 → 调重签回调换新地址。
//
// ★ **只有回调拿不到地址才停用重签**。原来「重签回来的地址和旧的一样」也停用,
// 那是错的:开了线路优选时上游是本机反代,它一个 502 就会走到这里,而重签当然
// 还是解析出同一个地址 —— 于是**一次网关抖动就把重签永久关掉**,
// 等这部片真的播到签名过期(长片常见)时,已经没人能换地址了 → 断流。
func (o *origin) refreshUpstream(ctx context.Context) {
	o.upMu.Lock()
	if o.onInvalid == nil || o.resignDisabled {
		o.upMu.Unlock()
		return
	}
	cb := o.onInvalid
	o.upMu.Unlock()

	fresh := cb(ctx)
	o.upMu.Lock()
	defer o.upMu.Unlock()
	if strings.TrimSpace(fresh) == "" {
		o.resignDisabled = true // 回调压根拿不到地址,停用避免刷接口
		return
	}
	o.url = fresh
	o.resolved, o.resolvedExp = "", time.Time{}
}

func max64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}
