package emby

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"linplayer/core/bus"
)

func extensionHandlers() map[string]func(context.Context, *Session, map[string]any) (any, error) {
	handlers := map[string]func(context.Context, *Session, map[string]any) (any, error){}
	registerExtensionCommands(func(name string, fn func(context.Context, *Session, map[string]any) (any, error)) {
		handlers[name] = fn
	})
	return handlers
}

const testCapabilities = `{"version":"1","rankings":true,"calendar":{"enabled":true},"library_matching":{"sources":["tmdb","douban","bangumi"]}}`

func TestExtensionContractAndExactMatch(t *testing.T) {
	matchCount := 1
	requests := []string{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Emby-Token") != "test-token" {
			t.Error("missing authentication")
		}
		requests = append(requests, r.URL.RequestURI())
		w.Header().Set("Content-Type", "application/json")
		switch strings.TrimPrefix(r.URL.Path, "/proxy/emby-ext/v1/") {
		case "capabilities":
			_, _ = w.Write([]byte(testCapabilities))
		case "rankings":
			_, _ = w.Write([]byte(`{"items":[{"id":"hot","name":"热门","source":"tmdb","enabled":true},{"id":"disabled","enabled":false}]}`))
		case "rankings/hot/items":
			if r.URL.Query().Get("page") != "2" {
				t.Error("page lost")
			}
			_, _ = w.Write([]byte(`{"page_size":20,"items":[{"source":"tmdb","media_type":"movie","tmdb_id":123,"title":"作品","poster_url":"/emby-ext/v1/images/tmdb/movie/123/poster"}]}`))
		case "calendar":
			_, _ = w.Write([]byte(`{"items":[{"source":"bangumi","media_type":"anime","bangumi_id":456,"title":"周历","weekday":7}]}`))
		case "library/matches":
			if r.Method != "POST" {
				t.Error("not POST")
			}
			var body struct {
				Items []map[string]any `json:"items"`
			}
			if json.NewDecoder(r.Body).Decode(&body) != nil || len(body.Items) != 1 || body.Items[0]["source"] != "tmdb" || body.Items[0]["external_id"] != "123" || body.Items[0]["media_type"] != "movie" || body.Items[0]["title"] != nil {
				t.Error("identity changed or title matching used")
			}
			matches := []any{}
			for i := 0; i < matchCount; i++ {
				matches = append(matches, map[string]any{"item_id": "library-id", "item_type": "Movie"})
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"items": []any{map[string]any{"key": "item", "matches": matches}}})
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
			w.WriteHeader(404)
		}
	}))
	defer srv.Close()
	old := defaultClient
	defaultClient = NewClient("test")
	defer func() { defaultClient = old }()
	h := extensionHandlers()
	session := &Session{Server: srv.URL + "/proxy/emby/", Token: "test-token", UserID: "user"}
	cats, err := h["emby.extRankings"](context.Background(), session, nil)
	if err != nil || len(cats.([]map[string]any)) != 1 {
		t.Fatalf("categories: %v %v", cats, err)
	}
	rows, err := h["emby.extRankingItems"](context.Background(), session, map[string]any{"category_id": "hot", "page": float64(2)})
	if err != nil {
		t.Fatal(err)
	}
	row := rows.([]map[string]any)[0]
	if row["rank"] != 21 || row["external_id"] != "123" || row["image_url"] != srv.URL+"/proxy/emby-ext/v1/images/tmdb/movie/123/poster" {
		t.Fatalf("normalization: %v", row)
	}
	calendar, err := h["emby.extCalendar"](context.Background(), session, nil)
	if err != nil || calendar.([]map[string]any)[0]["weekday"] != 7 || calendar.([]map[string]any)[0]["broadcast_at"] != nil {
		t.Fatalf("calendar: %v %v", calendar, err)
	}
	for _, tc := range []struct {
		n    int
		code string
	}{{1, ""}, {0, bus.ENotFound}, {2, bus.EInvalid}} {
		matchCount = tc.n
		got, err := h["emby.extResolve"](context.Background(), session, row)
		if tc.code == "" {
			b, _ := json.Marshal(got)
			if err != nil || !strings.Contains(string(b), `"item_id":"library-id"`) {
				t.Fatalf("resolve: %s %v", b, err)
			}
		} else {
			assertExtensionCode(t, err, tc.code)
		}
	}
	if len(requests) != 12 {
		t.Fatalf("expected fresh capability and data for each command, got %d", len(requests))
	}
}

func assertExtensionCode(t *testing.T, err error, code string) {
	t.Helper()
	var e *bus.Err
	if !errors.As(err, &e) || e.Code != code {
		t.Fatalf("want %s, got %v", code, err)
	}
}
func TestExtensionErrorsAndRedirect(t *testing.T) {
	for _, tc := range []struct {
		status     int
		body, code string
	}{{404, "", bus.EUnsupported}, {401, "", bus.EAuth}, {403, "", bus.EPermission}, {502, "", bus.EInternal}, {200, "<html>login</html>", bus.EUnsupported}, {200, `{"version":"2"}`, bus.EUnsupported}, {200, `{"version":"1","rankings":false}`, bus.EUnsupported}} {
		t.Run(tc.code+tc.body, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tc.status)
				_, _ = w.Write([]byte(tc.body))
			}))
			defer srv.Close()
			_, err := NewClient("test").extensionCapability(context.Background(), &Session{Server: srv.URL}, "rankings")
			assertExtensionCode(t, err, tc.code)
		})
	}
	called := false
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { called = true }))
	defer target.Close()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, target.URL, 302) }))
	defer srv.Close()
	_, err := NewClient("test").extensionCapability(context.Background(), &Session{Server: srv.URL, Token: "test-token"}, "")
	if err == nil || called {
		t.Fatal("redirect followed")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err = NewClient("test").extensionCapability(ctx, &Session{Server: srv.URL}, "")
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("cancel: %v", err)
	}
}
