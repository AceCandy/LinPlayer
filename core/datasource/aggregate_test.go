package datasource

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

func TestAggregateSearchReturnsUpTo50PerServer(t *testing.T) {
	oldClient := embyClient
	t.Cleanup(func() { embyClient = oldClient })
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
			embyClient = emby.NewClient("test")
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
