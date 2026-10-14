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

	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/paths"
)

func TestPreparedNextIsolatedAndHandoffReportsOrdered(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	cancelPrepared("")
	release := make(chan struct{})
	var releaseOnce sync.Once
	stopped := make(chan struct{})
	nextStarted := make(chan struct{})
	var nextResolves atomic.Int32
	data := make([]byte, 8<<20)
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.Contains(r.URL.Path, "PlaybackInfo"):
			id := "old"
			if strings.Contains(r.URL.Path, "/next/") {
				id = "next"
				nextResolves.Add(1)
			}
			fmt.Fprintf(w, `{"PlaySessionId":"session-%s","MediaSources":[{"Id":"ms-%s","Container":"mkv","DirectStreamUrl":"/Videos/%s/stream.mkv"}]}`, id, id, id)
		case strings.Contains(r.URL.Path, "stream.mkv"):
			w.Header().Set("ETag", `"v1"`)
			http.ServeContent(w, r, "video.mkv", time.Time{}, bytes.NewReader(data))
		case strings.Contains(r.URL.Path, "/Sessions/Playing"):
			var body struct {
				ItemID string `json:"ItemId"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			if strings.HasSuffix(r.URL.Path, "/Stopped") && body.ItemID == "old" {
				close(stopped)
				<-release
			} else if r.URL.Path == "/Sessions/Playing" && body.ItemID == "next" {
				close(nextStarted)
			}
			w.WriteHeader(204)
		default:
			fmt.Fprint(w, `{"Id":"item","Type":"Movie","RunTimeTicks":10000000000,"UserData":{"PlaybackPositionTicks":0}}`)
		}
	}))
	defer up.Close()
	defer releaseOnce.Do(func() { close(release) })
	defer closeSharedProxy()
	defer cancelPrepared("")
	s := &emby.Session{Server: up.URL, UserID: "u", Token: "test"}
	old, err := PlayResolve(context.Background(), s, "old", 0, "")
	if err != nil {
		t.Fatal(err)
	}
	active := currentProxy()
	result, err := prepareNext(context.Background(), s, "next", "", "request-next")
	if err != nil {
		t.Fatal(err)
	}
	if Current().ItemID != "old" || currentProxy() != active {
		t.Fatal("准备替换了当前播放")
	}
	response, err := http.Get(old.PlayURL)
	if err != nil {
		t.Fatal("当前代理被预热关闭", err)
	}
	response.Body.Close()
	select {
	case <-nextStarted:
		t.Fatal("预热不得上报正式开始")
	default:
	}
	if err := stopPlayback(context.Background(), s, 12, true); err != nil {
		t.Fatal(err)
	}
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("旧停止未发送")
	}
	done := make(chan error, 1)
	go func() {
		next, e := play(context.Background(), s, "next", 0, "", false, false, "request-next")
		if e == nil && next.PlayURL != result.(map[string]any)["play_url"] {
			e = fmt.Errorf("没有接管准备代理")
		}
		done <- e
	}()
	select {
	case e := <-done:
		if e != nil {
			t.Fatal(e)
		}
	case <-time.After(time.Second):
		t.Fatal("旧停止报告阻塞下一集准备完成后的起播")
	}
	if nextResolves.Load() != 1 {
		t.Fatal("正式起播重复请求PlaybackInfo")
	}
	select {
	case <-nextStarted:
		t.Fatal("新Playing越过旧Stopped")
	case <-time.After(100 * time.Millisecond):
	}
	releaseOnce.Do(func() { close(release) })
	select {
	case <-nextStarted:
	case <-time.After(time.Second):
		t.Fatal("停止完成后新开始未上报")
	}
	if err := Stop(context.Background(), s, 15); err != nil {
		t.Fatal(err)
	}
	if preparedNext != nil {
		t.Fatal("消费后仍保留准备槽")
	}
}

func TestPreparedIdentityAndSingleConsumption(t *testing.T) {
	prefs := config.DefaultPrefs()
	s := emby.Session{Server: "server", Token: "token", UserID: "user", DeviceID: "device"}
	for _, change := range []string{"token", "user", "server", "item", "source", "regex", "request"} {
		t.Run(change, func(t *testing.T) {
			cancelPrepared("")
			ctx, cancel := context.WithCancel(context.Background())
			target := &emby.PlaybackTarget{ItemID: "next", MediaSourceID: "ms"}
			p := &preparedPlayback{requestID: "r", session: s, itemID: "next", versionRegex: prefs.VersionRegex, target: target, cancel: cancel, expires: time.NewTimer(time.Minute)}
			preparedMu.Lock()
			preparedNext = p
			preparedMu.Unlock()
			defer cancelPrepared("")
			altered, id, source, request, options := s, "next", "", "r", prefs
			switch change {
			case "token":
				altered.Token = "renewed"
			case "user":
				altered.UserID = "other"
			case "server":
				altered.Server = "other"
			case "item":
				id = "other"
			case "source":
				source = "other"
			case "regex":
				options.VersionRegex = "other"
			case "request":
				request = "other"
			}
			if got, _ := takePrepared(&altered, id, source, request, options); got != nil {
				t.Fatal("错误身份/版本消费了准备结果")
			}
			cancelPrepared("stale-request")
			if ctx.Err() != nil {
				t.Fatal("旧取消关闭了新准备")
			}
			if got, _ := takePrepared(&s, "next", "", "r", prefs); got != target {
				t.Fatal("匹配结果未消费")
			}
			if got, _ := takePrepared(&s, "next", "", "r", prefs); got != nil {
				t.Fatal("旧会话可重复消费")
			}
		})
	}
}

func TestPreparedWarmWaitsAndPausesWithoutResolvingAgain(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	cancelPrepared("")
	var resolves, probes, reads atomic.Int32
	tailStarted, tailCanceled := make(chan struct{}), make(chan struct{})
	var once sync.Once
	data := make([]byte, 12<<20)
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "PlaybackInfo") {
			resolves.Add(1)
			fmt.Fprint(w, `{"MediaSources":[{"Id":"ms","Container":"mkv","DirectStreamUrl":"/stream.mkv"}]}`)
			return
		}
		if r.Header.Get("Range") == "bytes=0-0" {
			probes.Add(1)
		} else {
			reads.Add(1)
		}
		if strings.HasPrefix(r.Header.Get("Range"), "bytes=-") {
			once.Do(func() { close(tailStarted); <-r.Context().Done(); close(tailCanceled) })
			return
		}
		w.Header().Set("ETag", `"v1"`)
		http.ServeContent(w, r, "video.mkv", time.Time{}, bytes.NewReader(data))
	}))
	defer up.Close()
	defer cancelPrepared("")
	session := &emby.Session{Server: up.URL, UserID: "u", Token: "test"}
	if _, err := prepareNext(context.Background(), session, "next", "", "r"); err != nil {
		t.Fatal(err)
	}
	time.Sleep(100 * time.Millisecond)
	if reads.Load() != 0 || probes.Load() != 2 {
		t.Fatal("信息准备阶段提前下载视频", reads.Load(), probes.Load())
	}
	wrong := *session
	wrong.Token = "other"
	if warmPrepared(&wrong, "r", true) || warmPrepared(session, "old", true) {
		t.Fatal("失效身份启动预热")
	}
	if !warmPrepared(session, "r", true) {
		t.Fatal("未启动准备槽")
	}
	select {
	case <-tailStarted:
	case <-time.After(5 * time.Second):
		t.Fatal("未预热视频")
	}
	if !warmPrepared(session, "r", false) {
		t.Fatal("未暂停准备槽")
	}
	select {
	case <-tailCanceled:
	case <-time.After(time.Second):
		t.Fatal("暂停未取消后台读取")
	}
	if !warmPrepared(session, "r", true) {
		t.Fatal("未恢复准备槽")
	}
	target, proxy := takePrepared(session, "next", "", "r", config.Current().PrefsOf())
	if target == nil || proxy == nil {
		t.Fatal("暂停丢失准备结果")
	}
	proxy.Close()
	if resolves.Load() != 1 || probes.Load() != 2 {
		t.Fatal("恢复重复取信息或探测", resolves.Load(), probes.Load())
	}
	if warmPrepared(session, "r", true) {
		t.Fatal("消费后仍可启动预热")
	}
}
