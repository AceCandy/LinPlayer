package emby

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"linplayer/core/bus"
	"linplayer/core/displaycache"
)

func TestAdultFilterCommandHTTPAndLegacyResponse(t *testing.T) {
	freshConfig(t)
	var expected string
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		q := r.URL.Query()
		if q.Get("IsAdult") != expected || q.Has("IsAdult") != (expected != "") {
			t.Errorf("filter lost: %v want %q", q, expected)
		}
		item := map[string]any{"Id": "example", "Name": "Example", "Type": "Movie", "LibraryIds": []string{"library"}, "UserData": map[string]any{}}
		if strings.HasSuffix(r.URL.Path, "/Views") {
			item["Type"] = "CollectionFolder"
			if expected != "" {
				item["IsAdult"] = expected == "true"
			}
		}
		switch {
		case strings.HasSuffix(r.URL.Path, "/Latest"):
			json.NewEncoder(w).Encode([]any{item})
		case strings.HasSuffix(r.URL.Path, "/PlaybackInfo"):
			json.NewEncoder(w).Encode(map[string]any{"MediaSources": []any{map[string]any{"Id": "version", "LibraryIds": []string{"library"}}}})
		case strings.HasSuffix(r.URL.Path, "/Items/example"):
			json.NewEncoder(w).Encode(item)
		default:
			json.NewEncoder(w).Encode(map[string]any{"Items": []any{item}, "TotalRecordCount": 1})
		}
	}))
	defer server.Close()
	for _, value := range []any{nil, false, true, false, nil} {
		expected = ""
		if value == false {
			expected = "false"
		}
		if value == true {
			expected = "true"
		}
		for _, name := range []string{"views", "listItems", "listItemsPage", "listLatest", "listResume", "listNextUp", "listFavorites", "search", "itemDetail", "itemMedia", "seriesSeasons"} {
			args := map[string]any{"server": server.URL, "user_id": "viewer", "query": "Example", "parent_id": "library", "item_id": "example", "series_id": "example", "with_children": false, "limit": float64(1)}
			if value != nil {
				args["is_adult"] = value
			}
			out, err := bus.Invoke(t.Context(), "emby."+name, args)
			if err != nil {
				t.Fatalf("%s: %v", name, err)
			}
			if name == "views" {
				items := out.([]Item)
				if len(items) != 1 {
					t.Fatalf("views=%v", items)
				}
				if value == nil && items[0].IsAdult != nil {
					t.Fatal("legacy absent became false")
				}
				if value != nil && (items[0].IsAdult == nil || *items[0].IsAdult != value) {
					t.Fatal("adult flag lost")
				}
				data, _ := json.Marshal(items[0])
				if value == false && !strings.Contains(string(data), `"is_adult":false`) {
					t.Fatal("false omitted")
				}
			}
			if name == "seriesSeasons" && len(out.([]SeasonInfo)[0].LibraryIDs) != 1 {
				t.Fatal("season membership lost")
			}
			if name == "itemDetail" && len(out.(*ItemDetail).LibraryIDs) != 1 {
				t.Fatal("detail membership lost")
			}
		}
	}
	before := requests
	for _, bad := range []any{"false", 0, nil, []any{true}} {
		_, err := bus.Invoke(t.Context(), "emby.listResume", map[string]any{"server": server.URL, "user_id": "viewer", "is_adult": bad})
		if err == nil {
			t.Fatalf("invalid accepted: %#v", bad)
		}
	}
	if requests != before {
		t.Fatal("invalid filter reached server")
	}
	// 三态快照不能串用，普通区显式 false 必须保留独立键。
	for _, value := range []any{nil, false, true} {
		args := map[string]any{"server": server.URL, "user_id": "viewer", "query": "Example", "parent_id": "library", "item_id": "example", "series_id": "example", "with_children": false, "limit": float64(1)}
		if value != nil {
			args["is_adult"] = value
		}
		if displaycache.Snapshot("emby.views", args) == nil {
			t.Fatalf("missing scoped snapshot: %v", value)
		}
	}
}

func TestPrivateRequestsRejectLegacyServerIgnoringFilter(t *testing.T) {
	freshConfig(t)
	contentRequests := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/Views") {
			json.NewEncoder(w).Encode(map[string]any{"Items": []any{map[string]any{"Id": "legacy", "Type": "CollectionFolder"}}})
		} else {
			contentRequests++
			json.NewEncoder(w).Encode(map[string]any{"Items": []any{}})
		}
	}))
	defer server.Close()
	for _, name := range []string{"listResume", "search", "itemMedia"} {
		_, err := bus.Invoke(t.Context(), "emby."+name, map[string]any{"server": server.URL, "user_id": "viewer", "query": "Example", "item_id": "film", "is_adult": true})
		if err == nil {
			t.Fatalf("%s 接受了无分类旧服", name)
		}
	}
	if contentRequests != 0 {
		t.Fatal("旧服仍被用于私密内容查询")
	}
}

func TestExtensionAdultSourceDoesNotLeakIntoNormalCatalog(t *testing.T) {
	for _, value := range []bool{false, true} {
		ctx, err := AdultFilterContext(t.Context(), map[string]any{"is_adult": value})
		if err != nil {
			t.Fatal(err)
		}
		if extensionSourceMatches(ctx, "huangguoai") != value || extensionSourceMatches(ctx, "javranking") != value || extensionSourceMatches(ctx, "hongguo") == value {
			t.Fatal("已明确分类的资料来源跨空间展示")
		}
	}
}
