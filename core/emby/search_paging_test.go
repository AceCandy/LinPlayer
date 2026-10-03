package emby

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"linplayer/core/bus"
)

func Test合集与搜索补取后页(t *testing.T) {
	for _, search := range []bool{false, true} {
		t.Run(map[bool]string{false: "超过200个合集", true: "类型筛选后补足60条"}[search], func(t *testing.T) {
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				start, _ := strconv.Atoi(r.URL.Query().Get("StartIndex"))
				limit, _ := strconv.Atoi(r.URL.Query().Get("Limit"))
				items := []map[string]any{}
				for i := start; i < min(start+limit, 261); i++ {
					kind := "BoxSet"
					if search {
						kind = "Episode"
						if i >= 200 {
							kind = "Movie"
						}
					}
					items = append(items, map[string]any{"Id": strconv.Itoa(i), "Type": kind})
				}
				_ = json.NewEncoder(w).Encode(map[string]any{"Items": items, "TotalRecordCount": 261})
			}))
			defer up.Close()
			c, s := NewClient("test"), &Session{Server: up.URL, UserID: "u"}
			var items []Item
			var err error
			if search {
				items, err = c.Search(context.Background(), s, "词", []string{"Movie"}, 60, "")
			} else {
				items, err = c.Collections(context.Background(), s)
			}
			want := 261
			if search {
				want = 60
			}
			if err != nil || len(items) != want {
				t.Fatalf("后页未完整获取: got=%d want=%d err=%v", len(items), want, err)
			}
			if search && items[0].ID != "200" {
				t.Fatal("未按类型筛选")
			}
		})
	}
}

func Test搜索拒绝重复原始页(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"Items":[{"Id":"episode","Type":"Episode"}],"TotalRecordCount":0}`))
	}))
	defer up.Close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, err := NewClient("test").Search(ctx, &Session{Server: up.URL}, "词", []string{"Movie"}, 60, "")
	if e, ok := err.(*bus.Err); !ok || e.Code != bus.EUpstream {
		t.Fatalf("分页不推进应明确报协议错误,实得 %v", err)
	}
}
