package player

import (
	"context"
	"math"
	"testing"
)

func TestMeasuredRollReusesOccupiedLane(t *testing.T) {
	s := testStyle()
	s.RollLines = 1
	var in []danmakuItem
	var widths []float64
	for i := 0; i < 16; i++ {
		in = append(in, danmakuItem{Time: float64(i), Mode: 1, Text: "连续弹幕"})
		widths = append(widths, 140)
	}
	out := layoutMeasured(in, s, 1920, widths)
	if len(out) != len(in) {
		t.Fatalf("同轨仍等待整条离场: got %d want %d", len(out), len(in))
	}
	for _, d := range out {
		if d.lane != 0 {
			t.Fatal("应在同一轨道连续接入")
		}
	}
}

func TestMeasuredRollPreventsLongTextCatchup(t *testing.T) {
	s := testStyle()
	s.RollLines = 1
	in := []danmakuItem{{Time: 0, Mode: 1}, {Time: 1, Mode: 1}, {Time: 4, Mode: 1}, {Time: 4.2, Mode: 1}, {Time: 6.7, Mode: 1}}
	out := layoutMeasured(in, s, 1920, []float64{60, 900, 900, 60, 60})
	if len(out) != 3 || out[1].Time != 4 || out[2].Time != 6.7 {
		t.Fatalf("追尾或入口碰撞未避让: %+v", out)
	}
}

func TestMeasuredGapAvoidsFiniteOverflow(t *testing.T) {
	if canFollow(danmakuItem{Time: 0, w: 1e308}, danmakuItem{Time: 1, w: 0}, 1e308, 2, 20) {
		t.Fatal("有限大数溢出导致无间距时仍接入")
	}
}

func TestMeasuredMixedModesStaySeparated(t *testing.T) {
	for _, viewport := range []float64{360, 1080, 1920, 4000} {
		for _, scale := range []float64{.1, 1, 3} {
			for _, speed := range []float64{.5, 1, 2} {
				s := testStyle()
				s.Scale, s.Speed, s.Area = scale, speed, .5
				var in []danmakuItem
				var widths []float64
				for i := 0; i < 250; i++ {
					in = append(in, danmakuItem{Time: float64(i) * .09, Mode: []int{1, 1, 1, 4, 5}[i%5]})
					widths = append(widths, float64(20+i%9*100)*scale)
				}
				out := layoutMeasured(in, s, viewport, widths)
				if len(out) == 0 {
					t.Fatal("不能通过丢弃全部弹幕规避重叠")
				}
				for now := 0.0; now < 30; now += .037 {
					type rect struct{ left, right, top float64 }
					var visible []rect
					for _, d := range out {
						span := fixSeconds
						if d.Mode == 1 {
							span = rollSeconds / speed
						}
						if now < d.Time || now >= d.Time+span {
							continue
						}
						x := (viewport - d.w) / 2
						if d.Mode == 1 {
							x = viewport - (viewport+d.w)*(now-d.Time)/span
						}
						top := 4 + float64(d.lane)*laneHeight*scale
						if d.Mode == 4 {
							top = danmakuResY*s.Area - 4 - float64(d.lane+1)*laneHeight*scale
						}
						for _, r := range visible {
							if top+1e-8 < r.top+laneHeight*scale && r.top+1e-8 < top+laneHeight*scale &&
								x < r.right-1e-8 && r.left < x+d.w-1e-8 {
								t.Fatalf("发生可见矩形重叠: viewport=%v scale=%v speed=%v time=%v", viewport, scale, speed, now)
							}
						}
						visible = append(visible, rect{x, x + d.w, top})
					}
				}
			}
		}
	}
}

func TestMeasuredLayoutSnapshotContract(t *testing.T) {
	setup(t)
	styMu.Lock()
	sty = testStyle()
	sty.RollLines = 1
	sty.Merge = true
	styMu.Unlock()
	danmakuSet([]feedComment{{Time: 0, Mode: 1, Text: "重复"}, {Time: 1, Mode: 1, Text: "重复"}, {Time: 2, Mode: 1, Text: "后续"}})
	prepared, err := measuredLayoutReply(context.Background(), map[string]any{"prepare": true})
	if err != nil {
		t.Fatal(err)
	}
	r := prepared.(map[string]any)
	items := r["items"].([]map[string]any)
	if len(items) != 2 || items[0]["u"] != "重复 ×2" {
		t.Fatalf("测量遗漏候选或合并后文字: %v", items)
	}
	req := map[string]any{"revision": float64(r["revision"].(int64)), "viewport_width": 1920.0, "widths": []any{140.0, 140.0}}
	result, err := measuredLayoutReply(context.Background(), req)
	if err != nil {
		t.Fatal(err)
	}
	final := result.(map[string]any)["items"].([]map[string]any)
	if len(final) != 2 || final[0]["u"] != "重复 ×2" {
		t.Fatal("候选被旧保守布局提前丢弃")
	}
	for _, widths := range [][]any{{140.0}, {140.0, -1.0}, {140.0, math.Inf(1)}, {140.0, "20"}} {
		bad := map[string]any{"revision": req["revision"], "viewport_width": 1920.0, "widths": widths}
		if _, err := measuredLayoutReply(context.Background(), bad); err == nil {
			t.Fatal("接受无效宽度")
		}
	}
	for _, viewport := range []any{0.0, -1.0, math.NaN(), math.Inf(1), "1920"} {
		bad := map[string]any{"revision": req["revision"], "viewport_width": viewport, "widths": req["widths"]}
		if _, err := measuredLayoutReply(context.Background(), bad); err == nil {
			t.Fatal("接受无效画布")
		}
	}
	danmakuSet([]feedComment{{Time: 0, Mode: 1, Text: "下一集"}})
	if _, err := measuredLayoutReply(context.Background(), req); err == nil {
		t.Fatal("旧语料测量覆盖新片")
	}
}
