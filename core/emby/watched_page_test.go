package emby

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestWatchedPageCurrentUserTypeAndNewEpisode(t *testing.T) {
	unplayed := 0
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		if r.Method != "GET" || r.URL.Path != "/Users/current/Items" || q.Get("Filters") != "IsPlayed" || q.Get("Recursive") != "true" || q.Get("ParentId") != "" || q.Get("EnableTotalRecordCount") != "true" || q.Get("StartIndex") != "0" || q.Get("Limit") != "60" {
			t.Errorf("watched query: %s %v", r.URL.Path, q)
		}
		kind := q.Get("IncludeItemTypes")
		if kind != "Movie" && kind != "Series" {
			t.Errorf("invalid kind %q", kind)
		}
		data := map[string]any{"Played": true, "IsFavorite": false}
		if kind == "Series" {
			data["UnplayedItemCount"] = unplayed
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"Items": []map[string]any{
			{"Id": "watched", "Type": kind, "UserData": data},
			{"Id": "ly-work-hidden", "Type": kind, "UserData": data},
			{"Id": "not-watched", "Type": kind, "UserData": map[string]any{"Played": false}},
		}, "TotalRecordCount": 4})
	}))
	defer up.Close()
	c, s := NewClient("test"), &Session{Server: up.URL, UserID: "current"}
	for _, kind := range []string{"Movie", "Series"} {
		p, err := c.WatchedPage(t.Context(), s, kind, 0, 60, "", "", "")
		if err != nil {
			t.Fatal(err)
		}
		if len(p.Items) != 1 || p.Items[0].ID != "watched" || p.Items[0].IsFavorite == nil || *p.Items[0].IsFavorite || p.NextIndex != 3 || !p.HasMore {
			t.Fatalf("watched results/cursor: %+v", p)
		}
	}
	unplayed = 1
	p, err := c.WatchedPage(t.Context(), s, "Series", 0, 60, "", "", "")
	if err != nil || len(p.Items) != 0 || p.NextIndex != 3 || !p.HasMore {
		t.Fatalf("new episode not removed/cursor lost: %+v %v", p, err)
	}
	for _, kind := range []string{"", "Episode", "Movie,Series"} {
		if _, err := c.WatchedPage(t.Context(), s, kind, 0, 60, "", "", ""); err == nil {
			t.Fatalf("accepted %q", kind)
		}
	}
}
