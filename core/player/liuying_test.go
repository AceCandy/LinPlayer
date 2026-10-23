package player

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/net/prefetch"
	"linplayer/core/paths"
)

type liuyingFixture struct {
	s        *emby.Session
	ctx      context.Context
	resolves atomic.Int32
	bytes    atomic.Int64
	probes   atomic.Int32
	starts   atomic.Int32
	slow     chan struct{}
	release  chan struct{}
	slowOnce sync.Once
}

func newLiuyingFixture(t *testing.T) *liuyingFixture {
	t.Helper()
	regTransportOnce.Do(registerTransport)
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	f := &liuyingFixture{slow: make(chan struct{}), release: make(chan struct{})}
	f.ctx, _ = emby.AdultFilterContext(context.Background(), map[string]any{"is_adult": true})
	data := bytes.Repeat([]byte{7}, 8<<20)
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/Views"):
			fmt.Fprint(w, `{"Items":[{"Id":"private","Type":"CollectionFolder","IsAdult":true}]}`)
		case strings.HasSuffix(r.URL.Path, "/PlaybackInfo"):
			f.resolves.Add(1)
			parts := strings.Split(r.URL.Path, "/")
			id := parts[len(parts)-2]
			if id == "slow" {
				f.slowOnce.Do(func() { close(f.slow) })
				select {
				case <-f.release:
				case <-r.Context().Done():
					return
				}
			}
			live := id == "live"
			fmt.Fprintf(w, `{"PlaySessionId":"test","MediaSources":[{"Id":"version","LibraryIds":["private"],"Container":"mp4","RunTimeTicks":600000000,"IsInfiniteStream":%t,"DirectStreamUrl":"/Videos/%s/stream.mp4"}]}`, live, id)
		case strings.Contains(r.URL.Path, "stream.mp4"):
			w.Header().Set("ETag", `"v1"`)
			if r.Header.Get("Range") != "bytes=0-0" {
				f.bytes.Add(1)
			} else {
				f.probes.Add(1)
			}
			http.ServeContent(w, r, "video.mp4", time.Time{}, bytes.NewReader(data))
		case strings.HasPrefix(r.URL.Path, "/Sessions/Playing"):
			if r.URL.Path == "/Sessions/Playing" {
				f.starts.Add(1)
			}
			w.WriteHeader(204)
		default:
			fmt.Fprint(w, `{"Id":"item","Name":"测试","Type":"Movie","IsAdult":true,"RunTimeTicks":600000000,"UserData":{"PlaybackPositionTicks":90000000}}`)
		}
	}))
	f.s = &emby.Session{Server: up.URL, UserID: "viewer", Token: "test", DeviceID: "device"}
	t.Cleanup(func() {
		cancelLiuying(f.s, "owner", 1, "")
		_ = stopPlayback(context.Background(), f.s, 0, false)
		cancelPrepared("")
		up.Close()
	})
	if err := openLiuying(f.s, "owner"); err != nil {
		t.Fatal(err)
	}
	return f
}

func TestLiuyingWarmPromoteAndReentryReuseVerifiedBytes(t *testing.T) {
	f := newLiuyingFixture(t)
	value, err := prepareLiuying(f.ctx, f.s, "owner", 1, "warm", "same", "")
	if err != nil {
		t.Fatal(err)
	}
	prepared := value.(map[string]any)
	if !warmLiuying(f.s, "owner", 1, "warm", true) {
		t.Fatal("有效准备不能读取")
	}
	read := func(url string) {
		t.Helper()
		req, _ := http.NewRequest("GET", url, nil)
		req.Header.Set("Range", "bytes=0-4194303")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		data, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil || len(data) != 4<<20 || !bytes.Equal(data, bytes.Repeat([]byte{7}, 4<<20)) {
			t.Fatalf("代理字节不正确: %d %v", len(data), err)
		}
	}
	read(prepared["play_url"].(string))
	before := f.bytes.Load()
	value, err = playLiuying(f.ctx, f.s, "owner", 1, "warm", "same", "")
	if err != nil {
		t.Fatal(err)
	}
	result := liuyingResult(t, value)
	read(result["play_url"].(string))
	if f.resolves.Load() != 1 || f.bytes.Load() != before {
		t.Fatal("同代理提升重复解析或下载已完成块")
	}
	if err := stopPlaybackOwned(f.ctx, f.s, 0, false, "same",
		&liuyingBinding{owner: "owner", playback: result["playback_id"].(string)}); err != nil {
		t.Fatal(err)
	}
	cancelLiuying(f.s, "owner", 1, "")
	if err := openLiuying(f.s, "owner"); err == nil {
		t.Fatal("已关闭的相同owner被重新激活")
	}
	if err := openLiuying(f.s, "reentry"); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { cancelLiuying(f.s, "reentry", 1, "") })
	value, err = playLiuying(f.ctx, f.s, "reentry", 1, "", "same", "")
	if err != nil {
		t.Fatal(err)
	}
	read(liuyingResult(t, value)["play_url"].(string))
	if f.resolves.Load() != 2 || f.bytes.Load() != before {
		t.Fatal("重进未重新授权或没有复用有效磁盘块")
	}
}

func TestLiuyingSlowPlayCannotReplaceOrdinaryCommit(t *testing.T) {
	f := newLiuyingFixture(t)
	done := make(chan error, 1)
	go func() {
		_, err := playLiuying(f.ctx, f.s, "owner", 1, "", "slow", "")
		done <- err
	}()
	<-f.slow
	_, err := play(f.ctx, f.s, "ordinary", -1, "", false, false, "")
	if err != nil {
		close(f.release)
		t.Fatal(err)
	}
	active, proxy := Current(), currentProxy()
	close(f.release)
	if err := <-done; err == nil {
		t.Fatal("迟到的流影起播成功覆盖普通播放")
	}
	if Current() != active || currentProxy() != proxy || active.ItemID != "ordinary" {
		t.Fatal("普通正式目标或代理被误伤")
	}
}

func TestLiuyingCommandsPassPrivateScopeAndKeepIdentity(t *testing.T) {
	f := newLiuyingFixture(t)
	invoke := func(name string, extra map[string]any) (any, error) {
		a := map[string]any{
			"owner_id": "commands", "generation": float64(1), "device_id": f.s.DeviceID,
			"request_scope": map[string]any{"id": "private-scope", "private": true, "filter": true,
				"server": f.s.Server, "user_id": f.s.UserID, "token": f.s.Token},
		}
		for k, v := range extra {
			a[k] = v
		}
		return bus.Invoke(context.Background(), "player.liuying"+name, a)
	}
	if _, err := invoke("Open", nil); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { cancelLiuying(f.s, "commands", 1, "") })
	if _, err := invoke("Prepare", map[string]any{"request_id": "ready", "item_id": "same"}); err != nil {
		t.Fatal("私密策略未放行准备", err)
	}
	if _, err := invoke("Warm", map[string]any{"request_id": "ready", "enabled": false, "depth": float64(5)}); err != nil {
		t.Fatal(err)
	}
	value, err := invoke("Play", map[string]any{"request_id": "ready", "item_id": "same"})
	if err != nil {
		t.Fatal("私密策略未放行正式播放", err)
	}
	result := liuyingResult(t, value)
	if result["consumed_request_id"] != "ready" || result["resume_secs"] != float64(0) {
		t.Fatal("命令通道未接管同一准备或未从头起播")
	}
	position := map[string]any{"playback_id": result["playback_id"], "item_id": "same", "pos": float64(1), "paused": false}
	if _, err := invoke("Progress", position); err != nil {
		t.Fatal(err)
	}
	if _, err := invoke("Stop", position); err != nil {
		t.Fatal(err)
	}
	if _, err := invoke("Cancel", nil); err != nil {
		t.Fatal(err)
	}
	if _, err := invoke("Open", map[string]any{"owner_id": "cross", "server": "other"}); err == nil {
		t.Fatal("私密命令允许覆盖固定身份")
	}
}

func liuyingResult(t *testing.T, value any) map[string]any {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	var result map[string]any
	if err := json.Unmarshal(data, &result); err != nil {
		t.Fatal(err)
	}
	return result
}

func TestLiuyingRetainedStopReplaysSameProxyWithoutResolve(t *testing.T) {
	f := newLiuyingFixture(t)
	value, err := playLiuying(f.ctx, f.s, "owner", 1, "", "same", "")
	if err != nil {
		t.Fatal(err)
	}
	first := liuyingResult(t, value)
	read := func(url string) {
		t.Helper()
		resp, err := http.Get(url)
		if err != nil {
			t.Fatal(err)
		}
		n, err := io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
		if err != nil || n != 8<<20 {
			t.Fatal("保留媒体供给不完整", n, err)
		}
	}
	read(first["play_url"].(string))
	requests, probes := f.bytes.Load(), f.probes.Load()
	stop, err := bus.Invoke(f.ctx, "player.liuyingStop", map[string]any{
		"server": f.s.Server, "user_id": f.s.UserID, "token": f.s.Token, "device_id": f.s.DeviceID,
		"is_adult": true, "owner_id": "owner", "generation": float64(1),
		"playback_id": first["playback_id"], "item_id": "same", "pos": float64(4),
		"retain_request_id": "return",
	})
	if err != nil {
		t.Fatal(err)
	}
	if liuyingResult(t, stop)["retained"] != true {
		t.Fatal("停止未把有效媒体移回流影准备池")
	}
	value, err = playLiuying(f.ctx, f.s, "owner", 1, "return", "same", "")
	if err != nil {
		t.Fatal(err)
	}
	again := liuyingResult(t, value)
	read(again["play_url"].(string))
	if f.resolves.Load() != 1 || again["consumed_request_id"] != "return" ||
		again["play_url"] != first["play_url"] || again["resume_secs"] != float64(0) ||
		again["playback_id"] == first["playback_id"] || f.bytes.Load() != requests || f.probes.Load() != probes {
		t.Fatal("回滑未原位接管、重复解析或复用了正式播放身份")
	}
}

func TestLiuyingFivePreparedItemsAndOrdinarySlotIsolation(t *testing.T) {
	f := newLiuyingFixture(t)
	ordinary, err := prepareNext(f.ctx, f.s, "ordinary", "", "ordinary")
	if err != nil {
		t.Fatal(err)
	}
	var first map[string]any
	for _, id := range []string{"a", "b", "c", "d", "e"} {
		value, err := prepareLiuying(f.ctx, f.s, "owner", 1, id, id, "")
		if err != nil {
			t.Fatal(err)
		}
		if id == "a" {
			first = value.(map[string]any)
		}
	}
	if _, err := prepareLiuying(f.ctx, f.s, "owner", 1, "f", "f", ""); err == nil {
		t.Fatal("第六个投机准备未拒绝")
	}
	if f.starts.Load() != 0 || Current() != nil || f.bytes.Load() != 0 {
		t.Fatal("准备启动了正式播放或媒体读取")
	}
	value, err := playLiuying(f.ctx, f.s, "owner", 1, "a", "a", "")
	if err != nil {
		t.Fatal(err)
	}
	result := liuyingResult(t, value)
	if result["consumed_request_id"] != "a" || result["play_url"] != first["play_url"] || result["resume_secs"] != float64(0) {
		t.Fatal("未原位接管或没有从头开始")
	}
	if f.resolves.Load() != 6 {
		t.Fatal("准备命中后重复解析")
	}
	cancelLiuying(f.s, "owner", 1, "a")
	response, err := http.Get(result["play_url"].(string))
	if err != nil {
		t.Fatal("消费后取消关闭了正式代理", err)
	}
	_, _ = io.Copy(io.Discard, response.Body)
	response.Body.Close()
	target, proxy := takePrepared(f.s, "ordinary", "", "ordinary", config.Current().PrefsOf(), emby.AdultFilter(f.ctx))
	if target == nil || proxy == nil || proxy.URL != ordinary.(map[string]any)["play_url"] {
		t.Fatal("流影修改了通用准备单槽")
	}
	proxy.Close()
}

func TestLiuyingRetainedWindowRejectsInvalidationAndDoesNotRenewLease(t *testing.T) {
	for _, invalidation := range []string{"expired", "clear", "disabled", "version", "generation", "live"} {
		t.Run(invalidation, func(t *testing.T) {
			f := newLiuyingFixture(t)
			item := "same"
			if invalidation == "live" {
				item = "live"
			}
			value, err := playLiuying(f.ctx, f.s, "owner", 1, "", item, "")
			if err != nil {
				t.Fatal(err)
			}
			id := liuyingResult(t, value)["playback_id"].(string)
			currentMu.Lock()
			start := liuyingCurrent.start
			original := start.validUntil
			if invalidation == "expired" {
				start.validUntil = time.Now().Add(-time.Second)
			}
			currentMu.Unlock()
			switch invalidation {
			case "clear":
				if err := prefetch.ClearCache(paths.ClearCache); err != nil {
					t.Fatal(err)
				}
			case "disabled", "version":
				p := config.Current().PrefsOf()
				if invalidation == "disabled" {
					p.PreloadEnabled = false
				} else {
					p.VersionRegex = "different"
				}
				if err := config.Current().SetPrefs(p); err != nil {
					t.Fatal(err)
				}
			case "generation":
				if _, err := prepareLiuying(f.ctx, f.s, "owner", 2, "next", "next", ""); err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { cancelLiuying(f.s, "owner", 2, "") })
			}
			guard := &liuyingBinding{owner: "owner", playback: id, retainRequest: "return"}
			if err := stopPlaybackOwned(f.ctx, f.s, 1, false, item, guard); err != nil {
				t.Fatal(err)
			}
			if guard.retained {
				t.Fatal("失效或不可预载目标被保留")
			}
			if invalidation != "expired" && start.validUntil != original {
				t.Fatal("停止延长了授权准备期限")
			}
		})
	}
}

func TestLiuyingRetainHandoffHasOneBoundedExtraSlotAndOldExpiryCannotCloseReuse(t *testing.T) {
	f := newLiuyingFixture(t)
	value, err := playLiuying(f.ctx, f.s, "owner", 1, "", "current", "")
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"a", "b", "c", "d", "e"} {
		if _, err := prepareLiuying(f.ctx, f.s, "owner", 1, id, id, ""); err != nil {
			t.Fatal(err)
		}
	}
	guard := &liuyingBinding{owner: "owner", playback: liuyingResult(t, value)["playback_id"].(string), retainRequest: "return"}
	if err := stopPlaybackOwned(f.ctx, f.s, 1, false, "current", guard); err != nil || !guard.retained {
		t.Fatal("满窗口不能移交当前媒体", err)
	}
	liuyingMu.Lock()
	o := liuyingPool
	old := o.items["return"]
	count := len(o.items)
	liuyingMu.Unlock()
	if count != 6 {
		t.Fatal("移交窗口数量错误", count)
	}
	value, err = playLiuying(f.ctx, f.s, "owner", 1, "return", "current", "")
	if err != nil {
		t.Fatal(err)
	}
	guard = &liuyingBinding{owner: "owner", playback: liuyingResult(t, value)["playback_id"].(string), retainRequest: "return"}
	if err := stopPlaybackOwned(f.ctx, f.s, 1, false, "current", guard); err != nil || !guard.retained {
		t.Fatal(err)
	}
	expireLiuying(o, old)
	liuyingMu.Lock()
	fresh := o.items["return"]
	liuyingMu.Unlock()
	if fresh == nil || fresh == old || fresh.proxy != old.proxy || fresh.validUntil != old.validUntil {
		t.Fatal("旧计时器误删回收项或反复回滑延长有效期")
	}
}

func TestLiuyingLateStopCannotStopSameItemReplayOrOrdinary(t *testing.T) {
	f := newLiuyingFixture(t)
	value, err := playLiuying(f.ctx, f.s, "owner", 1, "", "same", "")
	if err != nil {
		t.Fatal(err)
	}
	old := liuyingResult(t, value)["playback_id"].(string)
	guard := &liuyingBinding{owner: "owner", playback: old}
	if err := stopPlaybackOwned(f.ctx, f.s, 5, false, "same", guard); err != nil {
		t.Fatal(err)
	}
	if _, err := playLiuying(f.ctx, f.s, "owner", 1, "", "same", ""); err != nil {
		t.Fatal(err)
	}
	active, proxy := Current(), currentProxy()
	if err := stopPlaybackOwned(f.ctx, f.s, 12, false, "same", guard); err != nil {
		t.Fatal(err)
	}
	currentMu.Lock()
	matched := liuyingMatchesLocked(f.s, "owner", old, "same")
	currentMu.Unlock()
	if matched || Current() != active || currentProxy() != proxy {
		t.Fatal("迟到stop/progress接受了同条目的新播放")
	}
	_ = stopPlayback(f.ctx, f.s, 0, false)
	if _, err := play(f.ctx, f.s, "same", -1, "", false, false, ""); err != nil {
		t.Fatal(err)
	}
	active, proxy = Current(), currentProxy()
	if err := stopPlaybackOwned(f.ctx, f.s, 20, false, "same", guard); err != nil {
		t.Fatal(err)
	}
	if Current() != active || currentProxy() != proxy {
		t.Fatal("迟到流影清理停止了通用播放")
	}
}

func TestLiuyingLiveHasNoContentWarmAndDisabledHasNoResolve(t *testing.T) {
	f := newLiuyingFixture(t)
	value, err := prepareLiuying(f.ctx, f.s, "owner", 1, "live", "live", "")
	if err != nil {
		t.Fatal(err)
	}
	if value.(map[string]any)["can_preload"] != false || warmLiuying(f.s, "owner", 1, "live", true) || f.bytes.Load() != 0 {
		t.Fatal("实时源被内容预载")
	}
	prefs := config.Current().PrefsOf()
	prefs.PreloadEnabled = false
	if err := config.Current().SetPrefs(prefs); err != nil {
		t.Fatal(err)
	}
	before := f.resolves.Load()
	value, err = prepareLiuying(f.ctx, f.s, "owner", 1, "off", "off", "")
	if err != nil || value.(map[string]any)["skipped"] != true || f.resolves.Load() != before {
		t.Fatal("预加载关闭仍取流", err)
	}
}

func TestLiuyingCancelledResolveCannotPublishInNextGeneration(t *testing.T) {
	f := newLiuyingFixture(t)
	started, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/Views") {
			fmt.Fprint(w, `{"Items":[{"Id":"private","IsAdult":true}]}`)
			return
		}
		once.Do(func() { close(started) })
		<-release
		fmt.Fprint(w, `{"MediaSources":[{"Id":"old","RunTimeTicks":100000000}]}`)
	}))
	defer up.Close()
	s := *f.s
	s.Server = up.URL
	if err := openLiuying(&s, "owner"); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { _, err := prepareLiuying(f.ctx, &s, "owner", 1, "old", "old", ""); done <- err }()
	<-started
	cancelled := make(chan struct{})
	go func() { cancelLiuying(&s, "owner", 1, ""); close(cancelled) }()
	select {
	case <-cancelled:
	case <-time.After(time.Second):
		close(release)
		t.Fatal("取消不能收尾在途解析")
	}
	close(release)
	if err := <-done; err == nil {
		t.Fatal("取消后仍发布")
	}
	liuyingControlMu.Lock()
	liuyingMu.Lock()
	_, err := liuyingOwnerLocked(&s, "owner", 2)
	liuyingMu.Unlock()
	liuyingControlMu.Unlock()
	if err == nil || warmLiuying(&s, "owner", 1, "old", true) {
		t.Fatal("已关闭owner被更高代次复活", err)
	}
}

func TestLiuyingClosedOwnerRejectsLaterGenerationWithoutResolving(t *testing.T) {
	f := newLiuyingFixture(t)
	if !cancelLiuying(f.s, "owner", 1, "") {
		t.Fatal("未关闭owner")
	}
	if _, err := prepareLiuying(f.ctx, f.s, "owner", 2, "late", "late", ""); err == nil {
		t.Fatal("离页后更高代准备复活owner")
	}
	if _, err := playLiuying(f.ctx, f.s, "owner", 3, "", "late", ""); err == nil {
		t.Fatal("离页后更高代起播复活owner")
	}
	if f.resolves.Load() != 0 || Current() != nil {
		t.Fatal("关闭owner后仍解析或正式起播")
	}
	if err := openLiuying(f.s, "owner"); err == nil {
		t.Fatal("同owner重新打开绕过关闭状态")
	}
	if err := openLiuying(f.s, "new-owner"); err != nil {
		t.Fatal("新页面不能创建新owner", err)
	}
	cancelLiuying(f.s, "new-owner", 1, "")
}
