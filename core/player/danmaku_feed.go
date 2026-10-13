package player

// 弹幕接到播放链上(SPEC §7.5)。
//
// ☠ 这条链**以前整段是断的**:`core/player/danmaku.go` 有渲染器、
// `core/danmaku` 有取数,中间没有任何东西把两者接起来 ——
// `danmaku.load` 只把弹幕**返回给调用方**。所以「弹幕开关」在三端都是
// 一个点了没反应的按钮,而且不报错。
//
// 分工:取哪一集、用哪个源、过滤规则,全是 `danmaku.*` 的事(调用方发);
// 本文件只管**灌进来的语料**和**开关**。这样 core/player 不用 import
// core/danmaku,两边各自能测。

import (
	"context"
	"encoding/json"
	"math"
	"sort"

	"linplayer/core/bus"
	"linplayer/core/config"
)

// feedComment 灌进来的一条弹幕。字段名与 `danmaku.Comment` 一致 ——
// 调用方把 `danmaku.autoLoad` 的返回**原样**转发过来,不许在 UI 侧改形状。
type feedComment struct {
	Time  float64 `json:"time"`
	Text  string  `json:"text"`
	Mode  int     `json:"mode"`
	Color int     `json:"color"`
}

// danmakuSet 用灌进来的语料替换当前弹幕。空数组 = 清空。
func danmakuSet(list []feedComment) int {
	n, _ := danmakuSetGeneration(list, nil)
	return n
}

func danmakuSetGeneration(list []feedComment, generation *int64) (int, error) {
	items := make([]danmakuItem, 0, len(list))
	for _, c := range list {
		if c.Text == "" {
			continue
		}
		mode := c.Mode
		if mode != 4 && mode != 5 {
			mode = 1
		}
		items = append(items, danmakuItem{
			Time: c.Time, Mode: mode, Text: c.Text, Count: 1,
			Color: uint32(c.Color) & 0xFFFFFF,
		})
	}
	// 按时间排:布局按出现顺序分轨,乱序进来会让同一时刻的几条挤在一条轨上。
	sort.Slice(items, func(i, j int) bool { return items[i].Time < items[j].Time })
	/* 存的是**原始语料**,分轨结果由 relayout 现算(见 danmakustyle.go)。
	   ☠ 别把布局结果当唯一副本存下来:合并 / 范围 / 行数 / 缩放 / 速度这五项
	     一改都要重排,而重排的输入是「合并之前、丢弃之前」的那一份。
	     只留布局结果的话,范围调小一次就再也调不回去了 —— 丢掉的条目找不回来。 */
	styMu.Lock()
	if generation != nil && *generation != dmGeneration {
		styMu.Unlock()
		return 0, bus.NewErr(bus.EInvalid, "弹幕请求已过期")
	}
	dmRaw = items
	dmOffset = 0
	styMu.Unlock()
	relayout()
	return len(items), nil
}

func registerDanmakuFeed() {
	registerDanmakuStyle()
	bus.Register("player.danmakuBegin", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		styMu.Lock()
		dmGeneration++
		generation := dmGeneration
		dmRaw = nil
		dmOffset = 0
		styMu.Unlock()
		relayout()
		return map[string]any{"generation": generation}, nil
	})
	bus.Register("player.danmakuOffset", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		if raw, ok := a["seconds"]; ok {
			v, valid := raw.(float64)
			if !valid || math.IsNaN(v) || math.IsInf(v, 0) || v < -600 || v > 600 {
				return nil, bus.NewErr(bus.EInvalid, "弹幕偏移须在 -600 到 600 秒之间")
			}
			styMu.Lock()
			dmOffset = v
			styMu.Unlock()
			relayout()
		}
		styMu.RLock()
		v := dmOffset
		styMu.RUnlock()
		return map[string]any{"seconds": v}, nil
	})

	// player.danmakuSet 灌语料。传空数组 = 清空(换片时必须清,否则上一集的弹幕会跟过来)。
	bus.Register("player.danmakuSet", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		raw, ok := a["items"]
		if !ok {
			return nil, bus.NewErr(bus.EInvalid, "缺少 items")
		}
		b, err := json.Marshal(raw)
		if err != nil {
			return nil, bus.NewErr(bus.EInvalid, "items 解析失败: %v", err)
		}
		var list []feedComment
		if json.Unmarshal(b, &list) != nil {
			return nil, bus.NewErr(bus.EInvalid, "items 不是弹幕列表")
		}
		var generation *int64
		if raw, ok := a["generation"]; ok {
			v, valid := raw.(float64)
			if !valid {
				return nil, bus.NewErr(bus.EInvalid, "generation 无效")
			}
			n := int64(v)
			generation = &n
		}
		n, err := danmakuSetGeneration(list, generation)
		if err != nil {
			return nil, err
		}
		bus.Logf("info", "弹幕语料已更新:%d 条", n)
		return map[string]any{"count": n}, nil
	})

	// player.setDanmakuEnabled 开关 + 落库。**只落库**,不碰渲染 ——
	// 画不画由 UI 层自己看这个值决定(绘制在 UI 层,见 danmaku.go 顶上那段)。
	//
	// ★ 落在 prefs 里而不是只存内存:用户关掉弹幕是**长期意愿**,
	//   下一集又自己开起来等于没关。
	bus.Register("player.setDanmakuEnabled", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		styleCommandMu.Lock()
		defer styleCommandMu.Unlock()
		on, ok := a["enabled"].(bool)
		if !ok {
			return nil, bus.NewErr(bus.EInvalid, "缺少 enabled")
		}
		c := config.Current()
		before := c.Prefs
		p := c.PrefsOf()
		p.DanmakuEnabled = on
		if err := c.SetPrefs(p); err != nil {
			return nil, bus.NewErr(bus.EInternal, "偏好序列化失败: %v", err)
		}
		if err := c.Save(); err != nil {
			c.Prefs = before
			return nil, bus.NewErr(bus.EInternal, "配置保存失败: %v", err)
		}
		return map[string]any{"enabled": on}, nil
	})
}
