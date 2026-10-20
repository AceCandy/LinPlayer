package emby

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestLiuYingExtensionLibraryOnlyContract(t *testing.T) {
	freshConfig(t)
	var caps, items atomic.Int32
	var invalid atomic.Bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Emby-Token") != "test-token" {
			t.Error("missing authentication")
		}
		switch r.URL.Path {
		case "/emby-ext/v1/capabilities":
			caps.Add(1)
			_, _ = w.Write([]byte(`{"version":"1","liuying":{"enabled":true,"scope":"visible_library"}}`))
		case "/emby-ext/v1/liuying/items":
			items.Add(1)
			for key, want := range map[string]string{"feed": "normal", "tab": "dance", "channel": "tg-3398168133", "exposure": "side_breast_exposure", "favorite": "true", "seed": "fixed", "offset": "12"} {
				if got := r.URL.Query().Get(key); got != want {
					t.Errorf("%s = %q, want %q", key, got, want)
				}
			}
			id := "ly-work-1"
			if invalid.Load() {
				id = "catalog-only"
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"items": []any{map[string]any{"item_id": id, "feed": "normal", "src": "must-not-escape", "fallback_src": "must-not-escape"}}, "page_size": 12, "offset": 12, "has_more": false})
		case "/emby-ext/v1/liuying/labels":
			_, _ = w.Write([]byte(`{"items":[{"name":"tg-3398168133","count":2},{"name":"side_breast_exposure","count":1}]}`))
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
			w.WriteHeader(404)
		}
	}))
	defer srv.Close()
	old := defaultClient
	defaultClient = NewClient("test")
	defer func() { defaultClient = old }()
	session := &Session{Server: srv.URL, UserID: "user", Token: "test-token"}
	if _, err := defaultClient.refreshExtensionCapabilities(t.Context(), session); err != nil {
		t.Fatal(err)
	}
	h := extensionHandlers()
	ctx, err := AdultFilterContext(t.Context(), map[string]any{"is_adult": true})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := h["emby.extCapabilities"](ctx, session, map[string]any{"refresh": true}); err == nil {
		t.Fatal("隐私空间允许刷新能力")
	}
	args := map[string]any{"feed": "normal", "tab": "dance", "channel": "tg-3398168133", "exposure": "side_breast_exposure", "favorite": true, "seed": "fixed", "offset": float64(12)}
	for i := 0; i < 2; i++ {
		out, err := h["emby.extLiuYingItems"](ctx, session, args)
		if err != nil {
			t.Fatal(err)
		}
		raw, err := json.Marshal(out)
		if err != nil || strings.Contains(string(raw), "must-not-escape") || strings.Contains(string(raw), "src") {
			t.Fatalf("source leaked: %s %v", raw, err)
		}
	}
	labels, err := h["emby.extLiuYingLabels"](ctx, session, nil)
	if err != nil {
		t.Fatal(err)
	}
	rows := labels.([]map[string]any)
	if len(rows) != 2 || rows[0]["kind"] != "channel" || rows[1]["kind"] != "exposure" {
		t.Fatalf("labels: %v", rows)
	}
	before := items.Load()
	for _, command := range []string{"emby.extLiuYingItems", "emby.extLiuYingLabels"} {
		if _, err := h[command](context.Background(), session, args); err == nil {
			t.Fatal("ordinary space accepted", command)
		}
	}
	if items.Load() != before {
		t.Fatal("ordinary space requested items")
	}
	invalid.Store(true)
	if _, err := h["emby.extLiuYingItems"](ctx, session, args); err == nil {
		t.Fatal("catalog identity accepted")
	}
	if caps.Load() != 1 {
		t.Fatalf("capability probed during browsing: %d", caps.Load())
	}
}

func TestLiuYingHomeFilteringKeepsOtherLibraries(t *testing.T) {
	libraryType := "liuying"
	movieType := "movies"
	in := []Item{{ID: "library", LibraryType: &libraryType}, {ID: "ly-work-1"}, {ID: "movie", LibraryType: &movieType}, {ID: "unknown"}}
	out := withoutLiuYing(in)
	if len(out) != 2 || out[0].ID != "movie" || out[1].ID != "unknown" {
		t.Fatalf("home filtering: %v", out)
	}
	if len(in) != 4 || in[0].ID != "library" {
		t.Fatal("library browse input mutated")
	}
}
