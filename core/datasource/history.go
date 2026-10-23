package datasource

// 数据源观看历史与换源链路。

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"sync"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/history"
	"linplayer/core/paths"
)

var linkMu sync.Mutex

// HistoryEntry 全局观看历史里数据源的一条(D430 D431 D333)。
type HistoryEntry struct {
	ContinueEntry
	ServerName string `json:"server_name"`
	Removed    bool   `json:"removed"`
}

// cmdHistory 全部数据源的观看记录,按时间混排;同一部片在多个源都有时按「换源链路」合并取进度最远(D431)。
func cmdHistory(ctx context.Context, _ int64, a map[string]any) (any, error) {
	c := config.Current()
	all := history.Shared().LoadAll()
	var out []HistoryEntry
	for _, r := range sortByRecent(all) {
		if r.SourceRef == nil {
			continue
		}
		sid := history.ServerFromScope(r.ScopeKey)
		e := HistoryEntry{ContinueEntry: ContinueEntry{RecordID: r.RecordID, ServerID: sid, Ref: r.SourceRef, UpdatedAt: r.LastPlayedAt,
			Position: float64(r.LastPositionTicks) / float64(history.TicksPerSec)}}
		if r.RunTimeTicks != nil {
			e.Duration = float64(*r.RunTimeTicks) / float64(history.TicksPerSec)
		}
		if acc := c.Find(sid); acc != nil {
			e.ServerName = acc.DisplayName()
		} else {
			e.ServerName, e.Removed = sid, true
		}
		out = append(out, e)
	}
	return mergeLinked(out), nil
}

// ---------------------------------------------------------------- 换源链路(D236 D431)

type link struct{ From, To string } // 「服务器#条目 id」

func linkFile() string { return filepath.Join(paths.Root(), "source_links.json") }

// cmdLinkSwitch 用户从 A 换到 B 播放时记一条链路;历史合并只靠它,不靠片名猜(D431)。
func cmdLinkSwitch(ctx context.Context, _ int64, a map[string]any) (any, error) {
	from := str(a, "from_server") + "#" + str(a, "from_item")
	to := str(a, "to_server") + "#" + str(a, "to_item")
	linkMu.Lock()
	defer linkMu.Unlock()
	var ls []link
	if b, err := os.ReadFile(linkFile()); err == nil {
		_ = json.Unmarshal(b, &ls)
	}
	ls = append(ls, link{From: from, To: to})
	b, _ := json.Marshal(ls)
	if err := os.WriteFile(linkFile(), b, 0o644); err != nil {
		return nil, bus.NewErr(bus.EInternal, "保存失败: %v", err)
	}
	return nil, nil
}

// mergeLinked 有换源链路相连的记录合并成一条,取进度最远的(A 10%、B 30% → 显示 B)。
func mergeLinked(in []HistoryEntry) []HistoryEntry {
	var ls []link
	if b, err := os.ReadFile(linkFile()); err == nil {
		_ = json.Unmarshal(b, &ls)
	}
	parent := map[string]string{}
	var find func(string) string
	find = func(x string) string {
		for parent[x] != "" && parent[x] != x {
			x = parent[x]
		}
		return x
	}
	for _, l := range ls {
		a, b := find(l.From), find(l.To)
		if a != b {
			parent[a] = b
		}
	}
	best := map[string]int{}
	var out []HistoryEntry
	for _, e := range in {
		var ref sourceRef
		_ = json.Unmarshal(e.Ref, &ref)
		root := find(e.ServerID + "#" + ref.Item.str("id"))
		if i, ok := best[root]; ok {
			if progress(e) > progress(out[i]) {
				out[i] = e
			}
			continue
		}
		best[root] = len(out)
		out = append(out, e)
	}
	if out == nil {
		out = []HistoryEntry{}
	}
	return out
}

func progress(e HistoryEntry) float64 {
	if e.Duration <= 0 {
		return 0
	}
	return e.Position / e.Duration
}
