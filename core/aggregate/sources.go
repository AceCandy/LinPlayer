package aggregate

// 按服务器分行的聚合搜索(谁先返回谁先显示,D258 D262)、跨服换源(D233)与「允许聚合」开关。
// 只包括「允许聚合」的 Emby 服务器(D235)。命令名沿用 `source.*`,各端已按这个名字接线。

import (
	"context"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode"

	"linplayer/core/account"
	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/displaycache"
	"linplayer/core/emby"
)

const (
	aggPerSource   = 50              // 聚合搜索每服展示条数(D258)
	aggConcurrency = 8               // 批量调用并发上限(D234)
	switchTimeout  = 8 * time.Second // 换源单服超时(D234)
	searchTimeout  = 20 * time.Second
)

// cfgMu 串行化「改账号表 → 落盘」。
var cfgMu sync.Mutex

// SearchRow 聚合搜索的一行(一台服务器)。
type SearchRow struct {
	ServerID   string      `json:"server_id"`
	ServerName string      `json:"server_name"`
	Kind       string      `json:"kind"` // 恒为 emby
	EmbyItems  []emby.Item `json:"emby_items,omitempty"`
	Error      *string     `json:"error,omitempty"`
	Warning    string      `json:"warning,omitempty"` // 服务端能力限制,结果仍可使用。
	MS         int64       `json:"ms"`
}

func registerSourceCommands() {
	bus.Register("source.setAggregate", cmdSetAggregate)
	bus.Register("source.aggregateSearch", cmdAggregateSearch)
	bus.Register("source.switchCandidates", cmdSwitchCandidates)
}

func aggregateTargets(c *config.AppConfig) []config.Account {
	var out []config.Account
	for _, acc := range c.AccountList {
		if acc.AllowAggregate() && !acc.IsFileBrowse() {
			out = append(out, acc)
		}
	}
	return out
}

// eachLimited 并发上限 n 地跑。
func eachLimited[T any](xs []T, n int, f func(T)) {
	sem := make(chan struct{}, n)
	var wg sync.WaitGroup
	for _, x := range xs {
		wg.Add(1)
		sem <- struct{}{}
		go func(x T) {
			defer wg.Done()
			defer func() { <-sem }()
			f(x)
		}(x)
	}
	wg.Wait()
}

func str(a map[string]any, k string) string { s, _ := a[k].(string); return s }

// cmdSetAggregate 「允许聚合」:一个开关管聚合搜索、聚合视界、换源(D233 D235)。
func cmdSetAggregate(ctx context.Context, _ int64, a map[string]any) (any, error) {
	sid := strings.TrimSpace(str(a, "server_id"))
	if sid == "" {
		return nil, bus.NewErr(bus.EInvalid, "缺少 server_id")
	}
	allow, _ := a["allow"].(bool)
	cfgMu.Lock()
	defer cfgMu.Unlock()
	c := config.Current()
	acc := c.Find(sid)
	if acc == nil {
		return nil, bus.NewErr(bus.ENotFound, "没有这个服务器:"+sid)
	}
	acc.SetAllowAggregate(allow)
	if err := c.Save(); err != nil {
		return nil, bus.NewErr(bus.EInternal, "保存失败: %v", err)
	}
	account.SyncImageAllowlist()
	bus.Emit("account.changed", map[string]any{}, "account.changed")
	return map[string]bool{"aggregate": allow}, nil
}

// cmdAggregateSearch 按回车才发(D258);每服一行,返回一行就 partial 推一行,最后回全部行。
func cmdAggregateSearch(ctx context.Context, seq int64, a map[string]any) (any, error) {
	ctx, err := emby.AdultFilterContext(ctx, a)
	if err != nil {
		return nil, err
	}
	q := strings.TrimSpace(str(a, "query"))
	if q == "" {
		return []SearchRow{}, nil
	}
	c := config.Current()
	var mu sync.Mutex
	rows := []SearchRow{}
	eachLimited(aggregateTargets(c), aggConcurrency, func(acc config.Account) {
		cacheArgs := map[string]any{"server_id": acc.Server, "query": q, "limit": aggPerSource}
		if adult := emby.AdultFilter(ctx); adult != nil {
			cacheArgs["is_adult"] = *adult
		}
		value, err := displaycache.Run(ctx, seq, "source.aggregateSearch", cacheArgs, func() (any, error) { return searchOne(ctx, c, acc, q, aggPerSource), nil })
		if err != nil {
			return
		}
		row, _ := value.(*SearchRow)
		if row == nil {
			// 成功空结果撤掉已发出的旧行，最终汇总仍保持原空结果契约。
			bus.Partial(seq, map[string]any{"server_id": acc.Server, "kind": "emby", "emby_items": []emby.Item{}})
			return
		}
		mu.Lock()
		rows = append(rows, *row)
		mu.Unlock()
		bus.Partial(seq, row)
	})
	return rows, nil
}

// searchOne 在一台服务器里搜;没结果返回 nil(整行不出),失败带 error(没搜成 ≠ 没有)。
func searchOne(ctx context.Context, c *config.AppConfig, acc config.Account, q string, limit int) *SearchRow {
	start := time.Now()
	row := &SearchRow{ServerID: acc.Server, ServerName: acc.DisplayName(), Kind: "emby"}
	sctx, cancel := context.WithTimeout(ctx, searchTimeout)
	defer cancel()
	items, err := client.Search(sctx, sessionOf(c, acc), q, []string{"Movie", "Series"}, 0, "")
	row.MS = time.Since(start).Milliseconds()
	if err != nil {
		msg := err.Error()
		row.Error = &msg
		return row
	}
	row.Warning = client.ProviderLookupNotice(sessionOf(c, acc).Server)
	if len(items) == 0 {
		if row.Warning == "" {
			return nil
		}
		return row
	}
	if len(items) > limit {
		items = items[:limit]
	}
	row.EmbyItems = items
	return row
}

// Candidate 换源的一个候选(其它允许聚合的 Emby 服务器上的同名作品)。
type Candidate struct {
	Layer      int        `json:"layer"` // 恒为 3(Emby,D233),保留字段兼容各端排序
	ServerID   string     `json:"server_id"`
	ServerName string     `json:"server_name"`
	Kind       string     `json:"kind"`
	Tier       int        `json:"tier"` // 0 同一部 / 1 可能是 / 2 不像(折叠到底部,D259 D261)
	MS         int64      `json:"ms"`   // 该服接口响应耗时:档内按它排
	EmbyItem   *emby.Item `json:"emby_item,omitempty"`
}

// cmdSwitchCandidates 在其它允许聚合的 Emby 服务器里找同一部。
// 并发 8、单服 8 秒、边出边 partial;返回按「档 → 耗时」排好的全表。
func cmdSwitchCandidates(ctx context.Context, seq int64, a map[string]any) (any, error) {
	sid := str(a, "server_id")
	title := strings.TrimSpace(str(a, "title"))
	year, _ := a["year"].(float64)
	var mu sync.Mutex
	out := []Candidate{}
	c := config.Current()
	if current := c.Resolve(sid); current != nil {
		sid = current.Server
	}
	var targets []config.Account
	for _, acc := range aggregateTargets(c) {
		if acc.Server != sid {
			targets = append(targets, acc)
		}
	}
	eachLimited(targets, aggConcurrency, func(acc config.Account) {
		sctx, cancel := context.WithTimeout(ctx, switchTimeout)
		defer cancel()
		row := searchOne(sctx, c, acc, title, 20)
		if row == nil || row.Error != nil {
			return
		}
		var cs []Candidate
		for i := range row.EmbyItems {
			e := row.EmbyItems[i]
			y := 0
			if e.Year != nil {
				y = int(*e.Year)
			}
			cs = append(cs, Candidate{Layer: 3, ServerID: acc.Server, ServerName: acc.DisplayName(), Kind: "emby", MS: row.MS,
				EmbyItem: &e, Tier: tierOf(title, int(year), e.Name, y)})
		}
		if len(cs) == 0 {
			return
		}
		mu.Lock()
		out = append(out, cs...)
		mu.Unlock()
		bus.Partial(seq, cs)
	})
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Tier != out[j].Tier {
			return out[i].Tier < out[j].Tier
		}
		return out[i].MS < out[j].MS
	})
	return out, nil
}

// tierOf 相似度三档:片名完全一致 > 年份一致 > 包含关键词;明显不对的(解说/预告)折叠(D259)。
func tierOf(want string, wantYear int, got string, gotYear int) int {
	w, g := normTitle(want), normTitle(got)
	junk := []string{"解说", "预告", "花絮", "速看", "片段", "reaction"}
	for _, j := range junk {
		if strings.Contains(strings.ToLower(got), j) && !strings.Contains(strings.ToLower(want), j) {
			return 2
		}
	}
	switch {
	case w == g && (wantYear == 0 || gotYear == 0 || wantYear == gotYear):
		return 0
	case w == g || (strings.Contains(g, w) || strings.Contains(w, g)) && (wantYear == 0 || gotYear == 0 || wantYear == gotYear):
		return 1
	}
	return 2
}

func normTitle(s string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
		}
	}
	return b.String()
}
