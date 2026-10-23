package displaycache

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"linplayer/core/bus"
	"linplayer/core/paths"
)

func TestPersonPageSnapshotRetainsCursorAndSeparatesCategory(t *testing.T) {
	paths.SetRoot(t.TempDir())
	a := map[string]any{"server": "source", "user_id": "one", "person_id": "p", "type": "Movie", "start_index": 30}
	value := map[string]any{"items": []any{map[string]any{"id": "film", "name": "作品"}}, "total": 65, "next_index": 60, "has_more": true}
	if _, err := Run(context.Background(), 0, "emby.personItemsPage", a, func() (any, error) { return value, nil }); err != nil {
		t.Fatal(err)
	}
	snapshot := Snapshot("emby.personItemsPage", a)
	b, _ := json.Marshal(snapshot)
	var stored map[string]any
	if err := json.Unmarshal(b, &stored); err != nil || stored["next_index"] != float64(60) || stored["has_more"] != true || stored["total"] != float64(65) {
		t.Fatalf("page snapshot lost cursor: %s", b)
	}
	a["type"] = "Series"
	if Snapshot("emby.personItemsPage", a) != nil {
		t.Fatal("category snapshot leaked")
	}
	a["type"] = "Movie"
	a["start_index"] = 0
	if Snapshot("emby.personItemsPage", a) != nil {
		t.Fatal("page snapshot leaked")
	}
}

func TestFavoritesIgnoresLegacyMixedSnapshot(t *testing.T) {
	paths.SetRoot(t.TempDir())
	bus.Init()
	args := map[string]any{"server": "source", "user_id": "one", "start_index": 0, "limit": 60}
	b, _ := json.Marshal(args)
	legacyKey := identityPrefix(args) + hash("emby.listFavorites|" + string(b))[32:]
	put(legacyKey, []byte(`{"items":[{"id":"ly-work-1"}],"next_index":1,"has_more":false}`), epoch())
	if Snapshot("emby.listFavorites", args) != nil {
		t.Fatal("普通收藏仍读取混有流影的旧快照")
	}
	fresh := map[string]any{"items": []any{map[string]any{"id": "movie"}}, "next_index": 2, "has_more": false}
	_, err := Run(t.Context(), 31, "emby.listFavorites", args, func() (any, error) {
		if event := bus.NextEvent(0); len(event) != 0 {
			t.Fatalf("旧收藏快照不应作为partial发出: %s", event)
		}
		return fresh, nil
	})
	if err != nil || Snapshot("emby.listFavorites", args) == nil {
		t.Fatalf("新收藏快照未保存: %v", err)
	}
}

func TestSnapshotPartialAndRealtimeResult(t *testing.T) {
	paths.SetRoot(t.TempDir())
	bus.Init()
	a := map[string]any{"server": "source", "user_id": "one", "query": "片名", "token": "fixture-secret"}
	old := []any{map[string]any{"id": "old", "name": "旧资料", "token": "fixture-secret", "path": "fixture-private", "url": "fixture-private"}}
	_, _ = Run(context.Background(), 0, "emby.search", a, func() (any, error) { return old, nil })
	mu.Lock()
	memory = map[string]entry{}
	mu.Unlock()
	fresh := []any{map[string]any{"id": "new", "name": "新资料"}}
	got, err := Run(context.Background(), 21, "emby.search", a, func() (any, error) {
		var ev struct {
			T    string
			Seq  int64
			Data []map[string]any
		}
		if err := json.Unmarshal(bus.NextEvent(0), &ev); err != nil {
			t.Fatal(err)
		}
		if ev.T != "partial" || ev.Seq != 21 || ev.Data[0]["id"] != "old" || ev.Data[0]["token"] != nil || ev.Data[0]["url"] != nil || ev.Data[0]["path"] != nil {
			t.Fatalf("缓存快照契约错误: %s", ev.T)
		}
		return fresh, nil
	})
	if err != nil || got.([]any)[0].(map[string]any)["id"] != "new" {
		t.Fatal("最终结果必须为实时结果")
	}
	other := map[string]any{"server": "source", "user_id": "two", "query": "片名"}
	if load(key("emby.search", other), epoch()) != nil {
		t.Fatal("账号缓存串用")
	}
	a["token"] = "another-fixture"
	if load(key("emby.search", a), epoch()) == nil {
		t.Fatal("重登不应丢掉展示快照")
	}
}

func TestFailuresCancellationClearAndPlayback(t *testing.T) {
	paths.SetRoot(t.TempDir())
	e := epoch()
	a := map[string]any{"server": "s", "user_id": "u", "item_id": "i"}
	k := key("emby.itemDetail", a)
	value := map[string]any{"id": "i", "name": "资料", "resume_secs": 90, "played": true, "is_favorite": true}
	_, _ = Run(context.Background(), 0, "emby.itemDetail", a, func() (any, error) { return value, nil })
	var stored map[string]any
	_ = json.Unmarshal(load(k, e), &stored)
	if _, ok := stored["resume_secs"]; ok {
		t.Fatal("详情快照不能用于续播")
	}
	_, _ = Run(context.Background(), 0, "emby.itemDetail", a, func() (any, error) { return nil, bus.NewErr(bus.ENetwork, "离线") })
	if load(k, e) == nil {
		t.Fatal("联网失败应保留快照")
	}
	_, _ = Run(context.Background(), 0, "emby.itemDetail", a, func() (any, error) { return nil, bus.NewErr(bus.EAuth, "未登录") })
	if load(k, e) != nil {
		t.Fatal("鉴权失败应移除快照")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, _ = Run(ctx, 0, "emby.itemDetail", a, func() (any, error) { return value, nil })
	if load(k, e) != nil {
		t.Fatal("取消结果不能写入")
	}
	put(k, []byte(`{"id":"i"}`), e)
	if err := Clear(paths.ClearCache); err != nil {
		t.Fatal(err)
	}
	put(k, []byte(`{"id":"i"}`), e)
	if load(k, epoch()) != nil {
		t.Fatal("清理后的旧写入未拒绝")
	}
	for _, cmd := range []string{"player.play", "emby.itemMedia", "emby.permissions", "emby.setPlayed"} {
		calls := 0
		for i := 0; i < 2; i++ {
			_, _ = Run(context.Background(), 0, cmd, a, func() (any, error) { calls++; return value, nil })
		}
		if calls != 2 || load(key(cmd, a), epoch()) != nil {
			t.Fatalf("%s 应实时且不存快照", cmd)
		}
	}
}

func TestLongTermCapacityAndCorruption(t *testing.T) {
	paths.SetRoot(t.TempDir())
	e := epoch()
	for i := 0; i < 1000; i++ {
		k := hash(string(rune(i)))
		put(k, []byte(`[]`), e)
		past := time.Unix(int64(i), 0)
		_ = os.Chtimes(filepath.Join(root, k), past, past)
	}
	first := hash(string(rune(0)))
	if load(first, e) == nil {
		t.Fatal("长期缓存不可过期")
	}
	put(hash("new"), []byte(`[]`), e)
	files, _ := os.ReadDir(root)
	if len(files) != 1000 || len(memory) != 1000 {
		t.Fatal("1000条上限未生效")
	}
	if _, err := os.Stat(filepath.Join(root, first)); err != nil {
		t.Fatal("最近读取的快照被淘汰")
	}
	if _, err := os.Stat(filepath.Join(root, hash(string(rune(1))))); !os.IsNotExist(err) {
		t.Fatal("最旧快照未淘汰")
	}
	mu.Lock()
	memory = map[string]entry{}
	mu.Unlock()
	_ = os.WriteFile(filepath.Join(root, first), []byte("{broken"), 0600)
	if load(first, e) != nil {
		t.Fatal("损坏快照应回源")
	}
}

func TestAggregateFailureDoesNotOverwriteSnapshot(t *testing.T) {
	paths.SetRoot(t.TempDir())
	a := map[string]any{}
	old := []any{map[string]any{"server_id": "fixture", "resume": []any{map[string]any{"id": "old"}}}}
	_, _ = Run(context.Background(), 0, "emby.aggregateOverview", a, func() (any, error) { return old, nil })
	before := string(load(key("emby.aggregateOverview", a), epoch()))
	failed := []any{map[string]any{"server_id": "fixture", "resume": []any{}, "error": "不可用"}}
	_, _ = Run(context.Background(), 0, "emby.aggregateOverview", a, func() (any, error) { return failed, nil })
	if after := string(load(key("emby.aggregateOverview", a), epoch())); after != before {
		t.Fatal("单服失败覆盖了成功快照")
	}
}

func TestSourceSearchFailureKeepsSuccessAndEmptyClears(t *testing.T) {
	paths.SetRoot(t.TempDir())
	a := map[string]any{"server_id": "fixture", "query": "词", "limit": 50}
	old := map[string]any{"server_id": "fixture", "kind": "emby", "emby_items": []any{map[string]any{"id": "old"}}}
	_, _ = Run(context.Background(), 0, "source.aggregateSearch", a, func() (any, error) { return old, nil })
	before := string(load(key("source.aggregateSearch", a), epoch()))
	failure := map[string]any{"server_id": "fixture", "kind": "emby", "error": "暂不可用"}
	_, _ = Run(context.Background(), 0, "source.aggregateSearch", a, func() (any, error) { return failure, nil })
	if after := string(load(key("source.aggregateSearch", a), epoch())); after != before {
		t.Fatal("流式Emby搜索失败覆盖成功行")
	}
	_, _ = Run(context.Background(), 0, "source.aggregateSearch", a, func() (any, error) { return nil, nil })
	if after := string(load(key("source.aggregateSearch", a), epoch())); after != "null" {
		t.Fatal("成功空结果保留了旧条目")
	}
}

func TestExtensionSnapshotIdentityAndRevocation(t *testing.T) {
	paths.SetRoot(t.TempDir())
	a := map[string]any{"server": "test-server", "user_id": "one", "page": 1}
	rows := []any{map[string]any{"title": "作品", "source": "tmdb", "external_id": "101", "media_type": "movie", "rank": 1, "image_url": "https://test.invalid/private", "item_id": "live-only", "broadcast_time": "01:30", "timezone": "Asia/Tokyo", "time_source": "bangumi-data"}}
	for _, command := range []string{"emby.extRankings", "emby.extRankingItems", "emby.extCalendar"} {
		_, err := Run(context.Background(), 0, command, a, func() (any, error) { return rows, nil })
		if err != nil {
			t.Fatal(err)
		}
		b := load(key(command, a), epoch())
		var stored []map[string]any
		_ = json.Unmarshal(b, &stored)
		if len(stored) != 1 || stored[0]["external_id"] != "101" || stored[0]["title"] != "作品" || stored[0]["image_url"] != nil || stored[0]["item_id"] != nil || stored[0]["broadcast_time"] != "01:30" || stored[0]["timezone"] != "Asia/Tokyo" || stored[0]["time_source"] != "bangumi-data" {
			t.Fatalf("unsafe or incomplete snapshot: %s", b)
		}
		other := map[string]any{"server": "test-server", "user_id": "two", "page": 1}
		if load(key(command, other), epoch()) != nil {
			t.Fatal("cross-account snapshot")
		}
		other["user_id"] = "one"
		other["page"] = 2
		if load(key(command, other), epoch()) != nil {
			t.Fatal("cross-page snapshot")
		}
		_, _ = Run(context.Background(), 0, command, a, func() (any, error) { return nil, bus.NewErr(bus.EUnsupported, "disabled") })
		if load(key(command, a), epoch()) != nil {
			t.Fatal("unsupported snapshot retained")
		}
	}
	if commands["emby.extResolve"] || commands["emby.extCapabilities"] {
		t.Fatal("authorization cached")
	}
}

func TestPrivateSnapshotsPersistWithSpaceIsolation(t *testing.T) {
	paths.SetRoot(t.TempDir())
	args := map[string]any{"server": "fixture", "user_id": "u", "item_id": "i", "is_adult": true}
	value := map[string]any{"id": "i", "name": "私密资料"}
	_, err := Run(context.Background(), 0, "emby.itemDetail", args, func() (any, error) { return value, nil })
	if err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	memory = map[string]entry{}
	mu.Unlock()
	if Snapshot("emby.itemDetail", args) == nil {
		t.Fatal("私密快照未从磁盘恢复")
	}
	for field, other := range map[string]any{"is_adult": false, "user_id": "other", "server": "other"} {
		changed := map[string]any{}
		for k, v := range args {
			changed[k] = v
		}
		changed[field] = other
		if Snapshot("emby.itemDetail", changed) != nil {
			t.Fatalf("快照未按 %s 隔离", field)
		}
	}
	if err := Clear(paths.ClearCache); err != nil {
		t.Fatal(err)
	}
	if Snapshot("emby.itemDetail", args) != nil {
		t.Fatal("清理后仍有私密快照")
	}
}
