package player

import (
	"bytes"
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/net/prefetch"
	"linplayer/core/paths"
	"strings"
)

var regWarmOnce sync.Once

func Test预热取流取消后晚到结果不能替换播放代理(t *testing.T) {
	for _, action := range []string{"离开详情", "正式起播", "新详情"} {
		t.Run(action, func(t *testing.T) { testCanceledWarm(t, action) })
	}
}

// testCanceledWarm 保留旧服务端响应，验证各入口都阻止过期任务覆盖播放代理。
func testCanceledWarm(t *testing.T, action string) {
	regWarmOnce.Do(registerWarmCommands)
	setup(t)
	entered := make(chan struct{})
	release := make(chan struct{})
	var staleStreamRequests atomic.Int64
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "/PlaybackInfo") {
			if !strings.Contains(r.URL.Path, "/old/") {
				http.Error(w, "新条目取流失败", http.StatusServiceUnavailable)
				return
			}
			close(entered)
			// 服务端仍让旧响应完成，不能靠删除候选或不放行旧请求得到通过。
			<-release
			fmt.Fprint(w, `{"PlaySessionId":"warm-old","MediaSources":[{"Id":"old","SupportsDirectStream":true,"DirectStreamUrl":"/old.mkv"}]}`)
			return
		}
		if r.URL.Path == "/old.mkv" {
			staleStreamRequests.Add(1)
		}
		http.ServeContent(w, r, "sample.mkv", time.Time{}, bytes.NewReader(make([]byte, 1024)))
	}))
	defer up.Close()
	defer closeSharedProxy()
	defer func() {
		select {
		case <-release:
		default:
			close(release)
		}
		warmMu.Lock()
		warmMu.Unlock()
	}()
	args := map[string]any{"server": up.URL, "token": "test", "user_id": "user", "item_id": "old"}
	if r := call(t, 9801, "prefs.preloadItem", args); !r.OK {
		t.Fatal(r.Msg)
	}
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("预热未发起取流")
	}
	switch action {
	case "离开详情":
		if r := call(t, 9802, "prefs.preloadCancel", nil); !r.OK {
			t.Fatal(r.Msg)
		}
	case "正式起播":
		_, err := PlayResolve(context.Background(), &emby.Session{Server: up.URL, Token: "test", UserID: "user"}, "new", 0, "")
		if err == nil {
			t.Fatal("新条目取流失败应返回错误")
		}
	case "新详情":
		args["item_id"] = "new"
		if r := call(t, 9802, "prefs.preloadItem", args); !r.OK {
			t.Fatal(r.Msg)
		}
	}
	active := proxyFor(context.Background(), up.URL+"/active.mkv", config.DefaultPrefs(), false)
	if active == nil {
		t.Fatal("正式播放代理未创建")
	}
	close(release)
	warmMu.Lock()
	warmMu.Unlock()
	if currentProxy() != active {
		t.Fatal("已经取消的旧预热替换了正式播放代理")
	}
	if got := staleStreamRequests.Load(); got != 0 {
		t.Fatalf("取消后仍发起旧流请求: %d", got)
	}
	if _, err := os.Stat(active.CachePathForTest()); err != nil {
		t.Fatal("正式播放缓存被删除")
	}
}

// ★★ C27 判据:预热之后起播必须**复用同一个句柄**,不能新起。
//
// 新起一个的表现:旧句柄一关,它的环形缓存文件就被删了 —— 详情页预热的那几十 MB
// 全部作废,起播还得从头再下一遍。慢链路上那是几分钟的白等,
// 而用户什么都看不出来(只觉得「预加载好像没用」)。
func TestC27_预热之后起播复用同一句柄(t *testing.T) {
	paths.SetRoot(t.TempDir())
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		const total = 8 * 1024 * 1024
		w.Header().Set("Content-Range", fmt.Sprintf("bytes 0-0/%d", total))
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write(make([]byte, 1))
	}))
	defer up.Close()
	defer closeSharedProxy()

	p := config.DefaultPrefs()
	first := proxyFor(context.Background(), up.URL+"/v.mkv", p, true)
	if first == nil {
		t.Fatal("该起得来")
	}
	cancelWarm()
	if _, err := os.Stat(first.CachePathForTest()); err != nil {
		t.Fatal("取消预热不能删除可复用缓存")
	}
	again := proxyFor(context.Background(), up.URL+"/v.mkv", p, true)
	if again != first {
		t.Fatal("同一条上游地址必须复用同一个句柄 —— 新起一个会把预热好的缓存删掉")
	}
	if again.URL != first.URL {
		t.Fatalf("复用的话本地地址也该一样:%s vs %s", first.URL, again.URL)
	}

	// 换一条流:必须换句柄(旧的连缓存一起收掉,否则端口和文件都留着)
	other := proxyFor(context.Background(), up.URL+"/other.mkv", p, true)
	if other == first {
		t.Fatal("换了流还复用旧句柄 = 播 A 的字节喂给 B")
	}
	if _, err := os.Stat(first.CachePathForTest()); err == nil {
		t.Fatal("旧句柄的缓存文件没删掉")
	}
}

// 每次取流的播放会话参数不同；同一媒体、同一线路要复用预热代理并换上新授权地址。
func TestProxyForReusesSameMediaAcrossPlaySessions(t *testing.T) {
	paths.SetRoot(t.TempDir())
	var probes atomic.Int32
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		probes.Add(1)
		w.Header().Set("Content-Range", fmt.Sprintf("bytes 0-0/%d", 8<<20))
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write(make([]byte, 1))
	}))
	defer up.Close()
	defer closeSharedProxy()
	p := config.DefaultPrefs()
	media := prefetch.CacheOptions{Identity: "same-media", Budget: 1 << 30}
	first := proxyFor(context.Background(), up.URL+"/v.mkv?PlaySessionId=a", p, true, media)
	if first == nil {
		t.Fatal("该起得来")
	}
	before := probes.Load()
	again := proxyFor(context.Background(), up.URL+"/v.mkv?PlaySessionId=b", p, true, media)
	if again != first || probes.Load() != before {
		t.Fatal("同一媒体换播放会话后重新探测")
	}
	if again.Upstream() != up.URL+"/v.mkv?PlaySessionId=b" {
		t.Fatalf("复用后仍用旧授权地址: %s", again.Upstream())
	}
	again.Close() // 后台校验发现换版本时代理会自己停掉,重试不能再拿回它
	if fresh := proxyFor(context.Background(), up.URL+"/v.mkv?PlaySessionId=e", p, true, media); fresh == nil || fresh == first {
		t.Fatal("已停的代理被复用")
	}
	otherLine := strings.Replace(up.URL, "127.0.0.1", "localhost", 1) + "/v.mkv?PlaySessionId=c"
	if line := proxyFor(context.Background(), otherLine, p, true, media); line == first {
		t.Fatal("换线路仍复用旧代理")
	}
	other := prefetch.CacheOptions{Identity: "other-media", Budget: 1 << 30}
	if h := proxyFor(context.Background(), up.URL+"/v.mkv?PlaySessionId=d", p, true, other); h == nil || h == first {
		t.Fatal("不同媒体共用了代理")
	}
}

// 关掉「多线程加载」**也要走本地代理** —— 只是不超前拉。
//
// ★★ 从前这里是 `if !on && !warmHit { return target.URL }`:开关关着连代理都不起,
// 而缩略图和进度条上「哪一段有图」唯一的字节来源就是代理的环形缓存 ——
// 于是「不开多线程加载就没有缩略图」。两个功能挂在一个开关上,用户 2026-09-06 点名。
func TestStartPrefetch关了多线程也要有本地代理(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		const total = 8 * 1024 * 1024
		w.Header().Set("Content-Range", fmt.Sprintf("bytes 0-0/%d", total))
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write(make([]byte, 1))
	}))
	defer up.Close()
	defer closeSharedProxy()

	p := config.DefaultPrefs()
	if len(p.PrefetchServers) != 0 {
		t.Fatal("前提不成立:多线程加载默认该是**按服务器手动开**的")
	}
	target := &emby.PlaybackTarget{URL: up.URL + "/v.mkv", PlayMethod: "DirectStream"}
	got := startPrefetch(context.Background(), nil, target, p)
	if got == target.URL {
		t.Fatal("关了多线程加载就直连了 —— 那样本地没有任何字节,缩略图整个不存在")
	}
	if !strings.HasPrefix(got, "http://127.0.0.1:") {
		t.Fatalf("该交给本地代理,实得 %s", got)
	}
}

// 转码流仍然直连:分段流套一层字节代理没有意义(这条别被上面那条顺手改坏)。
func TestStartPrefetch转码流仍然直连(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	defer closeSharedProxy()
	target := &emby.PlaybackTarget{URL: "http://example.invalid/hls.m3u8", PlayMethod: "Transcode"}
	if got := startPrefetch(context.Background(), nil, target, config.DefaultPrefs()); got != target.URL {
		t.Fatalf("转码流该直连,实得 %s", got)
	}
}
