package player

import (
	"encoding/json"
	"path/filepath"
	"testing"

	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/history"
	"linplayer/core/paths"
)

func Test无会话停播仍清理上下文并强制保存进度(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	oldStore := history.Shared()
	store := history.New(filepath.Join(t.TempDir(), "history.json"))
	history.SetShared(store)
	t.Cleanup(func() { history.SetShared(oldStore) })
	runtime := int64(100 * history.TicksPerSec)
	currentMu.Lock()
	current = &emby.PlaybackTarget{ItemID: "movie"}
	pendingSubs = []emby.ExternalSub{{Title: "字幕"}}
	currentCtx = &historyContext{scope: "local", candidate: history.Candidate{
		ID: "movie", Name: "影片", Type: "Movie", RunTimeTicks: &runtime,
	}}
	currentMu.Unlock()
	setShaderScope("local")
	captureHistory(10, true) // 随即停播,必须绕过 10 秒节流。
	regTransportOnce.Do(registerTransport)
	r := call(t, 9081, "player.stopPlayback", map[string]any{"pos": 15.0})
	if !r.OK {
		t.Fatalf("本地停播失败: %s", r.Msg)
	}
	var data map[string]any
	_ = json.Unmarshal(r.Data, &data)
	if data["reported"] != false {
		t.Fatal("没有会话却报告上报成功")
	}
	currentMu.Lock()
	clean := current == nil && currentCtx == nil && len(pendingSubs) == 0
	currentMu.Unlock()
	if !clean || currentScope() != "" {
		t.Fatal("停播后仍残留目标、上下文或字幕")
	}
	records := store.LoadAll()
	if len(records) != 1 || records[0].LastPositionTicks != 15*history.TicksPerSec {
		t.Fatalf("最后进度未强制写盘: %+v", records)
	}
}
