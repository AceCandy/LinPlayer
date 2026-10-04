package emby

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
)

func Test媒体库浏览跟随服务器直属内容(t *testing.T) {
	for _, types := range [][]string{{"Movie", "Movie"}, {"Series", "Series"}, {"Movie", "Series"}} {
		t.Run(types[0]+"_"+types[1], func(t *testing.T) {
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				q := r.URL.Query()
				if r.URL.Path != "/Users/u/Items" || q.Get("ParentId") != "lib /&" {
					t.Errorf("媒体库范围错误: %s", r.URL.Path)
				}
				if q.Get("SortBy") != "DateLastContentAdded" || q.Get("SortOrder") != "Descending" || q.Get("Limit") != "1" {
					t.Error("分页或排序参数丢失")
				}
				items := []map[string]any{}
				// 混合类型被误派发成剧集查询会漏电影；递归查询会展开分集。
				if !q.Has("IncludeItemTypes") && q.Get("Recursive") != "true" {
					start, _ := strconv.Atoi(q.Get("StartIndex"))
					if start < len(types) {
						items = append(items, map[string]any{"Id": strconv.Itoa(start), "Type": types[start]})
					}
				}
				_ = json.NewEncoder(w).Encode(map[string]any{"Items": items, "TotalRecordCount": 2})
			}))
			defer up.Close()
			c := NewClient("test")
			limit, sortBy, sortOrder := 1, "DateLastContentAdded", "Descending"
			for start := range types {
				page, err := c.Items(context.Background(), &Session{Server: up.URL, UserID: "u"}, "lib /&", &ItemQuery{
					Limit: &limit, StartIndex: &start, SortBy: &sortBy, SortOrder: &sortOrder,
				})
				if err != nil {
					t.Fatal(err)
				}
				if page.Total != 2 || len(page.Items) != 1 || page.Items[0].ID != strconv.Itoa(start) || page.Items[0].Type != types[start] {
					t.Fatalf("媒体库直属内容或分页不符: %+v", page)
				}
			}
		})
	}
}
