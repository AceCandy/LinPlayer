package player

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
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
)

func TestDetailMediaSharesPlaybackInfoWithWarm(t *testing.T) {
	regWarmOnce.Do(registerWarmCommands)
	setup(t)
	var requests atomic.Int64
	streamed := make(chan struct{}, 1)
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/PlaybackInfo") {
			requests.Add(1)
			var body map[string]any
			if json.NewDecoder(r.Body).Decode(&body) != nil || body["DeviceProfile"] == nil {
				t.Error("详情预热必须携带播放能力")
			}
			fmt.Fprint(w, `{"PlaySessionId":"test-session","MediaSources":[{"Id":"source","Name":"4K","Container":"mkv"}]}`)
			return
		}
		select {
		case streamed <- struct{}{}:
		default:
		}
		http.ServeContent(w, r, "video.mkv", time.Time{}, bytes.NewReader(make([]byte, 1024)))
	}))
	defer up.Close()
	defer closeSharedProxy()
	defer cancelWarm()
	args := map[string]any{"server": up.URL, "token": "test", "user_id": "user", "item_id": "item", "return_media": true}
	r := call(t, 9901, "prefs.preloadItem", args)
	if !r.OK {
		t.Fatal(r.Msg)
	}
	var versions []emby.MediaVersion
	if err := json.Unmarshal(r.Data, &versions); err != nil {
		t.Fatalf("应返回版本数组: %v", err)
	}
	if len(versions) != 1 {
		t.Fatalf("版本数=%d", len(versions))
	}
	select {
	case <-streamed:
	case <-time.After(5 * time.Second):
		t.Fatal("没有后台预热")
	}
	if got := requests.Load(); got != 1 {
		t.Fatalf("展示与预热请求次数=%d", got)
	}
	cancelWarm()
	_, err := prefsClient.ResolveStream(context.Background(), &emby.Session{Server: up.URL, Token: "test", UserID: "user"}, "item", "", "")
	if err != nil {
		t.Fatal(err)
	}
	if got := requests.Load(); got != 2 {
		t.Fatalf("正式解析必须重新请求: %d", got)
	}
}

func TestDetailMediaDisabledAndFailure(t *testing.T) {
	regWarmOnce.Do(registerWarmCommands)
	setup(t)
	p := config.Current().PrefsOf()
	p.PreloadEnabled = false
	p.VersionRegex = "4K"
	if err := config.Current().SetPrefs(p); err != nil {
		t.Fatal(err)
	}
	var requests, streams atomic.Int64
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/PlaybackInfo") {
			streams.Add(1)
			return
		}
		requests.Add(1)
		if strings.Contains(r.URL.Path, "/bad/") {
			http.Error(w, "failed", 503)
			return
		}
		fmt.Fprint(w, `{"MediaSources":[{"Id":"a","Name":"1080p"},{"Id":"b","Name":"4K"}]}`)
	}))
	defer up.Close()
	args := map[string]any{"server": up.URL, "token": "test", "user_id": "user", "item_id": "item", "return_media": true}
	out, err := bus.Invoke(context.Background(), "prefs.preloadItem", args)
	if err != nil {
		t.Fatal(err)
	}
	versions := out.([]emby.MediaVersion)
	if len(versions) != 2 || versions[0].Preferred || !versions[1].Preferred {
		t.Fatal("未按播放正则标记首选版本")
	}
	args["item_id"] = "bad"
	if _, err := bus.Invoke(context.Background(), "prefs.preloadItem", args); err == nil {
		t.Fatal("请求失败不能返回成功")
	}
	delete(args, "return_media")
	if _, err := bus.Invoke(context.Background(), "prefs.preloadItem", args); err != nil {
		t.Fatal(err)
	}
	if requests.Load() != 2 || streams.Load() != 0 {
		t.Fatalf("关闭预热不应读视频或旧命令取流: %d/%d", requests.Load(), streams.Load())
	}
}

func TestDetailMediaCancellation(t *testing.T) {
	for _, action := range []string{"request", "leave", "play", "replace"} {
		t.Run(action, func(t *testing.T) {
			regWarmOnce.Do(registerWarmCommands)
			setup(t)
			entered, release := make(chan struct{}), make(chan struct{})
			var streams atomic.Int64
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if strings.HasSuffix(r.URL.Path, "/PlaybackInfo") {
					if strings.Contains(r.URL.Path, "/new/") {
						http.Error(w, "failed", 503)
						return
					}
					close(entered)
					<-release
					fmt.Fprint(w, `{"MediaSources":[{"Id":"source","Container":"mkv"}]}`)
					return
				}
				if strings.HasPrefix(r.URL.Path, "/Videos/old/") {
					streams.Add(1)
				}
			}))
			defer up.Close()
			defer close(release)
			defer cancelWarm()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan error, 1)
			go func() {
				_, err := bus.Invoke(ctx, "prefs.preloadItem", map[string]any{"server": up.URL, "token": "test", "user_id": "user", "item_id": "old", "return_media": true})
				done <- err
			}()
			select {
			case <-entered:
			case <-time.After(5 * time.Second):
				t.Fatal("请求未开始")
			}
			switch action {
			case "request":
				cancel()
			case "leave":
				_, _ = bus.Invoke(context.Background(), "prefs.preloadCancel", nil)
			case "play":
				_, _ = PlayResolve(context.Background(), &emby.Session{Server: up.URL, Token: "test", UserID: "user"}, "new", 0, "")
			case "replace":
				_, _ = bus.Invoke(context.Background(), "prefs.preloadItem", map[string]any{"server": up.URL, "token": "test", "user_id": "user", "item_id": "new", "return_media": true})
			}
			select {
			case err := <-done:
				if err == nil {
					t.Fatal("取消应使媒体请求失败")
				}
			case <-time.After(5 * time.Second):
				t.Fatal("取消未终止媒体请求")
			}
			if streams.Load() != 0 {
				t.Fatal("已取消任务仍读视频")
			}
		})
	}
}

func TestDetailMediaReturnsBeforeVideoProbeAndCancelsIt(t *testing.T) {
	regWarmOnce.Do(registerWarmCommands)
	setup(t)
	entered, canceled := make(chan struct{}), make(chan struct{})
	var once sync.Once
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/PlaybackInfo") {
			fmt.Fprint(w, `{"MediaSources":[{"Id":"source","DirectStreamUrl":"/Videos/item/stream.mkv"}]}`)
			return
		}
		once.Do(func() { close(entered) })
		<-r.Context().Done()
		close(canceled)
	}))
	defer up.Close()
	defer closeSharedProxy()
	defer cancelWarm()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	out, err := bus.Invoke(ctx, "prefs.preloadItem", map[string]any{"server": up.URL, "token": "test", "user_id": "user", "item_id": "item", "return_media": true})
	if err != nil {
		t.Fatal(err)
	}
	if len(out.([]emby.MediaVersion)) != 1 {
		t.Fatal("版本未返回")
	}
	select {
	case <-entered:
	case <-ctx.Done():
		t.Fatal("未开始后台探测")
	}
	cancelWarm()
	select {
	case <-canceled:
	case <-ctx.Done():
		t.Fatal("后台视频探测未取消")
	}
	warmMu.Lock()
	warmMu.Unlock()
	if currentProxy() != nil {
		t.Fatal("取消后不应发布代理")
	}
}
