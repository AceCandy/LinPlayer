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

func Test收藏库归属与旧接口兼容(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/Views") {
			w.Write([]byte(`{"Items":[{"Id":"short-library","Name":"短剧","Type":"CollectionFolder","CollectionType":"tvshows","LibraryType":"hongguo"}]}`))
			return
		}
		w.Write([]byte(`{"Items":[{"Id":"new","Type":"Series","LibraryIds":["tv-library","short-library"]},{"Id":"old","Type":"Series"},{"Id":"null","Type":"Series","LibraryIds":null},{"Id":"empty","Type":"Series","LibraryIds":[]}],"TotalRecordCount":4}`))
	}))
	defer up.Close()
	c, s := NewClient("test"), &Session{Server: up.URL, UserID: "viewer"}
	page, err := c.FavoritesPage(context.Background(), s, 0, 60, "更新时间", "", "")
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 4 || page.NextIndex != 4 || page.HasMore {
		t.Fatalf("pagination changed: %+v", page)
	}
	if !reflect.DeepEqual(page.Items[0].LibraryIDs, []string{"tv-library", "short-library"}) {
		t.Fatalf("membership lost: %+v", page.Items[0])
	}
	for _, item := range page.Items[1:] {
		if len(item.LibraryIDs) != 0 || item.Type != "Series" {
			t.Fatalf("legacy fallback: %+v", item)
		}
		data, _ := json.Marshal(item)
		if strings.Contains(string(data), "library_ids") || strings.Contains(string(data), "library_type") {
			t.Fatalf("legacy JSON shape changed: %s", data)
		}
	}
	views, err := c.Views(context.Background(), s)
	if err != nil || len(views) != 1 || views[0].LibraryType == nil || *views[0].LibraryType != "hongguo" {
		t.Fatalf("views type lost: %+v %v", views, err)
	}
	data, _ := json.Marshal(page.Items[0])
	if !strings.Contains(string(data), `"library_ids":["tv-library","short-library"]`) {
		t.Fatalf("host field missing: %s", data)
	}
}
