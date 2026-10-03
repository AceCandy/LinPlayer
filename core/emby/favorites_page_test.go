package emby

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"linplayer/core/blocklist"
)

func Test收藏单页短页不提前结束且屏蔽不改变游标(t *testing.T) {
	old := blocklist.List()
	defer blocklist.Replace(old)
	blocklist.Replace([]blocklist.Entry{{ID: "blocked"}})
	calls := 0
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		q := r.URL.Query()
		if q.Get("Filters") != "IsFavorite" || q.Get("SortBy") != "DateLastContentAdded" || q.Get("SortOrder") != "Descending" || q.Get("Limit") != "60" || q.Get("StartIndex") != "1999" {
			t.Errorf("分页排序契约错误: %v", q)
		}
		json.NewEncoder(w).Encode(map[string]any{"Items": []map[string]any{{"Id": "blocked"}, {"Id": "visible"}}, "TotalRecordCount": 3000})
	}))
	defer up.Close()
	p, err := NewClient("test").FavoritesPage(context.Background(), &Session{Server: up.URL, UserID: "u"}, 1999, 60, "更新时间", "", "")
	if err != nil {
		t.Fatal(err)
	}
	if calls != 1 || len(p.Items) != 1 || p.NextIndex != 2001 || !p.HasMore || p.Total != 3000 {
		t.Fatalf("短页/屏蔽/超过旧上限丢失: %+v calls=%d", p, calls)
	}
}

func Test收藏首屏受控对照(t *testing.T) {
	var calls atomic.Int32
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		time.Sleep(40 * time.Millisecond)
		start, _ := strconv.Atoi(r.URL.Query().Get("StartIndex"))
		limit, _ := strconv.Atoi(r.URL.Query().Get("Limit"))
		items := []map[string]any{}
		for i := start; i < min(start+limit, 3000); i++ {
			items = append(items, map[string]any{"Id": "measure-" + strconv.Itoa(i), "Type": "Movie"})
		}
		json.NewEncoder(w).Encode(map[string]any{"Items": items, "TotalRecordCount": 3000})
	}))
	defer up.Close()
	c, s := NewClient("test"), &Session{Server: up.URL, UserID: "u"}
	before := time.Now()
	if _, err := c.Favorites(context.Background(), s); err != nil {
		t.Fatal(err)
	}
	oldTime, oldCalls := time.Since(before), calls.Swap(0)
	after := time.Now()
	p, err := c.FavoritesPage(context.Background(), s, 0, 60, "更新时间", "", "")
	if err != nil {
		t.Fatal(err)
	}
	newTime, newCalls := time.Since(after), calls.Load()
	if oldCalls != 10 || newCalls != 1 || len(p.Items) != 60 || !p.HasMore {
		t.Fatalf("请求预算或后续分页丢失: old=%d new=%d page=%+v", oldCalls, newCalls, p)
	}
	t.Logf("受控同进程/同上游/3000条/每请求40ms:旧首屏 %v/%d次,新首屏 %v/%d次;后续仍可分页", oldTime, oldCalls, newTime, newCalls)
}
