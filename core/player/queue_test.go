package player

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"sync"
	"testing"
	"time"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/paths"
)

func TestQueuedPlaybackCommitsOnlyAfterEngineTransition(t *testing.T) {
	regTransportOnce.Do(registerTransport)
	for _, engine := range []string{"exo", "mpv", "exo-cancel", "mpv-cancel", "mpv-stop", "mpv-stop-late"} {
		t.Run(engine, func(t *testing.T) {
			stopAfterEvent := strings.HasSuffix(engine, "-late")
			engine = strings.TrimSuffix(engine, "-late")
			stopDuringTransition := strings.HasSuffix(engine, "-stop")
			engine = strings.TrimSuffix(engine, "-stop")
			cancelBeforeTransition := strings.HasSuffix(engine, "-cancel")
			engine := strings.TrimSuffix(engine, "-cancel")
			paths.SetRoot(t.TempDir())
			if _, err := config.Load(); err != nil {
				t.Fatal(err)
			}
			cancelPrepared("")
			var mu sync.Mutex
			var reports []string
			stoppedPositions := map[string]int64{}
			var commands [][]string
			oldCommand, oldPlaylist := mpvCommand, queuePlaylist
			mpvCommand = func(a ...string) error { commands = append(commands, append([]string(nil), a...)); return nil }
			var nextURL string
			queuePlaylist = func() string {
				return fmt.Sprintf(`[{"id":1,"filename":"old","current":true},{"id":2,"filename":%q}]`, nextURL)
			}
			defer func() { mpvCommand = oldCommand; queuePlaylist = oldPlaylist }()
			data := make([]byte, 8<<20)
			resolves := 0
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch {
				case strings.Contains(r.URL.Path, "PlaybackInfo"):
					id := "old"
					if strings.Contains(r.URL.Path, "/next/") {
						id = "next"
						mu.Lock()
						resolves++
						mu.Unlock()
					}
					fmt.Fprintf(w, `{"PlaySessionId":"session-%s","MediaSources":[{"Id":"ms-%s","Container":"mkv","RunTimeTicks":10000000000,"DirectStreamUrl":"/video.mkv"}]}`, id, id)
				case strings.Contains(r.URL.Path, "video.mkv"):
					w.Header().Set("ETag", `"v1"`)
					http.ServeContent(w, r, "video.mkv", time.Time{}, bytes.NewReader(data))
				case strings.Contains(r.URL.Path, "/Sessions/Playing"):
					var body struct {
						ItemID        string `json:"ItemId"`
						PositionTicks int64  `json:"PositionTicks"`
					}
					_ = json.NewDecoder(r.Body).Decode(&body)
					mu.Lock()
					reports = append(reports, body.ItemID+":"+r.URL.Path)
					if strings.HasSuffix(r.URL.Path, "/Stopped") {
						stoppedPositions[body.ItemID] = body.PositionTicks
					}
					mu.Unlock()
					w.WriteHeader(204)
				default:
					id := "old"
					if strings.HasSuffix(r.URL.Path, "/next") {
						id = "next"
					}
					fmt.Fprintf(w, `{"Id":%q,"Type":"Episode","RunTimeTicks":10000000000,"UserData":{"PlaybackPositionTicks":0}}`, id)
				}
			}))
			defer up.Close()
			s := &emby.Session{Server: up.URL, UserID: "u", Token: "test"}
			if _, err := PlayResolve(context.Background(), s, "old", 0, ""); err != nil {
				t.Fatal(err)
			}
			defer func() { _ = Stop(context.Background(), s, 2); cancelPrepared("") }()
			prepared, err := prepareNext(context.Background(), s, "next", "", "q")
			if err != nil {
				t.Fatal(err)
			}
			nextURL = prepared.(map[string]any)["play_url"].(string)
			oldProxy := currentProxy()
			result, err := queuePrepared(context.Background(), s, "q", "old", engine)
			if err != nil {
				t.Fatal(err)
			}
			if Current().ItemID != "old" || currentProxy() != oldProxy {
				t.Fatal("排队提前改变当前归属")
			}
			if result.(map[string]any)["play_url"] != nextURL {
				t.Fatal("排队丢失预载代理")
			}
			if cancelBeforeTransition {
				cancelPrepared("other-request")
				if queuedNext == nil {
					t.Fatal("无关请求取消了队列")
				}
				cancelPrepared("q")
				if queuedNext != nil || Current().ItemID != "old" || currentProxy() != oldProxy {
					t.Fatal("取消队列影响当前播放或未清除队列")
				}
				if _, err := advanceQueued(s, "q", 1000); err == nil {
					t.Fatal("取消后迟到接管仍成功")
				}
				return
			}
			if stopDuringTransition {
				if stopAfterEvent {
					onQueuedMpvStart(2)
				}
				queuePlaylist = func() string { return fmt.Sprintf(`[{"id":2,"filename":%q,"current":true}]`, nextURL) }
				_, err := bus.Invoke(context.Background(), "player.stopPlayback", map[string]any{
					"server": s.Server, "user_id": s.UserID, "token": s.Token, "item_id": "old", "pos": 999.0,
				})
				if err != nil {
					t.Fatal(err)
				}
				mu.Lock()
				defer mu.Unlock()
				if p, ok := stoppedPositions["next"]; !ok || p != 0 {
					t.Fatalf("旧集位置写入新集: %v", stoppedPositions)
				}
				return
			}
			if os.Getenv("LP_QUEUE_SPEED_TEST") == "1" {
				if code := ensureMpv(); code != 0 {
					t.Fatalf("mpv初始化失败: %d", code)
				}
				setProp("speed", "1.5")
			}
			wrong := *s
			wrong.Token = "changed"
			if _, err := advanceQueued(&wrong, "q", 1000); err == nil {
				t.Fatal("错误会话接管")
			}
			if engine == "mpv" {
				onQueuedMpvStart(99)
				if Current().ItemID != "old" {
					t.Fatal("错误队列项触发接管")
				}
				onQueuedMpvStart(2)
				if len(commands) != 1 || commands[0][0] != "loadfile" || commands[0][2] != "append" {
					t.Fatalf("出现停止或重新加载: %v", commands)
				}
			} else if _, err := advanceQueued(s, "q", 1000); err != nil {
				t.Fatal(err)
			}
			if os.Getenv("LP_QUEUE_SPEED_TEST") == "1" && propF("speed") != 1.5 {
				t.Fatalf("队列丢失倍速: %v", propF("speed"))
			}
			if Current().ItemID != "next" || currentProxy().URL != nextURL {
				t.Fatal("实际切换未接管")
			}
			cancelPrepared("q")
			if currentProxy() == nil {
				t.Fatal("迟到取消关闭已接管代理")
			}
			if _, err := advanceQueued(s, "q", 1000); err == nil {
				t.Fatal("重复接管")
			}
			if err := Stop(context.Background(), s, 2); err != nil {
				t.Fatal(err)
			}
			mu.Lock()
			defer mu.Unlock()
			if resolves != 1 {
				t.Fatal("接管重复请求播放信息", resolves)
			}
			oldStop, newStart := -1, -1
			for i, r := range reports {
				if r == "old:/Sessions/Playing/Stopped" {
					oldStop = i
				}
				if r == "next:/Sessions/Playing" {
					newStart = i
				}
			}
			if oldStop < 0 || newStart <= oldStop {
				t.Fatal("上报次序错误", reports)
			}
		})
	}
}

func TestQueuedMpvAppendDetectsLegacySyntax(t *testing.T) {
	old := mpvCommand
	defer func() { mpvCommand = old; resetLoadfileSyntax() }()
	resetLoadfileSyntax()
	calls := 0
	mpvCommand = func(a ...string) error {
		calls++
		if a[2] != "append" {
			t.Fatal(a)
		}
		if a[3] == "-1" {
			return fmt.Errorf("invalid parameter")
		}
		if !strings.Contains(a[3], "http-header-fields=") {
			t.Fatal(a)
		}
		return nil
	}
	if err := appendQueuedMpv("http://127.0.0.1/video", 0); err != nil {
		t.Fatal(err)
	}
	if !loadfileLegacy.Load() || calls != 2 {
		t.Fatal("未识别旧语法")
	}
}

// 隔离真实libmpv及其事件线程，避免污染其它无播放器用例。
func TestQueuedMpvPreservesSpeed(t *testing.T) {
	cmd := exec.Command(os.Args[0], "-test.run=^TestQueuedPlaybackCommitsOnlyAfterEngineTransition$/^mpv$", "-test.count=1")
	cmd.Env = append(os.Environ(), "LP_QUEUE_SPEED_TEST=1")
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("原生队列倍速回归: %v\n%s", err, out)
	}
}
