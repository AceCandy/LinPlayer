package player

import (
	"context"
	"math"

	"linplayer/core/bus"
)

// measuredLayoutReply 将同一候选快照和宿主实际几何绑定；测量结果不写全局布局。
func measuredLayoutReply(ctx context.Context, a map[string]any) (any, error) {
	if a["prepare"] == nil && a["revision"] == nil && a["viewport_width"] == nil && a["widths"] == nil && a["until"] == nil {
		return danmakuLayoutReply(), nil
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	dmMu.Lock()
	items, style, revision := dmPrepared, dmPreparedStyle, dmRevision
	dmMu.Unlock()
	if raw, exists := a["until"]; exists {
		until, ok := raw.(float64)
		if !ok || until < 0 || math.IsNaN(until) || math.IsInf(until, 0) {
			return nil, bus.NewErr(bus.EInvalid, "弹幕准备终点无效")
		}
		// 保留所有此前候选；只截未来，避免跨批次改变占轨和合并结果。
		prefix := make([]danmakuItem, 0)
		for _, item := range items {
			if item.Time <= until {
				prefix = append(prefix, item)
			}
		}
		items = prefix
	}
	if prepare, ok := a["prepare"].(bool); ok && prepare && a["widths"] == nil && a["revision"] == nil {
		formatted := append([]danmakuItem(nil), items...)
		for i := range formatted {
			formatted[i].Text = displayText(formatted[i])
		}
		reply := danmakuReply(formatted, style)
		reply["revision"], reply["needs_measurement"] = revision, true
		return reply, nil
	}
	v, ok := a["revision"].(float64)
	if !ok || v != float64(revision) || revision <= 0 {
		return nil, bus.NewErr(bus.EInvalid, "弹幕测量已过期")
	}
	viewport, ok := a["viewport_width"].(float64)
	if !ok || viewport <= 0 || math.IsNaN(viewport) || math.IsInf(viewport, 0) {
		return nil, bus.NewErr(bus.EInvalid, "弹幕画布宽度无效")
	}
	raw, ok := a["widths"].([]any)
	if !ok || len(raw) != len(items) {
		return nil, bus.NewErr(bus.EInvalid, "弹幕测量条数不匹配")
	}
	widths := make([]float64, len(raw))
	for i, value := range raw {
		w, valid := value.(float64)
		if !valid || w < 0 || math.IsNaN(w) || math.IsInf(w, 0) {
			return nil, bus.NewErr(bus.EInvalid, "弹幕文字宽度无效")
		}
		widths[i] = w
	}
	out := layoutMeasured(items, style, viewport, widths)
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	dmMu.Lock()
	current := dmRevision
	style.Opacity = dmPreparedStyle.Opacity
	dmMu.Unlock()
	if current != revision {
		return nil, bus.NewErr(bus.EInvalid, "弹幕测量已过期")
	}
	reply := danmakuReply(out, style)
	reply["revision"] = revision
	return reply, nil
}
