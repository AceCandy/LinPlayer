package player

import (
	"context"
	"encoding/json"
	"os"
	"testing"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/paths"
)

func TestDanmakuOptionsPersistGlobally(t *testing.T) {
	setup(t)
	call := func(cmd string, args map[string]any) map[string]any {
		t.Helper()
		value, err := bus.Invoke(context.Background(), cmd, args)
		if err != nil {
			t.Fatal(err)
		}
		raw, _ := json.Marshal(value)
		var result map[string]any
		if err := json.Unmarshal(raw, &result); err != nil {
			t.Fatal(err)
		}
		return result
	}
	first := call("player.getDanmakuStyle", nil)
	if first["roll_lines"] != float64(20) || first["prevent_overlap"] != true {
		t.Fatalf("旧配置默认值: %v", first)
	}
	changed := call("player.setDanmakuStyle", map[string]any{"roll_lines": 3.0, "prevent_overlap": false})
	if changed["roll_lines"] != 3.0 || changed["prevent_overlap"] != false {
		t.Fatalf("设置未回显: %v", changed)
	}
	call("player.setDanmakuStyle", map[string]any{"bold": true})
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	loadDanmakuStyle()
	got := call("player.getDanmakuStyle", nil)
	if got["roll_lines"] != 3.0 || got["prevent_overlap"] != false || got["bold"] != true {
		t.Fatalf("重启/单项更新丢值: %v", got)
	}
	// 保存失败不得让下一次读取冒充已落盘。
	before := config.Current().PrefsOf()
	if err := os.Remove(paths.ConfigFile()); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(paths.ConfigFile(), 0700); err != nil {
		t.Fatal(err)
	}
	if _, err := bus.Invoke(context.Background(), "player.setDanmakuStyle", map[string]any{"roll_lines": 8.0}); err == nil {
		t.Fatal("保存失败未返回错误")
	}
	if styleReply(config.Current().PrefsOf()) != styleReply(before) {
		t.Fatal("保存失败仍修改内存偏好")
	}
	if _, err := bus.Invoke(context.Background(), "player.setDanmakuEnabled", map[string]any{"enabled": !before.DanmakuEnabled}); err == nil {
		t.Fatal("总开关保存失败未返回错误")
	}
	if config.Current().PrefsOf().DanmakuEnabled != before.DanmakuEnabled {
		t.Fatal("总开关保存失败仍修改内存偏好")
	}
}

func TestDanmakuRollLimitAndOverlapSwitch(t *testing.T) {
	input := []danmakuItem{{Time: 1, Mode: 1, Text: "滚动一"}, {Time: 1, Mode: 1, Text: "滚动二"}, {Time: 1, Mode: 1, Text: "滚动三"}}
	for _, tc := range []struct {
		raw   string
		count int
	}{
		{`{"danmaku_roll_lines":0}`, 0},
		{`{"danmaku_roll_lines":2,"danmaku_prevent_overlap":true}`, 2},
		{`{"danmaku_roll_lines":2,"danmaku_prevent_overlap":false}`, 3},
	} {
		out := layout(input, dmStyleOf(config.ParsePrefs([]byte(tc.raw))))
		if len(out) != tc.count {
			t.Fatalf("%s: got %d want %d", tc.raw, len(out), tc.count)
		}
		for _, d := range out {
			if d.lane >= 2 {
				t.Fatal("超过行数上限")
			}
		}
	}
}

func TestDanmakuMixedModesDoNotOverlap(t *testing.T) {
	s := dmStyleOf(config.DefaultPrefs())
	var in []danmakuItem
	for i := 0; i < 90; i++ {
		in = append(in, danmakuItem{Time: float64(i) / 10, Mode: []int{1, 4, 5}[i%3], Text: "同屏弹幕"})
	}
	out := layout(in, s)
	if len(out) < 3 {
		t.Fatal("避让不能清空弹幕")
	}
	top := func(d danmakuItem) float64 {
		if d.Mode == 4 {
			return danmakuResY - 4 - float64(d.lane+1)*laneHeight*s.Scale
		}
		return 4 + float64(d.lane)*laneHeight*s.Scale
	}
	for i, a := range out {
		for _, b := range out[i+1:] {
			span := fixSeconds
			if a.Mode == 1 {
				span = rollSeconds / s.Speed
			}
			if b.Time >= a.Time+span {
				continue
			}
			if top(a) < top(b)+laneHeight*s.Scale && top(b) < top(a)+laneHeight*s.Scale {
				t.Fatalf("存续期占用同一行: %+v / %+v", a, b)
			}
		}
	}
}

func TestDanmakuOptionsRejectInvalidValues(t *testing.T) {
	setup(t)
	before := styleReply(config.Current().PrefsOf())
	for _, args := range []map[string]any{
		{"roll_lines": -1.0}, {"roll_lines": 21.0}, {"roll_lines": 1.5},
		{"roll_lines": "2"}, {"prevent_overlap": "false"},
	} {
		if _, err := bus.Invoke(context.Background(), "player.setDanmakuStyle", args); err == nil {
			t.Fatalf("非法值被接受: %v", args)
		}
		if styleReply(config.Current().PrefsOf()) != before {
			t.Fatal("非法参数改变了偏好")
		}
	}
}
