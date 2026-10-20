package displaycache

import (
	"encoding/json"
	"linplayer/core/config"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// 候选池合并各次浏览资料；只有核心维护身份、投影和持久预算。
var candidatesMu sync.Mutex

func candidateKey(args map[string]any) string {
	identity := map[string]any{}
	for _, field := range []string{"server", "user_id", "server_id", "is_adult"} {
		if value, ok := args[field]; ok {
			identity[field] = value
		}
	}
	return key("emby.cachedItems", identity)
}

// Candidates 仅供轮播展示，不用于播放、授权或用户状态决策。
func Candidates(args map[string]any) any {
	if rows := restore(load(candidateKey(args), epoch())); rows != nil {
		return rows
	}
	return []any{}
}

func recordCandidates(command string, args map[string]any, raw any, e uint64) {
	switch command {
	case "emby.listLatest", "emby.listResume", "emby.listNextUp", "emby.listFavorites", "emby.listItemsPage", "emby.listItems", "emby.search", "emby.itemDetail", "emby.seriesSeasons", "emby.seasonEpisodes", "emby.listRandom", "emby.setPlayed":
	default:
		return
	}
	candidatesMu.Lock()
	defer candidatesMu.Unlock()
	k := candidateKey(args)
	previous, _ := restore(load(k, e)).([]any)
	rows := append([]any{}, previous...)
	incoming, ok := raw.([]any)
	if !ok {
		if obj, ok := raw.(map[string]any); ok {
			incoming, ok = obj["items"].([]any)
			if !ok {
				incoming = []any{obj}
			}
		}
	}
	for _, value := range incoming {
		row, ok := project(value).(map[string]any)
		if !ok {
			continue
		}
		if command == "emby.setPlayed" {
			row["id"] = args["item_id"]
		}
		id, _ := row["id"].(string)
		if id == "" {
			continue
		}
		typ, _ := row["type_"].(string)
		if typ != "" && typ != "Movie" && typ != "Series" && typ != "Season" && typ != "Episode" {
			continue
		}
		found := false
		for i, old := range rows {
			obj, _ := old.(map[string]any)
			if obj["id"] != id {
				continue
			}
			merged := map[string]any{}
			for field, value := range obj {
				merged[field] = value
			}
			for field, value := range row {
				merged[field] = value
			}
			row = merged
			rows = append(rows[:i], rows[i+1:]...)
			found = true
			break
		}
		if found || typ != "" {
			rows = append(rows, row)
		}
	}
	for len(rows) > 0 {
		b, err := json.Marshal(rows)
		if err != nil {
			return
		}
		if len(b) <= maxBytes {
			put(k, b, e)
			return
		}
		rows = rows[1:]
	}
}

// 同一账号和空间的用户状态改变后废止展示快照，保留轮播资料池。
func identityPrefix(args map[string]any) string {
	server, user := args["server"], args["user_id"]
	if id, _ := args["server_id"].(string); id != "" {
		if s, _, u, _, ok := config.Current().SessionOf(id); ok {
			server, user = s, u
		}
	}
	b, _ := json.Marshal([]any{server, user, args["is_adult"]})
	return hash(string(b))[:32]
}

func invalidateIdentity(args map[string]any, e uint64) uint64 {
	prefix, pool := identityPrefix(args), candidateKey(args)
	mu.Lock()
	defer mu.Unlock()
	current := e == cleared
	generation++
	for k := range memory {
		if k != pool && strings.HasPrefix(k, prefix) {
			delete(memory, k)
		}
	}
	files, _ := os.ReadDir(root)
	for _, f := range files {
		if f.Name() != pool && strings.HasPrefix(f.Name(), prefix) {
			_ = os.Remove(filepath.Join(root, f.Name()))
		}
	}
	if current {
		return generation
	}
	return 0
}
