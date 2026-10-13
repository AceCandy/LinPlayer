package emby

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRawDanmakuCapabilityAndPaths(t *testing.T) {
	for _, prefix := range []string{"", "/proxy"} {
		for _, caps := range []string{`{"version":"1"}`, `{"version":"1","danmaku":{"enabled":true,"format":"unknown"}}`, `{"version":"1","danmaku":{"enabled":true,"format":"bilibili_xml"}}`} {
			calls := 0
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("X-Emby-Token") != "test-token" {
					t.Error("missing token")
				}
				if r.URL.Path == prefix+"/emby-ext/v1/capabilities" {
					_, _ = w.Write([]byte(caps))
					return
				}
				if r.URL.Path != prefix+"/api/danmu/item/raw" {
					t.Errorf("unexpected path %s", r.URL.Path)
				}
				calls++
				_, _ = w.Write([]byte("<i/>"))
			}))
			client := NewClient("test")
			s := &Session{Server: srv.URL + prefix + "/emby", Token: "test-token", UserID: "u"}
			for i := 0; i < 2; i++ {
				if _, err := client.RawDanmaku(context.Background(), s, "item"); err != nil {
					t.Fatal(err)
				}
			}
			want := 0
			if caps == `{"version":"1","danmaku":{"enabled":true,"format":"bilibili_xml"}}` {
				want = 2
			}
			if calls != want {
				t.Fatalf("calls=%d want=%d", calls, want)
			}
			srv.Close()
		}
	}
}

func TestRawDanmakuDoesNotRedirect(t *testing.T) {
	leaked := false
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { leaked = true }))
	defer target.Close()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/emby-ext/v1/capabilities" {
			_, _ = w.Write([]byte(`{"version":"1","danmaku":{"enabled":true,"format":"bilibili_xml"}}`))
			return
		}
		http.Redirect(w, r, target.URL, 302)
	}))
	defer srv.Close()
	_, err := NewClient("test").RawDanmaku(context.Background(), &Session{Server: srv.URL, Token: "test-token"}, "item")
	if err == nil || leaked {
		t.Fatalf("err=%v leaked=%v", err, leaked)
	}
}
