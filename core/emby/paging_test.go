package emby

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"

	"linplayer/core/blocklist"
)

func Test分页游标使用屏蔽前数量(t *testing.T) {
	old := blocklist.List()
	t.Cleanup(func() { blocklist.Replace(old) })
	blocked := make([]blocklist.Entry, ServerPageCap)
	for i := range blocked {
		blocked[i].ID = strconv.Itoa(i)
	}
	blocklist.Replace(blocked)
	var offsets []int
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start, _ := strconv.Atoi(r.URL.Query().Get("StartIndex"))
		offsets = append(offsets, start)
		items := []map[string]any{}
		for i := start; i < min(start+150, 201); i++ {
			items = append(items, map[string]any{"Id": strconv.Itoa(i), "Type": "Episode"})
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"Items": items, "TotalRecordCount": 201})
	}))
	defer up.Close()
	c := NewClient("test")
	items, err := c.fetchAllPaged(context.Background(), &Session{Server: up.URL}, up.URL+"?Recursive=true", 3000)
	if err != nil || len(items) != 1 || items[0].ID != "200" {
		t.Fatalf("第一页全被屏蔽仍须取第二页: items=%v err=%v", items, err)
	}
	if fmt.Sprint(offsets) != "[0 150]" {
		t.Fatalf("游标不是服务端原始数量: %v", offsets)
	}
}

func Test本地筛选跨页计数并按筛后游标取页(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start, _ := strconv.Atoi(r.URL.Query().Get("StartIndex"))
		limit, _ := strconv.Atoi(r.URL.Query().Get("Limit"))
		items := []map[string]any{}
		for i := start; i < min(start+min(limit, 150), 403); i++ {
			rating := 9
			if i == 201 || i == 402 {
				rating = 5
			}
			items = append(items, map[string]any{"Id": strconv.Itoa(i), "Type": "Movie", "CommunityRating": rating})
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"Items": items, "TotalRecordCount": 403})
	}))
	defer up.Close()
	c := NewClient("test")
	start, limit, rating := 1, 1, 6.0
	p, err := c.Items(context.Background(), &Session{Server: up.URL, UserID: "u"}, "lib", &ItemQuery{
		StartIndex: &start, Limit: &limit, RatingMax: &rating,
	})
	if err != nil {
		t.Fatal(err)
	}
	if p.Total != 2 || len(p.Items) != 1 || p.Items[0].ID != "402" {
		t.Fatalf("筛后总数或第二页不完整: %+v", p)
	}
}
