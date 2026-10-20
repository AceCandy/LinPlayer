package displaycache

import (
	"context"
	"encoding/json"
	"linplayer/core/bus"
	"linplayer/core/paths"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCandidatesMergePersistIsolateAndClear(t *testing.T) {
	paths.SetRoot(t.TempDir())
	args := map[string]any{"server": "fixture", "user_id": "u", "is_adult": true}
	store := func(command string, value any) {
		t.Helper()
		if _, err := Run(context.Background(), 0, command, args, func() (any, error) { return value, nil }); err != nil {
			t.Fatal(err)
		}
	}
	store("emby.listLatest", []any{map[string]any{"id": "a", "name": "原名", "type_": "Movie", "token": "never-store", "url": "never-store"}})
	store("emby.listItemsPage", map[string]any{"items": []any{map[string]any{"id": "b", "name": "另一部", "type_": "Series"}}})
	store("emby.itemDetail", map[string]any{"id": "a", "name": "新名", "type_": "Movie", "has_backdrop": true})
	args["item_id"] = "a"
	store("emby.setPlayed", map[string]any{"item_id": "a", "played": true})
	mu.Lock()
	memory = map[string]entry{}
	mu.Unlock()
	rows := Candidates(args).([]any)
	if len(rows) != 2 {
		t.Fatalf("合并或恢复失败: %v", rows)
	}
	a := rows[1].(map[string]any)
	if a["name"] != "新名" || a["played"] != true || a["has_backdrop"] != true || a["token"] != nil || a["url"] != nil {
		t.Fatalf("合并投影错误: %v", a)
	}
	for field, value := range map[string]any{"server": "other", "user_id": "other", "is_adult": false} {
		changed := map[string]any{}
		for k, v := range args {
			changed[k] = v
		}
		changed[field] = value
		if len(Candidates(changed).([]any)) != 0 {
			t.Fatalf("跨%s候选", field)
		}
	}
	e := epoch()
	if err := Clear(paths.ClearCache); err != nil {
		t.Fatal(err)
	}
	recordCandidates("emby.listLatest", args, []any{a}, e)
	if len(Candidates(args).([]any)) != 0 {
		t.Fatal("清理后旧候选回写")
	}
}

func TestCandidatesBudgetAndFailedMutation(t *testing.T) {
	paths.SetRoot(t.TempDir())
	args := map[string]any{"server": "fixture", "user_id": "u"}
	rows := []any{}
	for _, id := range []string{"old", "new"} {
		rows = append(rows, map[string]any{"id": id, "name": strings.Repeat("x", maxBytes/2), "type_": "Movie"})
	}
	recordCandidates("emby.listLatest", args, rows, epoch())
	got := Candidates(args).([]any)
	if len(got) != 1 || got[0].(map[string]any)["id"] != "new" {
		t.Fatal("预算未淘汰最旧候选")
	}
	b, err := os.ReadFile(filepath.Join(root, candidateKey(args)))
	if err != nil || len(b) > maxBytes {
		t.Fatal("持久预算错误", err)
	}
	before := string(b)
	_, _ = Run(context.Background(), 0, "emby.setPlayed", args, func() (any, error) { return nil, bus.NewErr(bus.ENetwork, "离线") })
	b, _ = json.Marshal(Candidates(args))
	if string(b) != before {
		t.Fatal("失败操作改变候选")
	}
}

func TestMutationInvalidatesOnlyIdentitySnapshots(t *testing.T) {
	paths.SetRoot(t.TempDir())
	a := map[string]any{"server": "fixture", "user_id": "u", "is_adult": false}
	b := map[string]any{"server": "fixture", "user_id": "other", "is_adult": false}
	for _, args := range []map[string]any{a, b} {
		_, _ = Run(context.Background(), 0, "emby.listResume", args, func() (any, error) { return []any{map[string]any{"id": "i", "type_": "Movie"}}, nil })
	}
	old := epoch()
	_, _ = Run(context.Background(), 0, "emby.hideResume", a, func() (any, error) { return nil, nil })
	if Snapshot("emby.listResume", a) != nil || Snapshot("emby.listResume", b) == nil {
		t.Fatal("用户状态失效范围错误")
	}
	put(key("emby.listResume", a), json.RawMessage(`[]`), old)
	if Snapshot("emby.listResume", a) != nil {
		t.Fatal("状态变更前请求回写")
	}
	if len(Candidates(a).([]any)) != 1 {
		t.Fatal("失效不应丢掉轮播资料")
	}
}

func TestConcurrentStateWritesBothUpdateCandidates(t *testing.T) {
	paths.SetRoot(t.TempDir())
	args := map[string]any{"server": "fixture", "user_id": "u"}
	recordCandidates("emby.listLatest", args, []any{map[string]any{"id": "a", "type_": "Movie"}, map[string]any{"id": "b", "type_": "Movie"}}, epoch())
	entered, release, done := make(chan struct{}, 2), make(chan struct{}), make(chan struct{}, 2)
	for _, id := range []string{"a", "b"} {
		go func(id string) {
			a := map[string]any{"server": "fixture", "user_id": "u", "item_id": id}
			_, _ = Run(context.Background(), 0, "emby.setPlayed", a, func() (any, error) {
				entered <- struct{}{}
				<-release
				return map[string]any{"item_id": id, "played": true}, nil
			})
			done <- struct{}{}
		}(id)
	}
	<-entered
	<-entered
	close(release)
	<-done
	<-done
	for _, value := range Candidates(args).([]any) {
		if value.(map[string]any)["played"] != true {
			t.Fatal("并发写入漏更新候选")
		}
	}
}
