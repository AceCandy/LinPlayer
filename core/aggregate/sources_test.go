package aggregate

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/paths"
)

func TestSwitchCandidatesExcludesCurrentServerOnAlternateLine(t *testing.T) {
	oldClient := client
	t.Cleanup(func() { client = oldClient })
	paths.SetRoot(t.TempDir())
	c, err := config.Load()
	if err != nil {
		t.Fatal(err)
	}
	server := func(id string) *httptest.Server {
		up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/System/Info/Public" {
				json.NewEncoder(w).Encode(map[string]string{"Id": id})
				return
			}
			json.NewEncoder(w).Encode(map[string]any{"Items": []map[string]any{{"Id": id, "Name": "同一部", "Type": "Movie"}}, "TotalRecordCount": 1})
		}))
		t.Cleanup(up.Close)
		return up
	}
	current, other := server("current"), server("other")
	c.AccountList = []config.Account{
		{Server: "https://current.invalid", UserID: "user", Lines: []config.ServerLine{{URL: current.URL}}},
		{Server: other.URL, UserID: "user"},
	}
	client = emby.NewClient("test")
	result, err := cmdSwitchCandidates(context.Background(), 0, map[string]any{"server_id": current.URL, "title": "同一部"})
	if err != nil {
		t.Fatal(err)
	}
	candidates := result.([]Candidate)
	if len(candidates) != 1 || candidates[0].ServerID != other.URL {
		t.Fatalf("备用线路所属的当前服务器不应成为换源候选: %+v", candidates)
	}
}

func TestAggregateSearchReturnsUpTo50PerServer(t *testing.T) {
	oldClient := client
	t.Cleanup(func() { client = oldClient })
	for _, count := range []int{3, 50, 65} {
		t.Run(fmt.Sprint(count), func(t *testing.T) {
			paths.SetRoot(t.TempDir())
			c, err := config.Load()
			if err != nil {
				t.Fatal(err)
			}
			items := make([]map[string]any, count)
			for i := range items {
				items[i] = map[string]any{"Id": fmt.Sprint(i), "Name": "故事", "Type": "Movie"}
			}
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/System/Info/Public" {
					json.NewEncoder(w).Encode(map[string]string{"Id": "test-emby"})
					return
				}
				if r.URL.Query().Get("Limit") != "50" {
					t.Errorf("搜索请求上限应为50: %s", r.URL.Query().Get("Limit"))
				}
				json.NewEncoder(w).Encode(map[string]any{"Items": items, "TotalRecordCount": count})
			}))
			t.Cleanup(up.Close)
			c.AccountList = []config.Account{{Server: up.URL, UserID: "user"}}
			client = emby.NewClient("test")
			result, err := cmdAggregateSearch(context.Background(), 0, map[string]any{"query": "故事"})
			if err != nil {
				t.Fatal(err)
			}
			rows := result.([]SearchRow)
			if len(rows) != 1 || rows[0].Error != nil || len(rows[0].EmbyItems) != min(count, 50) {
				t.Fatalf("聚合应返回每服最多50条,不足按实际数量: %+v", rows)
			}
			if rows[0].EmbyItems[len(rows[0].EmbyItems)-1].ID != fmt.Sprint(min(count, 50)-1) {
				t.Fatal("末条结果不正确")
			}
		})
	}
}
