package download

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"linplayer/core/config"
	"linplayer/core/paths"
)

func Test旧下载迁移后使用重登凭据且不保存令牌(t *testing.T) {
	paths.SetRoot(t.TempDir())
	cfg, err := config.Load()
	if err != nil {
		t.Fatal(err)
	}
	var authenticated atomic.Bool
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Emby-Token") != "dummy-renewed" || r.URL.Query().Get("api_key") != "" {
			w.WriteHeader(401)
			return
		}
		authenticated.Store(true)
		w.Header().Set("Content-Length", "4")
		_, _ = w.Write([]byte("data"))
	}))
	defer up.Close()
	cfg.Upsert(config.Account{Server: up.URL, Token: "dummy-original", UserID: "user"})
	dir := t.TempDir()
	poster := up.URL + "/Items/movie/Images/Primary?api_key=dummy-original"
	b, _ := json.Marshal(indexFile{Threads: 1, Items: []*Item{{ID: "old", ItemID: "movie", Title: "影片", Container: "mkv", Status: StatusPaused,
		URL: up.URL + "/Items/movie/Download?api_key=dummy-original", PosterURL: &poster, FilePath: filepath.Join(dir, "movie.mkv")}}})
	if err := os.WriteFile(filepath.Join(dir, "index.json"), b, 0644); err != nil {
		t.Fatal(err)
	}
	m, err := New(dir, http.DefaultClient)
	if err != nil {
		t.Fatal(err)
	}
	defer m.Close()
	b, _ = os.ReadFile(filepath.Join(dir, "index.json"))
	if strings.Contains(string(b), "dummy-original") {
		t.Fatal("旧索引仍保留令牌")
	}
	if runtime.GOOS != "windows" {
		st, err := os.Stat(filepath.Join(dir, "index.json"))
		if err != nil || st.Mode().Perm() != 0600 {
			t.Fatal("索引权限未收紧")
		}
	}
	cfg.Upsert(config.Account{Server: up.URL, Token: "dummy-renewed", UserID: "user"})
	cfg.Upsert(config.Account{Server: up.URL + "/other", Token: "dummy-unrelated", UserID: "other"})
	m.Resume("old")
	it := waitDone(t, m, "old", time.Second)
	if it.Status != StatusCompleted || !authenticated.Load() {
		t.Fatalf("重登后未用新凭据恢复: %+v", it)
	}
	if it.PosterURL == nil || !strings.Contains(*it.PosterURL, "dummy-renewed") {
		t.Fatal("封面未使用当前凭据")
	}
	m.persist()
	b, _ = os.ReadFile(filepath.Join(dir, "index.json"))
	if strings.Contains(string(b), "dummy-renewed") {
		t.Fatal("新令牌写入索引")
	}
}

func Test下载拒绝换用户且外部重定向不带账号令牌(t *testing.T) {
	paths.SetRoot(t.TempDir())
	cfg, err := config.Load()
	if err != nil {
		t.Fatal(err)
	}
	var leaked atomic.Bool
	storage := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		leaked.Store(r.Header.Get("X-Emby-Token") != "")
		_, _ = w.Write([]byte("data"))
	}))
	defer storage.Close()
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, storage.URL, 302) }))
	defer up.Close()
	cfg.Upsert(config.Account{Server: up.URL, Token: "test", UserID: "original"})
	it := &Item{ServerID: up.URL, UserID: "original", ItemID: "movie"}
	req, err := downloadRequest(context.Background(), it)
	if err != nil {
		t.Fatal(err)
	}
	resp, err := downloadClient(http.DefaultClient).Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if leaked.Load() {
		t.Fatal("账号令牌泄漏到外部存储")
	}
	cfg.Upsert(config.Account{Server: up.URL, Token: "another", UserID: "different"})
	if _, err := downloadRequest(context.Background(), it); err == nil {
		t.Fatal("沿用同服另一用户的身份")
	}
	legacy := &Item{URL: up.URL + "/Items/movie/Download?api_key=unknown", ItemID: "movie", Status: StatusQueued}
	migrateAuth(legacy)
	if legacy.ServerID != "" || legacy.URL != "" || legacy.Status != StatusFailed {
		t.Fatal("旧下载误绑定当前账号或仍保留旧凭据")
	}
}
