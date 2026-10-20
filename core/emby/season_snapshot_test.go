package emby

import (
	"context"
	"encoding/json"
	"linplayer/core/bus"
	"linplayer/core/displaycache"
	"linplayer/core/paths"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAllSeasonSnapshotKeepsCompleteResultOnPageFailure(t *testing.T) {
	paths.SetRoot(t.TempDir())
	fail := false
	requests := []string{}
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := r.URL.Query().Get("StartIndex")
		requests = append(requests, start)
		if start == "1" && fail {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		id := "first"
		if start == "1" {
			id = "last"
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"Items": []any{map[string]any{"Id": id, "Name": id, "Type": "Episode"}}, "TotalRecordCount": 2})
	}))
	defer upstream.Close()
	args := map[string]any{"server": upstream.URL, "user_id": "u", "token": "fixture", "parent_id": "season", "all": true}
	value, err := bus.Invoke(context.Background(), "emby.seasonEpisodes", args)
	if err != nil {
		t.Fatal(err)
	}
	if len(value.(*Page).Items) != 2 || len(requests) != 2 || requests[1] != "1" {
		t.Fatal("全季分页未完成", requests)
	}
	before, _ := json.Marshal(displaycache.Snapshot("emby.seasonEpisodes", args))
	fail = true
	if _, err = bus.Invoke(context.Background(), "emby.seasonEpisodes", args); err == nil {
		t.Fatal("后页错误被吞掉")
	}
	after, _ := json.Marshal(displaycache.Snapshot("emby.seasonEpisodes", args))
	if string(before) != string(after) {
		t.Fatal("后页失败覆盖完整快照")
	}
	delete(args, "all")
	requests = nil
	if _, err = bus.Invoke(context.Background(), "emby.seasonEpisodes", args); err != nil {
		t.Fatal(err)
	}
	if len(requests) != 1 {
		t.Fatal("默认分页请求变成全季")
	}
}

func TestAllSeasonRejectsRepeatedPage(t *testing.T) {
	paths.SetRoot(t.TempDir())
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"Items": []any{map[string]any{"Id": "same", "Name": "same", "Type": "Episode"}}, "TotalRecordCount": 2})
	}))
	defer upstream.Close()
	_, err := bus.Invoke(context.Background(), "emby.seasonEpisodes", map[string]any{"server": upstream.URL, "user_id": "u", "token": "fixture", "parent_id": "season", "all": true})
	if err == nil {
		t.Fatal("重复分页被当成完整季")
	}
}
