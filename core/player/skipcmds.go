package player

import (
	"context"
	"encoding/json"
	"math"
	"sync"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/segments"
)

/*
片头片尾的来源有三层,**优先级从高到低**:

	① 手动设定   —— 用户自己量的,压过一切
	② 服务端章节 —— 刮削过章节的库才有
	③ 第三方库   —— 前两层缺什么补什么(IntroDB / TheIntroDB / AniSkip)

为什么要第三层:用户 2026-09-06 —— 「很多 Emby 服务器没有数据」。
只靠章节的话,没刮章节的库上这个功能是**完全静默地不存在**的。
*/

var skipMarkersMu sync.Mutex

// skipKeysOf 保留旧剧级键作为兼容回退，新标记只按季或本集保存。
func skipKeysOf(ctx context.Context, s *emby.Session, itemID string, runtime float64) (map[string]string, segments.Meta) {
	it, err := prefsClient.ItemForHistory(ctx, s, itemID)
	if err != nil || it == nil {
		return nil, segments.Meta{}
	}
	key := func(scope, owner string) string {
		b, _ := json.Marshal([]string{s.Server, scope, owner})
		return string(b)
	}
	keys := map[string]string{"episode": key("episode", itemID)}
	m := segments.Meta{RuntimeSecs: runtime}
	ids, owner := it.ProviderIDs, it.ID
	if it.SeriesID != nil && *it.SeriesID != "" {
		owner = *it.SeriesID

		if seriesIDs := prefsClient.SeriesProviders(ctx, s, owner); len(seriesIDs) > 0 {
			ids = seriesIDs
		}
	} else {
		m.IsMovie = it.Type != "Episode"
	}
	if it.SeasonID != nil && *it.SeasonID != "" {
		keys["season"] = key("season", *it.SeasonID)
	} else if it.SeriesID != nil && *it.SeriesID != "" && it.SeasonNo != nil {
		b, _ := json.Marshal([]any{*it.SeriesID, *it.SeasonNo})
		keys["season"] = key("season-number", string(b))
	}
	keys["legacy"] = s.Server + "|" + owner
	if it.SeasonNo != nil {
		m.Season = int(*it.SeasonNo)
	}
	if it.EpisodeNo != nil {
		m.Episode = int(*it.EpisodeNo)
	}
	m.IMDb, m.TMDb, m.TVDb = emby.ProviderOf(ids, "Imdb"), emby.ProviderOf(ids, "Tmdb"), emby.ProviderOf(ids, "Tvdb")
	if m.MAL = emby.ProviderOf(ids, "MyAnimeList"); m.MAL == "" {
		m.MAL = emby.ProviderOf(ids, "Mal")
	}
	return keys, m
}

func skipRanges(r config.SkipRange, runtime float64) (intro, outro *emby.Range) {
	if r.IntroStart >= 0 && r.IntroEnd > r.IntroStart && (runtime <= 0 || r.IntroEnd <= runtime) {
		intro = &emby.Range{Start: r.IntroStart, End: r.IntroEnd}
	}
	if r.OutroLen > r.OutroEndOffset && r.OutroEndOffset >= 0 && runtime > 0 && runtime >= r.OutroLen {
		outro = &emby.Range{Start: runtime - r.OutroLen, End: runtime - r.OutroEndOffset}
	} else if r.OutroStart >= 0 && r.OutroEnd > r.OutroStart && (runtime <= 0 || r.OutroEnd <= runtime) {
		outro = &emby.Range{Start: r.OutroStart, End: r.OutroEnd}
	}
	return
}

// 每段独立继承，设置本集片头不能截断本季的片尾。
func manualSkip(pf config.Prefs, keys map[string]string, scope string, runtime float64) (intro, outro *emby.Range) {
	for _, level := range []string{"legacy", "season", "episode"} {
		if r, ok := pf.SkipOverrides[keys[level]]; ok && keys[level] != "" {
			i, o := skipRanges(r, runtime)
			if i != nil {
				intro = i
			}
			if o != nil {
				outro = o
			}
		}
		if level == scope {
			break
		}
	}
	return
}

func skipResult(pf config.Prefs, keys map[string]string, scope string, runtime float64) map[string]any {
	var r any
	if v, ok := pf.SkipOverrides[keys[scope]]; ok {
		r = v
	}
	i, o := manualSkip(pf, keys, scope, runtime)
	_, legacy := pf.SkipOverrides[keys["legacy"]]
	return map[string]any{"key": keys[scope], "range": r, "scope": scope, "can_season": keys["season"] != "", "intro": i, "outro": o, "legacy": legacy}
}

// fillSkip 按三层优先级把 info 的 Intro / Outro 填满,返回数据出处(给界面说明用)。
//
// ★ 开关归它一个人管,调用方不必再判:关着的那一段恒为 nil ——
// **手动填过的除外**。用户为这部剧专门填了时长,下一集却因为设置页的开关没开
// 而不出跳过键,那等于填了没用(用户 2026-09-18 把入口改成了「填时长」)。
func fillSkip(ctx context.Context, s *emby.Session, itemID string,
	runtime float64, info *emby.ChapterInfo, pf config.Prefs) string {
	if !pf.SkipIntro {
		info.Intro = nil
	}
	if !pf.SkipOutro {
		info.Outro = nil
	}
	// 两个开关都关、也没有任何手动设定:一层都不查 —— 尤其是第三层,那是一次外网请求
	if !pf.SkipIntro && !pf.SkipOutro && len(pf.SkipOverrides) == 0 {
		return ""
	}
	from := ""
	if info.Intro != nil || info.Outro != nil {
		from = "服务端章节"
	}

	keys, meta := skipKeysOf(ctx, s, itemID, runtime)
	intro, outro := manualSkip(pf, keys, "episode", runtime)
	if intro != nil {
		info.Intro = intro
		from = "手动设定"
	}
	if outro != nil {
		info.Outro = outro
		from = "手动设定"
	}

	need := (pf.SkipIntro && info.Intro == nil) || (pf.SkipOutro && info.Outro == nil)
	if !pf.SkipUseOnline || !need {
		return from
	}
	r := segments.Lookup(ctx, meta)
	if r == nil {
		return from
	}
	got := false
	if pf.SkipIntro && info.Intro == nil && r.Intro != nil {
		info.Intro = &emby.Range{Start: r.Intro.Start, End: r.Intro.End}
		got = true
	}
	if pf.SkipOutro && info.Outro == nil && r.Outro != nil {
		info.Outro = &emby.Range{Start: r.Outro.Start, End: r.Outro.End}
		got = true
	}
	if !got {
		return from
	}
	if from == "" {
		return r.From
	}
	return from + " + " + r.From
}

// scopedSkip 校验作用域，auto 只供读取默认范围，写入必须明确。
func scopedSkip(value any, exists bool, keys map[string]string) (string, error) {
	scope, ok := value.(string)
	if !exists {
		return "legacy", nil
	}
	if !ok {
		return "", bus.NewErr(bus.EInvalid, "无效的标记范围")
	}
	if scope == "auto" {
		scope = "season"
		if keys[scope] == "" {
			scope = "episode"
		}
	}
	if (scope != "season" && scope != "episode") || keys[scope] == "" {
		return "", bus.NewErr(bus.EInvalid, "没有本季信息，请选择仅本集")
	}
	return scope, nil
}

func registerSkip() {
	bus.Register("player.setSkipRange", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		id, _ := a["item_id"].(string)
		if id == "" {
			return nil, bus.NewErr(bus.EInvalid, "缺少 item_id")
		}
		sess, err := sessionFrom(a)
		if err != nil {
			return nil, err
		}
		keys, _ := skipKeysOf(ctx, sess, id, 0)
		if keys == nil {
			return nil, bus.NewErr(bus.EInternal, "无法读取当前影片信息")
		}
		rawScope, hasScope := a["scope"]
		scope, err := scopedSkip(rawScope, hasScope, keys)
		if err != nil {
			return nil, err
		}
		num := func(k string) float64 { v, _ := a[k].(float64); return v }
		for _, k := range []string{"position", "runtime_secs", "intro_start", "intro_end", "outro_start", "outro_end", "outro_len"} {
			if v, exists := a[k]; exists {
				n, ok := v.(float64)
				if !ok || math.IsNaN(n) || math.IsInf(n, 0) || n < 0 {
					return nil, bus.NewErr(bus.EInvalid, "时间必须是非负秒数")
				}
			}
		}
		// 同一条或不同作用域的标记命令串行提交，避免相互覆盖及失败回滚串扰。
		skipMarkersMu.Lock()
		defer skipMarkersMu.Unlock()
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		c := config.Current()
		before := c.PrefsOf()
		pf := before
		pf.SkipOverrides = make(map[string]config.SkipRange, len(before.SkipOverrides)+1)
		for k, v := range before.SkipOverrides {
			pf.SkipOverrides[k] = v
		}
		r := config.SkipRange{IntroStart: num("intro_start"), IntroEnd: num("intro_end"), OutroStart: num("outro_start"), OutroEnd: num("outro_end"), OutroLen: num("outro_len")}
		runtime, _ := a["runtime_secs"].(float64)
		if scope != "legacy" {
			if a["scope"] == "auto" {
				return nil, bus.NewErr(bus.EInvalid, "请选择本季或仅本集")
			}
			action, _ := a["action"].(string)
			r = pf.SkipOverrides[keys[scope]]
			if action == "clear" {
				r = config.SkipRange{}
			} else {
				pos := num("position")
				if _, ok := a["position"]; !ok || runtime <= 0 || pos > runtime {
					return nil, bus.NewErr(bus.EInvalid, "播放时长或当前位置无效")
				}
				i, o := manualSkip(pf, keys, scope, runtime)
				switch action {
				case "intro_end":
					start := 0.0
					if i != nil {
						start = i.Start
					}
					if pos <= start {
						return nil, bus.NewErr(bus.EInvalid, "片头结束必须晚于开始")
					}
					r.IntroStart, r.IntroEnd = start, pos
				case "intro_start":
					if i == nil || pos >= i.End {
						return nil, bus.NewErr(bus.EInvalid, "请先在更后的位置设置片头结束")
					}
					r.IntroStart, r.IntroEnd = pos, i.End
				case "outro_start", "outro_end":
					start, end := pos, runtime
					if action == "outro_end" {
						if o == nil {
							return nil, bus.NewErr(bus.EInvalid, "请先设置片尾开始")
						}
						start, end = o.Start, pos
					} else if o != nil {
						end = o.End
					}
					if start >= end {
						return nil, bus.NewErr(bus.EInvalid, "片尾结束必须晚于开始")
					}
					r.OutroStart, r.OutroEnd = 0, 0
					r.OutroLen, r.OutroEndOffset = runtime-start, runtime-end
				default:
					return nil, bus.NewErr(bus.EInvalid, "无效的标记操作")
				}
			}
		} else if r.IntroEnd < r.IntroStart || r.OutroEnd < r.OutroStart {
			return nil, bus.NewErr(bus.EInvalid, "结束时间不能早于开始时间")
		}
		if r == (config.SkipRange{}) {
			delete(pf.SkipOverrides, keys[scope])
		} else {
			pf.SkipOverrides[keys[scope]] = r
		}
		if err := savePrefs(c, pf); err != nil {
			_ = c.SetPrefs(before)
			return nil, err
		}
		return skipResult(pf, keys, scope, runtime), nil
	})
	bus.Register("player.getSkipRange", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		id, _ := a["item_id"].(string)
		if id == "" {
			return nil, bus.NewErr(bus.EInvalid, "缺少 item_id")
		}
		sess, err := sessionFrom(a)
		if err != nil {
			return nil, err
		}
		runtime, _ := a["runtime_secs"].(float64)
		keys, _ := skipKeysOf(ctx, sess, id, runtime)
		if keys == nil {
			return nil, bus.NewErr(bus.EInternal, "无法读取当前影片信息")
		}
		rawScope, hasScope := a["scope"]
		scope, err := scopedSkip(rawScope, hasScope, keys)
		if err != nil {
			return nil, err
		}
		return skipResult(config.Current().PrefsOf(), keys, scope, runtime), nil
	})
}
