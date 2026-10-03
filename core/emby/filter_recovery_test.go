package emby

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func Test筛选区分不支持与暂时失败(t *testing.T) {
	for _, status := range []int{404, 503, 401} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(status) }))
			defer up.Close()
			f, err := NewClient("test").FiltersOf(context.Background(), &Session{Server: up.URL}, "lib")
			if status == 401 {
				if err == nil {
					t.Fatal("鉴权失败被当成空筛选")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			b, _ := json.Marshal(f)
			if (status == 503) != strings.Contains(string(b), "unavailable") {
				t.Fatalf("失败与不支持没有区分: %s", b)
			}
			if status == 404 && !strings.Contains(string(b), "unsupported") {
				t.Fatalf("缺失接口被伪装成正常空数据: %s", b)
			}
		})
	}
}

func Test筛选部分失败保留成功且年份认证失败优先(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/Genres" {
			_, _ = w.Write([]byte(`{"Items":[{"Name":"动作"}]}`))
			return
		}
		if r.URL.Query().Get("SortOrder") == "Ascending" {
			w.WriteHeader(401)
			return
		}
		w.WriteHeader(404)
	}))
	defer up.Close()
	f, err := NewClient("test").FiltersOf(context.Background(), &Session{Server: up.URL}, "lib")
	if StatusOf(err) != 401 || len(f.Genres) != 1 {
		t.Fatalf("成功分面丢失或鉴权错误被404掩盖: %+v %v", f, err)
	}
}
