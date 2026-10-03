package emby

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
)

func Test兼容能力限制不发无效请求(t *testing.T) {
	var invalid atomic.Int32
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/System/Info/Public" {
			json.NewEncoder(w).Encode(map[string]string{"Id": "mediastation-go-001", "ServerName": "自定义名称"})
			return
		}
		invalid.Add(1)
		json.NewEncoder(w).Encode(map[string]any{"Items": []any{}, "TotalRecordCount": 0})
	}))
	defer up.Close()
	c, s := NewClient("test"), &Session{Server: up.URL, UserID: "user"}
	if err := c.HideResume(context.Background(), s, "item", true); err == nil {
		t.Error("隐藏续播被伪装成成功")
	}
	if err := c.RefreshItem(context.Background(), s, "item", false); err == nil {
		t.Error("刷新被伪装成成功")
	}
	if err := c.ScanAllLibraries(context.Background(), s); err == nil {
		t.Error("扫描被伪装成成功")
	}
	if _, err := c.Items(context.Background(), s, "lib", &ItemQuery{StudioIds: []string{"studio"}}); err == nil {
		t.Error("工作室筛选被伪装成成功")
	}
	if _, err := c.ByTmdb(context.Background(), s, "Movie", []string{"1"}); err == nil {
		t.Error("单页普通列表被当成精确查询")
	}
	if _, err := c.Collections(context.Background(), s); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Similar(context.Background(), s, "item", 12); err != nil {
		t.Fatal(err)
	}
	if len(c.Chapters(context.Background(), s, "item", 100)) != 0 {
		t.Fatal("不应提供章节")
	}
	if _, err := c.FiltersOf(context.Background(), s, "lib"); err != nil {
		t.Fatal(err)
	}
	if invalid.Load() != 0 {
		t.Fatalf("发出了 %d 次无效请求", invalid.Load())
	}
}

func Test兼容识别不猜名称且未知保留写操作(t *testing.T) {
	for _, id := range []string{"other-emby", "", "unavailable"} {
		t.Run(id, func(t *testing.T) {
			writes := 0
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/System/Info/Public" {
					if id == "unavailable" {
						w.WriteHeader(404)
						return
					}
					json.NewEncoder(w).Encode(map[string]string{"Id": id, "ServerName": "MediaStationGo"})
					return
				}
				if r.Method != http.MethodPost {
					t.Errorf("方法错误: %s", r.Method)
				}
				writes++
				w.WriteHeader(204)
			}))
			defer up.Close()
			c, s := NewClient("test"), &Session{Server: up.URL, UserID: "user"}
			if err := c.HideResume(context.Background(), s, "item", true); err != nil {
				t.Fatal(err)
			}
			if err := c.RefreshItem(context.Background(), s, "item", false); err != nil {
				t.Fatal(err)
			}
			if err := c.ScanAllLibraries(context.Background(), s); err != nil {
				t.Fatal(err)
			}
			if writes != 3 {
				t.Fatalf("旧写操作被改变: %d", writes)
			}
		})
	}
}

func Test并发筛选只探测一次且与普通服务器隔离(t *testing.T) {
	var probes atomic.Int32
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		probes.Add(1)
		json.NewEncoder(w).Encode(map[string]string{"Id": "mediastation-go-001"})
	}))
	defer up.Close()
	c, s := NewClient("test"), &Session{Server: up.URL}
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			f, err := c.FiltersOf(context.Background(), s, "lib")
			if err != nil || f.Capabilities == nil || f.Capabilities.Filters {
				t.Errorf("能力降级未生效: %+v %v", f, err)
			}
		}()
	}
	wg.Wait()
	if probes.Load() != 1 {
		t.Fatalf("重复探测: %d", probes.Load())
	}
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"Items": []any{map[string]string{"Name": "动作"}}})
	}))
	defer other.Close()
	f, err := c.FiltersOf(context.Background(), &Session{Server: other.URL}, "lib")
	if err != nil || len(f.Genres) != 1 || f.Capabilities != nil {
		t.Fatalf("另一服务器受到污染: %+v %v", f, err)
	}
}

func Test探测失败不能覆盖并发媒体响应确认的身份(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/System/Info/Public" {
			close(entered)
			<-release
			w.WriteHeader(404)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"Items": []any{}})
	}))
	defer up.Close()
	c, s := NewClient("test"), &Session{Server: up.URL}
	done := make(chan *Filters, 1)
	go func() { f, _ := c.FiltersOf(context.Background(), s, "library"); done <- f }()
	<-entered
	c.rememberIdentity(s.Server, "mediastation-go-001")
	close(release)
	f := <-done
	if f.Capabilities == nil || f.Capabilities.Filters {
		t.Fatal("晚到探测失败覆盖已核实身份")
	}
}
