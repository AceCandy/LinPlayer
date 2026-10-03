package player

import (
	"context"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/history"
	"linplayer/core/paths"
)

func Test历史补建保存最新进度且停播取消旧请求(t *testing.T) {
	for _, stop := range []bool{false, true} {
		t.Run(map[bool]string{false: "恢复", true: "停播"}[stop], func(t *testing.T) {
			paths.SetRoot(t.TempDir())
			if _, err := config.Load(); err != nil {
				t.Fatal(err)
			}
			old := history.Shared()
			store := history.New(filepath.Join(t.TempDir(), "history.json"))
			history.SetShared(store)
			t.Cleanup(func() {
				currentMu.Lock()
				cancelHistoryRetryLocked()
				current = nil
				currentCtx = nil
				currentMu.Unlock()
				history.SetShared(old)
			})
			entered, release := make(chan struct{}), make(chan struct{})
			var attempts atomic.Int32
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				if attempts.Add(1) == 1 {
					w.WriteHeader(503)
					return
				}
				close(entered)
				<-release
				_, _ = w.Write([]byte(`{"Id":"movie","Name":"影片","Type":"Movie","ProviderIds":{"Tmdb":"123"},"RunTimeTicks":1000000000}`))
			}))
			defer up.Close()
			s := &emby.Session{Server: up.URL, UserID: "user"}
			if h := buildHistoryContext(context.Background(), s, "movie"); h != nil {
				t.Fatal("首次失败未得到空上下文")
			}
			target := &emby.PlaybackTarget{ItemID: "movie"}
			currentMu.Lock()
			current = target
			currentCtx = nil
			currentMu.Unlock()
			ctx, cancel := context.WithCancel(context.Background())
			r := &historyRetry{target: target, session: *s, ctx: ctx, cancel: cancel}
			currentMu.Lock()
			pendingHistory = r
			currentMu.Unlock()
			done := make(chan struct{})
			go func() { retryHistory(r); close(done) }()
			select {
			case <-entered:
			case <-time.After(time.Second):
				close(release)
				t.Fatal("未启动补建")
			}
			captureHistory(37.5, false)
			if stop {
				if err := Stop(context.Background(), nil, 37.5); err != nil {
					close(release)
					t.Fatal(err)
				}
				currentMu.Lock()
				current = &emby.PlaybackTarget{ItemID: "next"}
				currentMu.Unlock()
			}
			close(release)
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("补建任务未收尾")
			}
			records := store.LoadAll()
			if stop {
				currentMu.Lock()
				stale := currentCtx != nil
				currentMu.Unlock()
				if stale || len(records) != 0 {
					t.Fatal("旧请求在停播/换片后提交了历史")
				}
			} else if len(records) != 1 || records[0].LastPositionTicks != 375000000 {
				t.Fatalf("补建未保存最新进度: %+v", records)
			}
		})
	}
}
