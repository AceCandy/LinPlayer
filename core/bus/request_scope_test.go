package bus

import (
	"context"
	"encoding/json"
	"testing"
)

func TestRequestScopePolicy(t *testing.T) {
	scope := map[string]any{"id": "epoch", "private": true, "filter": true, "server": "fixture", "user_id": "u", "token": "fixture"}
	for _, command := range []string{"source.aggregateSearch", "plugin.list", "download.list", "emby.extRankings", "emby.watchHistory", "emby.aggregateSearch", "player.playLocal", "prefs.pushSearch"} {
		called := false
		_, err := scopedHandler(command, func(context.Context, int64, map[string]any) (any, error) { called = true; return nil, nil })(context.Background(), 1, map[string]any{"request_scope": scope})
		if err == nil || called {
			t.Fatal("受限请求已执行", command)
		}
	}
	args := map[string]any{"request_scope": scope, "item_id": "item"}
	clean, current, err := scopeArguments("emby.itemDetail", args)
	if err != nil || clean["is_adult"] != true || clean["token"] != "fixture" || current["id"] != "epoch" {
		t.Fatal(clean, err)
	}
	if _, exists := clean["request_scope"]; exists {
		t.Fatal("请求代次污染缓存键")
	}
	for _, key := range []string{"server", "user_id", "token", "server_id"} {
		args[key] = "other"
		if _, _, err := scopeArguments("emby.itemDetail", args); err == nil {
			t.Fatal("跨身份请求未拒绝", key)
		}
		delete(args, key)
	}
	scope["private"] = false
	clean, _, err = scopeArguments("emby.search", args)
	if err != nil || clean["is_adult"] != false {
		t.Fatal(clean, err)
	}
	args["server"] = "other"
	_, current, err = scopeArguments("emby.setFavorite", args)
	if err != nil || current != nil {
		t.Fatal("跨服写入通知当前服")
	}
}
func TestUserStateMutationClassification(t *testing.T) {
	for command, want := range map[string]string{"emby.setPlayed": "all", "emby.setBlocked": "all", "emby.setFavorite": "library", "emby.hideResume": "resume", "emby.itemDetail": ""} {
		if got := userStateChange(command); got != want {
			t.Fatal(command, got)
		}
	}
}

func TestScopedWritesEmitOnlyAfterSuccess(t *testing.T) {
	events := make(chan string, 8)
	Tap(func(name string, raw json.RawMessage) {
		if name != "data.userState" {
			return
		}
		var event map[string]any
		if json.Unmarshal(raw, &event) == nil && event["id"] == "scope-test-write" {
			select {
			case events <- event["scope"].(string):
			default:
			}
		}
	})
	args := map[string]any{"request_scope": map[string]any{"id": "scope-test-write", "private": false, "filter": true}}
	good := scopedHandler("emby.setFavorite", func(context.Context, int64, map[string]any) (any, error) { return nil, nil })
	if _, err := good(context.Background(), 9, args); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-events:
		if got != "library" {
			t.Fatal(got)
		}
	default:
		t.Fatal("成功写入未通知")
	}
	bad := scopedHandler("emby.setPlayed", func(context.Context, int64, map[string]any) (any, error) { return nil, NewErr(ENetwork, "离线") })
	_, _ = bad(context.Background(), 10, args)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, _ = good(ctx, 11, args)
	select {
	case <-events:
		t.Fatal("失败或取消的写入触发刷新")
	default:
	}
}

func TestPrivateLiuYingExtensionScope(t *testing.T) {
	scope := map[string]any{"id": "liuying", "private": true, "filter": true, "server": "fixture", "user_id": "user", "token": "fixture"}
	for _, command := range []string{"emby.extCapabilities", "emby.extLiuYingItems", "emby.extLiuYingLabels"} {
		clean, _, err := scopeArguments(command, map[string]any{"request_scope": scope})
		if err != nil || clean["is_adult"] != true || clean["server"] != "fixture" {
			t.Fatalf("流影命令未限定本服放行 %s: %v %v", command, clean, err)
		}
		if _, _, err := scopeArguments(command, map[string]any{"request_scope": scope, "server": "other"}); err == nil {
			t.Fatal("流影跨服请求未拒绝", command)
		}
	}
	for _, command := range []string{"emby.extRankings", "emby.extCalendar", "emby.extResolve", "emby.extLiuYingUnknown"} {
		if _, _, err := scopeArguments(command, map[string]any{"request_scope": scope}); err == nil {
			t.Fatal("意外放行其他扩展", command)
		}
	}
}
