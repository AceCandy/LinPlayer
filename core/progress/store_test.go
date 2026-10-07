package progress

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

	"linplayer/core/config"
	"linplayer/core/emby"
)

func Test主服失败重试清零冲突与旧会话(t *testing.T) {
	var mu sync.Mutex
	state := emby.ProgressSnapshot{MediaSourceID: "file", PositionTicks: 40000000, RunTimeTicks: 1200000000, Revision: "1"}
	fail, lostResponse, posts := false, false, 0
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		if r.Method == "POST" {
			posts++
			var body struct {
				emby.ProgressSnapshot
				ExpectedRevision string
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Error(err)
			}
			if fail {
				w.WriteHeader(503)
				return
			}
			if body.ExpectedRevision != state.Revision {
				w.WriteHeader(409)
				return
			}
			rev, _ := strconv.Atoi(state.Revision)
			state = body.ProgressSnapshot
			state.Revision = strconv.Itoa(rev + 1)
			if lostResponse {
				lostResponse = false
				w.WriteHeader(503)
				return
			}
		}
		json.NewEncoder(w).Encode(state)
	}))
	defer up.Close()
	client := emby.NewClient("test")
	session := emby.Session{Server: up.URL, UserID: "user"}
	binding := config.ProgressServer{Server: up.URL, UserID: "user"}
	store := NewStore(filepath.Join(t.TempDir(), "progress.json"))
	link := &Link{store: store, client: client, session: session, binding: binding, itemID: "item", baseline: state, epoch: config.ProgressAccountEpoch()}
	k := key(binding.Server, binding.UserID, "item")
	store.owners[k] = link
	if err := link.Sync(t.Context(), 0, false); err != nil {
		t.Fatal(err)
	}
	if state.PositionTicks != 0 || store.Status().Pending != 0 {
		t.Fatalf("zero not committed: %+v %+v", state, store.Status())
	}
	mu.Lock()
	fail = true
	mu.Unlock()
	if err := link.Sync(t.Context(), 2, false); err == nil {
		t.Fatal("failed request reported success")
	}
	if store.Status().Pending != 1 {
		t.Fatal("failed sync was lost")
	}
	mu.Lock()
	fail = false
	mu.Unlock()
	// 重启后的存储重新解析账号，不依赖旧内存实例。
	restarted := NewStore(store.path)
	if err := restarted.Retry(t.Context(), client, binding, session, config.ProgressAccountEpoch()); err != nil {
		t.Fatal(err)
	}
	if restarted.Status().Pending != 0 || state.PositionTicks != 20000000 {
		t.Fatal("restart retry failed")
	}
	link.baseline = state
	mu.Lock()
	state.Revision = "20"
	state.PositionTicks = 90000000
	previousPosts := posts
	mu.Unlock()
	if err := link.Sync(t.Context(), 0, false); err == nil {
		t.Fatal("conflict reported success")
	}
	if state.PositionTicks != 90000000 || posts != previousPosts || store.Status().Conflicts != 1 {
		t.Fatal("conflict overwrote primary")
	}
	if err := store.Retry(t.Context(), client, binding, session, config.ProgressAccountEpoch()); err == nil || state.PositionTicks != 90000000 {
		t.Fatal("retry overwrote conflict")
	}
	// 不同主服/用户的重试不能发送旧队列。
	if err := store.Retry(t.Context(), client, config.ProgressServer{Server: "other", UserID: "other"}, session, config.ProgressAccountEpoch()); err != nil || posts != previousPosts {
		t.Fatal("old queue migrated")
	}
	newLink := &Link{store: store, client: client, session: session, binding: binding, itemID: "other-item", baseline: state, epoch: config.ProgressAccountEpoch()}
	store.owners[key(binding.Server, binding.UserID, "other-item")] = newLink
	oldLink := *newLink
	if err := oldLink.Sync(context.Background(), 3, false); err != nil || posts != previousPosts {
		t.Fatal("old playback wrote after replacement")
	}
	// 丢失提交响应后，同状态回读可以确认成功，不再重复写入。
	mu.Lock()
	lostResponse = true
	mu.Unlock()
	if err := newLink.Sync(t.Context(), 4, false); err == nil {
		t.Fatal("lost response reported success")
	}
	mu.Lock()
	previousPosts = posts
	mu.Unlock()
	if err := store.Retry(t.Context(), client, binding, session, config.ProgressAccountEpoch()); err == nil {
		t.Fatal("existing conflict must remain visible")
	}
	if store.Status().Pending != 1 || posts != previousPosts {
		t.Fatal("lost response was not reconciled")
	}
}

func Test主服待同步损坏文件不被覆盖(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pending.json")
	if err := os.WriteFile(path, []byte("broken"), 0600); err != nil {
		t.Fatal(err)
	}
	store := NewStore(path)
	if store.Status().Error == "" {
		t.Fatal("corruption hidden")
	}
	if err := store.Retry(t.Context(), emby.NewClient("test"), config.ProgressServer{}, emby.Session{}, config.ProgressAccountEpoch()); err == nil {
		t.Fatal("corruption accepted")
	}
	b, _ := os.ReadFile(path)
	if string(b) != "broken" {
		t.Fatal("corrupt file overwritten")
	}
}

func Test主服切换等待在途写入并拒绝迟到同步(t *testing.T) {
	for _, mode := range []string{"playback", "retry"} {
		t.Run(mode, func(t *testing.T) {
			started, finish := make(chan struct{}), make(chan struct{})
			state := emby.ProgressSnapshot{MediaSourceID: "file", RunTimeTicks: 1200000000, Revision: "1"}
			var posts int
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method == "POST" {
					posts++
					close(started)
					<-finish
					state.PositionTicks, state.Revision = 20000000, "2"
				}
				json.NewEncoder(w).Encode(state)
			}))
			defer up.Close()
			binding := config.ProgressServer{Server: up.URL, UserID: "user"}
			store := NewStore(filepath.Join(t.TempDir(), "pending.json"))
			link := &Link{store: store, client: emby.NewClient("test"), session: emby.Session{Server: up.URL, UserID: "user"}, binding: binding, itemID: "item", baseline: state, epoch: config.ProgressAccountEpoch()}
			store.owners[key(binding.Server, binding.UserID, "item")] = link
			done := make(chan error, 1)
			go func() {
				if mode == "retry" {
					state.PositionTicks = 20000000
					if err := store.save(map[string]Pending{key(binding.Server, binding.UserID, "item"): {Server: binding.Server, UserID: binding.UserID, ItemID: "item", State: state}}); err != nil {
						done <- err
						return
					}
					done <- store.Retry(t.Context(), link.client, binding, link.session, link.epoch)
				} else {
					done <- link.Sync(t.Context(), 2, false)
				}
			}()
			<-started
			invalidating, invalidated := make(chan struct{}), make(chan struct{})
			go func() {
				close(invalidating)
				config.InvalidateProgressAccount()
				close(invalidated)
			}()
			<-invalidating
			select {
			case <-invalidated:
				t.Error("account switch completed while its write remained in flight")
			case <-time.After(30 * time.Millisecond):
			}
			close(finish)
			if err := <-done; err != nil {
				t.Fatal(err)
			}
			<-invalidated
			if err := link.Sync(t.Context(), 3, false); err != nil || posts != 1 {
				t.Fatalf("late write after switch: posts=%d err=%v", posts, err)
			}
			if err := store.Retry(t.Context(), link.client, binding, link.session, link.epoch); err == nil || posts != 1 {
				t.Fatalf("late retry after switch: posts=%d err=%v", posts, err)
			}

		})
	}
}
