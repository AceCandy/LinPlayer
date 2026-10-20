package emby

import (
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

func TestExtensionCacheOnlyExplicitRefresh(t *testing.T) {
	freshConfig(t)
	var calls atomic.Int32
	var fail atomic.Bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if fail.Load() {
			w.WriteHeader(500)
			return
		}
		_, _ = w.Write([]byte(`{"version":"1","liuying":{"enabled":true},"rankings":true}`))
	}))
	defer srv.Close()
	c := NewClient("test")
	s := &Session{Server: srv.URL, UserID: "one", Token: "token"}
	if _, err := c.extensionCapability(t.Context(), s, ""); err == nil || calls.Load() != 0 {
		t.Fatal("cache miss probed")
	}
	if _, err := c.refreshExtensionCapabilities(t.Context(), s); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 20; i++ {
		if _, err := c.extensionCapability(t.Context(), s, "liuying"); err != nil {
			t.Fatal(err)
		}
	}
	if calls.Load() != 1 {
		t.Fatal("repeated capability requests", calls.Load())
	}
	if _, err := NewClient("restart").extensionCapability(t.Context(), s, "liuying"); err != nil {
		t.Fatal("persistent cache", err)
	}
	other := *s
	other.UserID = "two"
	if _, err := c.extensionCapability(t.Context(), &other, ""); err == nil {
		t.Fatal("user cache leaked")
	}
	other = *s
	other.Server = srv.URL + "/other"
	if _, err := c.extensionCapability(t.Context(), &other, ""); err == nil {
		t.Fatal("server cache leaked")
	}
	fail.Store(true)
	if _, err := c.refreshExtensionCapabilities(t.Context(), s); err == nil {
		t.Fatal("refresh failure hidden")
	}
	if _, err := c.extensionCapability(t.Context(), s, "liuying"); err != nil {
		t.Fatal("valid cache lost", err)
	}
	if calls.Load() != 2 {
		t.Fatal("unexpected retry", calls.Load())
	}
}

func TestExtensionCacheLateOldTokenCannotReplaceNewCapabilities(t *testing.T) {
	freshConfig(t)
	started, release := make(chan struct{}), make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Emby-Token") == "old" {
			close(started)
			<-release
			_, _ = w.Write([]byte(`{"version":"1","liuying":{"enabled":true}}`))
			return
		}
		_, _ = w.Write([]byte(`{"version":"1","liuying":{"enabled":false}}`))
	}))
	defer srv.Close()
	defer close(release)
	c := NewClient("test")
	old := &Session{Server: srv.URL, UserID: "user", Token: "old"}
	done := make(chan error, 1)
	go func() { _, err := c.refreshExtensionCapabilities(t.Context(), old); done <- err }()
	<-started
	current := *old
	current.Token = "new"
	if _, err := c.refreshExtensionCapabilities(t.Context(), &current); err != nil {
		t.Fatal(err)
	}
	// 先放行旧响应，再读取缓存，确保断言包含迟到响应的写入机会。
	release <- struct{}{}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if _, err := c.extensionCapability(t.Context(), &current, "liuying"); err == nil {
		t.Fatal("old token restored revoked capability")
	}
}
