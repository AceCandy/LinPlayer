package emby

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"
)

func TestItemsRouteFallbackPreservesSeasonAndEpisodeQuery(t *testing.T) {
	for _, prefix := range []string{"", "/emby"} {
		t.Run(prefix, func(t *testing.T) {
			var calls []string
			var original map[string][]string
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls = append(calls, r.URL.Path)
				if r.Header.Get("X-Emby-Token") != "test-token" {
					t.Error("missing authentication")
				}
				q := r.URL.Query()
				if r.URL.Path == prefix+"/Users/u/Items" {
					original = q
					w.WriteHeader(404)
					return
				}
				if r.URL.Path != prefix+"/Items" {
					t.Errorf("unexpected route: %s", r.URL.Path)
				}
				if q.Get("UserId") != "u" {
					t.Error("missing UserId")
				}
				q.Del("UserId")
				if !reflect.DeepEqual(map[string][]string(q), original) {
					t.Errorf("query changed: %v != %v", q, original)
				}
				kind := q.Get("IncludeItemTypes")
				_ = json.NewEncoder(w).Encode(map[string]any{"Items": []any{map[string]any{"Id": "result", "Type": kind, "IndexNumber": 1}}, "TotalRecordCount": 403})
			}))
			defer up.Close()
			c := NewClient("test")
			s := &Session{Server: up.URL + prefix, UserID: "u", Token: "test-token"}
			seasons, err := c.Seasons(context.Background(), s, "series + &")
			if err != nil || len(seasons) != 1 {
				t.Fatalf("seasons=%v err=%v", seasons, err)
			}
			episodes, err := c.SeasonEpisodes(context.Background(), s, "season + &", 150, 150)
			if err != nil || len(episodes.Items) != 1 || episodes.Total != 403 {
				t.Fatalf("episodes=%v err=%v", episodes, err)
			}
			if len(calls) != 4 {
				t.Fatalf("calls=%v", calls)
			}
		})
	}
}

func TestItemsRouteFallbackOnlyForList404(t *testing.T) {
	for _, status := range []int{200, 401, 403, 404, 500} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			var calls []string
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls = append(calls, r.URL.Path)
				w.WriteHeader(status)
				if status == 200 {
					_, _ = w.Write([]byte(`{"Items":[]}`))
				}
			}))
			defer up.Close()
			c := NewClient("test")
			s := &Session{Server: up.URL, UserID: "u"}
			_, err := c.Seasons(context.Background(), s, "series")
			want := 1
			if status == 404 {
				want = 2
			}
			if len(calls) != want {
				t.Fatalf("calls=%v", calls)
			}
			if status != 200 && StatusOf(err) != status {
				t.Fatalf("lost status: %v", err)
			}
			calls = nil
			_, _ = c.fetchPage(context.Background(), s, up.URL+"/Users/u/Items/episode")
			if len(calls) != 1 {
				t.Fatalf("detail must not fall back: %v", calls)
			}
		})
	}
}

func TestRead404DoesNotBlameServerAddress(t *testing.T) {
	msg := (&StatusError{Status: 404, What: "取数据"}).Error()
	if strings.Contains(msg, "检查一下服务器地址") || !strings.Contains(msg, "404") {
		t.Fatal(msg)
	}
}

func TestItemsRouteFallbackReturnsAlternativeError(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/Users/u/Items" {
			w.WriteHeader(404)
		} else {
			w.WriteHeader(403)
		}
	}))
	defer up.Close()
	_, err := NewClient("test").Seasons(context.Background(), &Session{Server: up.URL, UserID: "u"}, "series")
	if StatusOf(err) != 403 {
		t.Fatalf("alternative error lost: %v", err)
	}
}

func TestItemsRouteCancellationDoesNotTryAlternative(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	calls := 0
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		cancel()
		w.WriteHeader(404)
	}))
	defer up.Close()
	_, err := NewClient("test").Seasons(ctx, &Session{Server: up.URL, UserID: "u"}, "series")
	if err == nil || calls != 1 {
		t.Fatalf("calls=%d err=%v", calls, err)
	}
}
