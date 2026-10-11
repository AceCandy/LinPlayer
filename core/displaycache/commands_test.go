package displaycache_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"linplayer/core/bus"
	"linplayer/core/emby"
	"linplayer/core/paths"
)

// 真实命令注册、HTTP解析、持久化和事件出口保持同一条链。
func TestEmbyRegisteredCommandSnapshotBeforeHTTPResult(t *testing.T) {
	paths.SetRoot(t.TempDir())
	var calls atomic.Int32
	entered, release := make(chan struct{}), make(chan struct{})
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		name := "旧搜索资料"
		if calls.Add(1) > 1 {
			close(entered)
			<-release
			name = "新搜索资料"
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"Items": []any{map[string]any{"Id": "movie", "Name": name, "Type": "Movie"}}, "TotalRecordCount": 1})
	}))
	defer upstream.Close()
	emby.RegisterCommands("test")
	bus.Init()
	args := map[string]any{"server": upstream.URL, "user_id": "fixture-user", "token": "fixture", "query": "关键词", "limit": 10}
	if _, err := bus.Invoke(context.Background(), "emby.search", args); err != nil {
		t.Fatal(err)
	}
	raw, _ := json.Marshal(args)
	if err := bus.Call(817, "emby.search", string(raw)); err != nil {
		t.Fatal(err)
	}
	defer close(release)
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("实时请求未发出")
	}
	for {
		var event struct {
			T    string
			Seq  int64
			Data []map[string]any
		}
		b := bus.NextEvent(3000)
		if len(b) == 0 {
			t.Fatal("未在HTTP返回前收到快照")
		}
		_ = json.Unmarshal(b, &event)
		if event.Seq != 817 {
			continue
		}
		if event.T != "partial" || len(event.Data) != 1 || event.Data[0]["name"] != "旧搜索资料" {
			t.Fatal("首个命令事件应是旧展示快照")
		}
		break
	}
	release <- struct{}{}
	for {
		var event struct {
			T    string
			Seq  int64
			OK   bool
			Data []map[string]any
		}
		b := bus.NextEvent(3000)
		if len(b) == 0 {
			t.Fatal("缺少最终实时结果")
		}
		_ = json.Unmarshal(b, &event)
		if event.Seq != 817 {
			continue
		}
		if event.T != "result" || !event.OK || len(event.Data) != 1 || event.Data[0]["name"] != "新搜索资料" {
			t.Fatal("最终结果不能使用缓存")
		}
		break
	}
	if calls.Load() != 2 {
		t.Fatal("缓存命中必须仍请求服务器")
	}
}
