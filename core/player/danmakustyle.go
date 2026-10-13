package player

// 全局弹幕显示设置、布局与热力图。
//
// 为什么排版留在核心层、绘制交给 UI:排一次要遍历上万条并逐轨算避让,
// 那是「换了设置才重来一次」的活;而画一帧只是把定好的坐标乘个缩放,
// 那是「每帧都要做」的活。两端各写一份排版的下场是 PC 和手机上同一集
// 弹幕排得不一样,而两边代码都「对」。

import (
	"context"
	"fmt"
	"math"
	"sort"
	"sync"

	"linplayer/core/bus"
	"linplayer/core/config"
)

// dmStyle 一份弹幕显示设置的快照。
type dmStyle struct {
	Area           float64
	Scale          float64
	Opacity        float64
	Speed          float64
	RollLines      int
	PreventOverlap bool
	TopLines       int
	BottomLines    int
	Merge          bool
	Bold           bool
}

var (
	styleCommandMu sync.Mutex // 样式读写按完整落盘/重排事务串行。
	styMu          sync.RWMutex
	sty            = dmStyleOf(config.DefaultPrefs())
	// dmRaw 灌进来的原始语料。布局的输入,style 一变就重排一次。
	dmRaw        []danmakuItem
	dmOffset     float64
	dmGeneration int64

	dmMu sync.Mutex
	// dmItems 排好版的那一份。UI 层一次性取走,自己按帧插位置。
	dmItems []danmakuItem
)

func dmStyleOf(p config.Prefs) dmStyle {
	return dmStyle{
		Area: p.DanmakuArea, Scale: p.DanmakuScale, Opacity: p.DanmakuOpacity,
		Speed: p.DanmakuSpeed, TopLines: p.DanmakuTopLines, BottomLines: p.DanmakuBottomLines,
		RollLines: p.DanmakuRollLines, PreventOverlap: p.DanmakuPreventOverlap,
		Merge: p.DanmakuMerge, Bold: p.DanmakuBold,
	}
}

func curStyle() dmStyle {
	styMu.RLock()
	defer styMu.RUnlock()
	return sty
}

// loadDanmakuStyle 把落库的设置读进内存并重排。起播时调一次。
func loadDanmakuStyle() {
	styMu.Lock()
	sty = dmStyleOf(config.Current().PrefsOf())
	styMu.Unlock()
	relayout()
}

// ---------------------------------------------------------------- 布局

// fixSeconds 置顶 / 置底弹幕停留多久。
//
// 滚动那条是「横穿画面」所以时长有物理含义,固定弹幕没有 —— 5 秒是
// B 站客户端的口径:够读完一句话,又不至于把那一行长期占死。
const fixSeconds = 5.0

// relayout 按当前设置重排:合并 → 分轨 → 存进 dmItems。
//
// ☠ **合并必须在分轨之前。** 反过来的话被合掉的那些条目已经占过轨道,
// 屏幕上会留下一排排空行,而弹幕总数看起来又是对的。
func relayout() {
	styMu.RLock()
	defer styMu.RUnlock()
	s := sty
	src := append([]danmakuItem(nil), dmRaw...)
	for i := range src {
		src[i].Time += dmOffset
	}

	items := src
	if s.Merge {
		items = mergeSame(items, 10.0)
	}
	out := layout(items, s)
	dmMu.Lock()
	dmItems = out
	dmMu.Unlock()
}

// mergeSame 时间窗口内同文本同类型合并,Count 记次数。
//
// ★ 和 core/danmaku 的 dedup 是**两件事**:那一份在取数时做、决定「存哪些」;
// 这一份在渲染前做、决定「这一屏怎么画」。开关在播放页一拨就要见效,
// 走取数那条的话得重新拉一次弹幕。
func mergeSame(in []danmakuItem, window float64) []danmakuItem {
	sorted := append([]danmakuItem(nil), in...)
	sort.SliceStable(sorted, func(i, j int) bool { return sorted[i].Time < sorted[j].Time })
	used := make([]bool, len(sorted))
	out := make([]danmakuItem, 0, len(sorted))
	for i := range sorted {
		if used[i] {
			continue
		}
		n := 1
		for j := i + 1; j < len(sorted); j++ {
			if used[j] {
				continue
			}
			if sorted[j].Time-sorted[i].Time > window {
				break // 已按时间排序,后面只会更远
			}
			if sorted[j].Text == sorted[i].Text && sorted[j].Mode == sorted[i].Mode {
				n++
				used[j] = true
			}
		}
		c := sorted[i]
		c.Count = n
		out = append(out, c)
	}
	return out
}

// layout 按行数分轨。防重叠开启时，三种模式共同占用纵向空间直到离场。
// 不依赖文本估宽来拼接同轨滚动弹幕，避免实际画面较窄或长句追尾；代价是密集时略过更多条目。
func layout(in []danmakuItem, s dmStyle) []danmakuItem {
	items := append([]danmakuItem(nil), in...)
	sort.SliceStable(items, func(i, j int) bool { return items[i].Time < items[j].Time })
	laneH := laneHeight * s.Scale
	maxLanes := int((float64(danmakuResY) - 8) / laneH)
	rollLanes := min(s.RollLines, maxLanes, max(1, int(float64(danmakuResY)*s.Area/laneH)))
	limits := map[int]int{1: rollLanes, 5: min(s.TopLines, maxLanes), 4: min(s.BottomLines, maxLanes)}
	next := map[int]int{}
	type occupiedRow struct{ top, until float64 }
	active := make([]occupiedRow, 0, maxLanes)
	out := make([]danmakuItem, 0, len(items))
	for _, d := range items {
		count := limits[d.Mode]
		if count <= 0 {
			continue
		}
		topAt := func(lane int) float64 {
			if d.Mode == 4 {
				return float64(danmakuResY) - 4 - float64(lane+1)*laneH
			}
			return 4 + float64(lane)*laneH
		}
		lane := next[d.Mode] % count
		if s.PreventOverlap {
			kept := active[:0]
			for _, row := range active {
				if row.until > d.Time {
					kept = append(kept, row)
				}
			}
			active = kept
			lane = -1
			for i := 0; i < count; i++ {
				top := topAt(i)
				available := true
				for _, row := range active {
					if top < row.top+laneH && row.top < top+laneH {
						available = false
						break
					}
				}
				if available {
					lane = i
					break
				}
			}
			if lane < 0 {
				continue
			}
			span := fixSeconds
			if d.Mode == 1 {
				span = rollSeconds / s.Speed
			}
			active = append(active, occupiedRow{topAt(lane), d.Time + span})
		} else {
			next[d.Mode] = lane + 1
		}
		d.Text = displayText(d)
		d.w = textWidth(d.Text, s.Scale)
		d.lane = lane
		out = append(out, d)
	}
	return out
}

// displayText 合并过的带上 xN。
func displayText(d danmakuItem) string {
	if d.Count > 1 {
		return fmt.Sprintf("%s ×%d", d.Text, d.Count)
	}
	return d.Text
}

// textWidth 估算文本像素宽。
//
// ★ 只能估:精确宽度得问两端各自的排字引擎,而排版在核心层跑。
//
//	估宽只影响「一轨能塞多密」和「什么时候完全走出左边」,偏一点不会画错。
func textWidth(text string, scale float64) float64 {
	w := 0.0
	for _, r := range text {
		if r < 0x2E80 { // 拉丁 / 数字 / 标点:大约半角
			w += 13
		} else {
			w += 26
		}
	}
	return w * scale
}

// ---------------------------------------------------------------- 热力图

// danmakuHeatmap 把当前语料按时间切成 buckets 段,返回每段的密度(0..1)。
//
// ★ 归一化到峰值而不是给绝对条数:进度条上那几十像素高的一条,
//
//	用户要的是「哪儿热闹」,不是「这里有 417 条」。
func danmakuHeatmap(buckets int, duration float64) []float64 {
	if buckets <= 0 {
		buckets = 120
	}
	styMu.RLock()
	src := append([]danmakuItem(nil), dmRaw...)
	for i := range src {
		src[i].Time += dmOffset
	}
	styMu.RUnlock()
	out := make([]float64, buckets)
	if len(src) == 0 {
		return out
	}
	if duration <= 0 {
		// 没给时长就用最后一条弹幕的时刻兜底 —— 画出来的形状仍然对,
		// 只是横轴会比整片短一截(片尾没弹幕时本来也就是这样)
		for _, d := range src {
			if d.Time > duration {
				duration = d.Time
			}
		}
	}
	if duration <= 0 {
		return out
	}
	peak := 0.0
	for _, d := range src {
		if d.Time < 0 {
			continue
		}
		i := int(d.Time / duration * float64(buckets))
		if i < 0 || i >= buckets {
			continue
		}
		out[i]++
		if out[i] > peak {
			peak = out[i]
		}
	}
	if peak == 0 {
		return out
	}
	for i := range out {
		out[i] /= peak
	}
	return out
}

// ---------------------------------------------------------------- 命令

func registerDanmakuStyle() {
	// player.getDanmakuStyle 回显。面板每次打开都要读回落库的那一份 ——
	// 不读的话滑块从写死的默认值起手,和画面上的弹幕对不上。
	bus.Register("player.getDanmakuStyle", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		styleCommandMu.Lock()
		defer styleCommandMu.Unlock()
		return styleReply(config.Current().PrefsOf()), nil
	})

	// player.setDanmakuStyle 改一项或几项,落库 + 立即重排上屏。
	bus.Register("player.setDanmakuStyle", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		styleCommandMu.Lock()
		defer styleCommandMu.Unlock()
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		c := config.Current()
		before := c.Prefs
		p := c.PrefsOf()
		if raw, exists := a["roll_lines"]; exists {
			v, ok := raw.(float64)
			if !ok || math.IsNaN(v) || math.IsInf(v, 0) || v != math.Trunc(v) || v < 0 || v > config.DanmakuLinesMax {
				return nil, bus.NewErr(bus.EInvalid, "滚动行数须为0到20的整数")
			}
			p.DanmakuRollLines = int(v)
		}
		if raw, exists := a["prevent_overlap"]; exists {
			v, ok := raw.(bool)
			if !ok {
				return nil, bus.NewErr(bus.EInvalid, "防止重叠须为开关值")
			}
			p.DanmakuPreventOverlap = v
		}
		if v, ok := numArg(a, "area"); ok {
			p.DanmakuArea = v
		}
		if v, ok := numArg(a, "scale"); ok {
			p.DanmakuScale = v
		}
		if v, ok := numArg(a, "opacity"); ok {
			p.DanmakuOpacity = v
		}
		if v, ok := numArg(a, "speed"); ok {
			p.DanmakuSpeed = v
		}
		if v, ok := numArg(a, "top_lines"); ok {
			p.DanmakuTopLines = int(v)
		}
		if v, ok := numArg(a, "bottom_lines"); ok {
			p.DanmakuBottomLines = int(v)
		}
		if v, ok := a["merge"].(bool); ok {
			p.DanmakuMerge = v
		}
		if v, ok := a["bold"].(bool); ok {
			p.DanmakuBold = v
		}
		if v, ok := a["heatmap"].(bool); ok {
			p.DanmakuHeatmap = v
		}
		if err := c.SetPrefs(p); err != nil {
			return nil, bus.NewErr(bus.EInternal, "偏好序列化失败: %v", err)
		}
		if err := c.Save(); err != nil {
			c.Prefs = before
			return nil, bus.NewErr(bus.EInternal, "配置保存失败: %v", err)
		}
		loadDanmakuStyle()
		// 回**钳过之后**的值:UI 照它刷读数,才不会显示一个核心层根本没接受的数
		return styleReply(config.Current().PrefsOf()), nil
	})

	// player.danmakuLayout 排好版的弹幕。灌完语料、改完设置各取一次。
	bus.Register("player.danmakuLayout", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		return danmakuLayoutReply(), nil
	})

	// player.danmakuHeatmap 当前这一集的弹幕密度。两端画同一份数据。
	bus.Register("player.danmakuHeatmap", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		n := 120
		if v, ok := numArg(a, "buckets"); ok && v > 0 {
			n = int(v)
		}
		dur, _ := numArg(a, "duration")
		return danmakuHeatmap(n, dur), nil
	})
}

// player.danmakuLayout 把排好版的这一份整个交给 UI。
//
// ★ 一次取走、UI 自己按帧插位置 —— 不是每帧来问一次。
//
//	每帧一次跨语言往返是上一版走 mpv `osd-overlay` 时的做法,
//	每秒 120 次,而那 120 次全排在 mpv 的核心线程上(见 danmaku.go 顶上那段)。
//
// 字段名用单字母:一集上万条,`"time"` 换成 `"t"` 省掉的是几百 KB 的
// 跨语言字符串。这是唯一一处这么写的地方,所以在这里说清楚:
// t=时刻 m=模式(1滚 4底 5顶) l=轨道 w=估算宽 c=RGB u=正文
func danmakuLayoutReply() map[string]any {
	s := curStyle()
	dmMu.Lock()
	items := dmItems
	dmMu.Unlock()

	out := make([]map[string]any, 0, len(items))
	for i := range items {
		d := &items[i]
		out = append(out, map[string]any{
			"t": d.Time, "m": d.Mode, "l": d.lane, "w": d.w, "c": d.Color, "u": d.Text,
		})
	}
	return map[string]any{
		"res_x": danmakuResX, "res_y": danmakuResY,
		"lane_height": laneHeight * s.Scale,
		// 这两个时长已经把速度算进去了:UI 不该再知道 speed 这一档怎么折算
		"roll_seconds": rollSeconds / s.Speed,
		"fix_seconds":  fixSeconds,
		"font_size":    40 * s.Scale,
		"opacity":      s.Opacity,
		"bold":         s.Bold,
		"items":        out,
	}
}

// DanmakuStyle 弹幕样式面板的回显体。
//
// 具名而不是裸 map:字段名是跨语言契约,拼错了两边都不报错,只是取到 null
// 画成空。有了这个类型 check-android-fields.py 才对得上账。
type DanmakuStyle struct {
	Area           float64 `json:"area"`
	Scale          float64 `json:"scale"`
	Opacity        float64 `json:"opacity"`
	Speed          float64 `json:"speed"`
	RollLines      int     `json:"roll_lines"`
	PreventOverlap bool    `json:"prevent_overlap"`
	TopLines       int     `json:"top_lines"`
	BottomLines    int     `json:"bottom_lines"`
	Merge          bool    `json:"merge"`
	Bold           bool    `json:"bold"`
	Heatmap        bool    `json:"heatmap"`
}

func styleReply(p config.Prefs) DanmakuStyle {
	return DanmakuStyle{
		Area: p.DanmakuArea, Scale: p.DanmakuScale, Opacity: p.DanmakuOpacity,
		Speed: p.DanmakuSpeed, TopLines: p.DanmakuTopLines,
		RollLines: p.DanmakuRollLines, PreventOverlap: p.DanmakuPreventOverlap,
		BottomLines: p.DanmakuBottomLines, Merge: p.DanmakuMerge,
		Bold: p.DanmakuBold, Heatmap: p.DanmakuHeatmap,
	}
}

func numArg(a map[string]any, k string) (float64, bool) {
	v, ok := a[k].(float64)
	return v, ok
}
