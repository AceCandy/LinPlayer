package danmaku

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/paths"
)

// ResolveResult 将语料和来源一起返回，避免并发播放读取错配的全局状态。
type ResolveResult struct {
	Items    []Comment       `json:"items"`
	Match    *MatchCandidate `json:"match"`
	Warnings []string        `json:"warnings,omitempty"`
}

type rememberedMatch struct {
	Match       MatchCandidate `json:"match"`
	Fingerprint string         `json:"fingerprint"`
	EpisodeNo   *int64         `json:"episode_no,omitempty"`
}

var memoryMu sync.Mutex

func memoryKeys(a map[string]any, in *MatchInput) (string, string) {
	server, user := str(a, "server"), str(a, "user_id")
	if id := str(a, "server_id"); id != "" {
		var ok bool
		server, _, user, _, ok = config.Current().SessionOf(id)
		if !ok {
			return "", ""
		}
	}
	if server == "" || user == "" || in.ItemID == "" {
		return "", ""
	}
	hash := func(parts ...string) string { b, _ := json.Marshal(parts); return fmt.Sprintf("%x", sha256.Sum256(b)) }
	scope := hash(strings.TrimRight(server, "/"), user)
	item := hash(scope, "item", in.ItemID)
	if in.SeriesID == "" || (in.SeasonID == "" && in.SeasonNo == nil) {
		return item, ""
	}
	season := in.SeasonID
	if season == "" {
		season = strconv.FormatInt(*in.SeasonNo, 10)
	}
	return item, hash(scope, "season", in.SeriesID, season)
}

func readMemory() (map[string]rememberedMatch, error) {
	b, err := os.ReadFile(paths.DanmakuMatchesFile())
	if os.IsNotExist(err) {
		return map[string]rememberedMatch{}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("读取弹幕匹配记忆失败")
	}
	var m map[string]rememberedMatch
	if json.Unmarshal(b, &m) != nil || m == nil {
		return nil, fmt.Errorf("弹幕匹配记忆损坏，未覆盖原文件")
	}
	return m, nil
}
func writeMemory(m map[string]rememberedMatch) error {
	p := paths.DanmakuMatchesFile()
	if err := os.MkdirAll(filepath.Dir(p), 0700); err != nil {
		return fmt.Errorf("保存弹幕匹配记忆失败")
	}
	b, err := json.Marshal(m)
	if err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(p), ".danmaku-matches-*")
	if err != nil {
		return fmt.Errorf("保存弹幕匹配记忆失败")
	}
	name := f.Name()
	defer os.Remove(name)
	if _, err = f.Write(b); err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err == nil {
		err = closeErr
	}
	if err == nil {
		err = os.Rename(name, p)
	}
	if err != nil {
		return fmt.Errorf("保存弹幕匹配记忆失败")
	}
	return nil
}

func remembered(ctx context.Context, a map[string]any, in *MatchInput, cfgs []SourceConfig) (*MatchCandidate, error) {
	item, season := memoryKeys(a, in)
	if item == "" {
		return nil, nil
	}
	memoryMu.Lock()
	m, err := readMemory()
	memoryMu.Unlock()
	if err != nil {
		return nil, err
	}
	p, ok := m[item]
	exact := ok
	if !ok && season != "" {
		p, ok = m[season]
	}
	if !ok {
		return nil, nil
	}
	cfg := findSourceIn(cfgs, p.Match.SourceID)
	if cfg == nil || p.Fingerprint != sourceFingerprint(cfg) {
		return nil, nil
	}
	c := p.Match
	c.SourceName = cfg.Name
	if exact {
		return &c, nil
	}
	if in.EpisodeNo == nil || p.EpisodeNo == nil || c.AnimeID == "" {
		return nil, nil
	}
	eps, err := cfg.BangumiEpisodes(ctx, c.AnimeID)
	if err != nil {
		return nil, err
	}
	// 以用户选中的真实集号为锚点；只计算集号映射，绝不计算分集 ID。
	var anchor *int64
	for _, ep := range eps {
		if ep.EpisodeID == c.EpisodeID && ep.EpisodeNumber != nil {
			match := regularEpisode.FindStringSubmatch(strings.TrimSpace(*ep.EpisodeNumber))
			if match != nil {
				n, e := strconv.ParseInt(match[1], 10, 64)
				if e == nil {
					anchor = &n
				}
			}
			break
		}
	}
	if anchor == nil {
		return nil, nil
	}
	target := *anchor + *in.EpisodeNo - *p.EpisodeNo
	ep := pickEpisode(eps, &target)
	if ep == nil {
		return nil, nil
	}
	c.EpisodeID, c.EpisodeTitle = ep.EpisodeID, ep.EpisodeTitle
	return &c, nil
}

func remember(ctx context.Context, a map[string]any, in *MatchInput, c MatchCandidate, cfg *SourceConfig) error {
	item, season := memoryKeys(a, in)
	if item == "" {
		return nil
	}
	memoryMu.Lock()
	defer memoryMu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}
	m, err := readMemory()
	if err != nil {
		return err
	}
	p := rememberedMatch{Match: c, Fingerprint: sourceFingerprint(cfg), EpisodeNo: in.EpisodeNo}
	m[item] = p
	if season != "" {
		m[season] = p
	}
	return writeMemory(m)
}

func resolveSource(ctx context.Context, a map[string]any) (ResolveResult, error) {
	result := ResolveResult{Items: []Comment{}}
	in, err := matchInputOf(a)
	if err != nil {
		return result, err
	}
	cfgs := allSources(AllowOfficialFor(in.Genres))
	load := func(ctx context.Context, c MatchCandidate) (bool, error) {
		cfg := findSourceIn(cfgs, c.SourceID)
		if cfg == nil {
			return false, bus.NewErr(bus.ENotFound, "弹幕源不可用或已停用")
		}
		items, err := getCommentsCached(ctx, cfg, c.EpisodeID, chConvertOf(a))
		if err != nil {
			return false, err
		}
		if err = ctx.Err(); err != nil {
			return false, err
		}
		if len(items) == 0 {
			return false, nil
		}
		c.SourceName = cfg.Name
		result = ResolveResult{Items: items, Match: &c}
		return true, nil
	}
	if raw, ok := a["selection"]; ok && raw != nil {
		cfgs = allSources(true)
		b, _ := json.Marshal(raw)
		var c MatchCandidate
		if json.Unmarshal(b, &c) != nil || c.EpisodeID == "" {
			return result, bus.NewErr(bus.EInvalid, "请选择弹幕分集")
		}
		timed, cancel := context.WithTimeout(ctx, sourceTimeout)
		defer cancel()
		ok, err := load(timed, c)
		if err != nil {
			return result, upstream(err)
		}
		if !ok {
			return result, bus.NewErr(bus.ENotFound, "所选分集没有弹幕")
		}
		if err := remember(ctx, a, in, *result.Match, findSourceIn(cfgs, c.SourceID)); err != nil {
			return result, upstream(err)
		}
		return result, nil
	}
	var failures []string
	if ignore, _ := a["ignore_memory"].(bool); !ignore {
		timed, cancel := context.WithTimeout(ctx, sourceTimeout)
		c, e := remembered(timed, a, in, cfgs)
		if e == nil && c != nil {
			var ok bool
			ok, e = load(timed, *c)
			if ok {
				cancel()
				return result, nil
			}
		}
		cancel()
		if e != nil {
			failures = append(failures, e.Error())
		}
	}
	in = withBgmTitles(ctx, in)
	for i := range cfgs {
		if err := ctx.Err(); err != nil {
			return result, err
		}
		cfg := &cfgs[i]
		timed, cancel := context.WithTimeout(ctx, sourceTimeout)
		candidates, e := matchOne(timed, cfg, in)
		sort.SliceStable(candidates, func(i, j int) bool { return candidates[i].Score > candidates[j].Score })
		if e == nil && len(candidates) > 0 && candidates[0].Score >= MinAutoScore {
			var ok bool
			ok, e = load(timed, candidates[0])
			if ok {
				cancel()
				return result, nil
			}
		}
		cancel()
		if e != nil {
			failures = append(failures, cfg.Name+": "+e.Error())
		}
	}
	if err := ctx.Err(); err != nil {
		return result, err
	}
	if len(failures) > 0 {
		return result, upstream(fmt.Errorf("%s", strings.Join(failures, "；")))
	}
	return result, nil
}

func registerResolve() {
	bus.Register("danmaku.resolve", func(ctx context.Context, seq int64, a map[string]any) (any, error) { return resolve(ctx, a) })
	bus.Register("danmaku.forget", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		in, err := matchInputOf(a)
		if err != nil {
			return nil, err
		}
		item, season := memoryKeys(a, in)
		if item == "" {
			return nil, nil
		}
		memoryMu.Lock()
		defer memoryMu.Unlock()
		m, err := readMemory()
		if err != nil {
			return nil, upstream(err)
		}
		delete(m, item)
		delete(m, season)
		if err = ctx.Err(); err != nil {
			return nil, err
		}
		return nil, writeMemory(m)
	})
}
