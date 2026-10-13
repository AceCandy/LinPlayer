package player

import (
	"context"
	"math"
	"testing"

	"linplayer/core/bus"
)

func TestDanmakuGenerationAndOffset(t *testing.T) {
	registerDanmakuFeed()
	call := func(cmd string, a map[string]any) any {
		t.Helper()
		r, err := bus.Invoke(context.Background(), cmd, a)
		if err != nil {
			t.Fatal(err)
		}
		return r
	}
	first := call("player.danmakuBegin", nil).(map[string]any)["generation"].(int64)
	second := call("player.danmakuBegin", nil).(map[string]any)["generation"].(int64)
	list := []feedComment{{Time: 10, Text: "测试", Mode: 1, Color: 16777215}}
	if _, err := danmakuSetGeneration(list, &first); err == nil {
		t.Fatal("旧播放不得挂载")
	}
	if _, err := danmakuSetGeneration(list, &second); err != nil {
		t.Fatal(err)
	}
	call("player.danmakuOffset", map[string]any{"seconds": 2.0})
	relayout()
	relayout()
	dmMu.Lock()
	got := dmItems[0].Time
	dmMu.Unlock()
	if got != 12 {
		t.Fatalf("重排重复累加偏移: %v", got)
	}
	call("player.danmakuOffset", map[string]any{"seconds": -600.0})
	for _, v := range danmakuHeatmap(10, 100) {
		if math.IsNaN(v) || v != 0 {
			t.Fatalf("负时间热力图无效: %v", v)
		}
	}
	danmakuSet(list)
	styMu.RLock()
	offset := dmOffset
	raw := dmRaw[0].Time
	styMu.RUnlock()
	if offset != 0 || raw != 10 {
		t.Fatalf("换片没有重置: %v %v", offset, raw)
	}
}
