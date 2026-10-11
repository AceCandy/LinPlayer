// Package displaycache 保存各端共用的展示快照；最终命令结果始终来自实时请求。
package displaycache

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/paths"
)

const maxEntries = 1000
const maxBytes = 512 * 1024

type entry struct {
	data json.RawMessage
	used uint64
}

var mu sync.Mutex
var memory = map[string]entry{}
var root string
var generation, revision uint64

// 展示读取显式列举；鉴权、媒体版本、播放控制和写命令不参与缓存。
var commands = map[string]bool{}

func init() {
	commands["source.aggregateSearch"] = true
	for _, name := range []string{"views", "listLatest", "listResume", "listNextUp", "listFavorites", "listCollections",
		"collectionItems", "listItemsPage", "itemDetail", "seriesSeasons", "seasonEpisodes", "listItems", "listRandom",
		"getFilters", "search", "similarItems", "personDetail", "personItems", "counts", "aggregateOverview", "aggregateSearch"} {
		commands["emby."+name] = true
	}
}

func hash(s string) string { v := sha256.Sum256([]byte(s)); return hex.EncodeToString(v[:]) }

// 键只落散列：凭据不参与键，账号、查询、屏蔽配置和聚合账号组成参与隔离。
func key(command string, args map[string]any) string {
	a := map[string]any{}
	for k, v := range args {
		if k != "token" && k != "device_id" {
			a[k] = v
		}
	}
	if id, _ := a["server_id"].(string); id != "" {
		server, _, user, _, ok := config.Current().SessionOf(id)
		if ok {
			a["server"] = server
			a["user_id"] = user
		}
	}
	if command == "emby.aggregateOverview" || command == "emby.aggregateSearch" {
		accounts := []any{}
		c := config.Current()
		for _, acc := range c.AccountList {
			accounts = append(accounts, []any{acc.Server, acc.UserID, acc.ActiveLineURL(), acc.AllowAggregate()})
		}
		a["accounts"] = accounts
		a["active"] = c.Active
	}
	if info, err := os.Stat(paths.BlocklistFile()); err == nil {
		a["blocked_revision"] = info.ModTime().UnixNano()
	}
	b, _ := json.Marshal(a)
	return hash(command + "|" + string(b))
}

// Register 只给展示白名单增加快照；其它命令保持原处理器行为。
func Register(command string, h bus.Handler) {
	bus.Register(command, func(ctx context.Context, seq int64, args map[string]any) (any, error) {
		return Run(ctx, seq, command, args, func() (any, error) { return h(ctx, seq, args) })
	})
}

func epoch() uint64 {
	mu.Lock()
	defer mu.Unlock()
	if next := paths.DisplayCache(); root != next {
		root = next
		memory = map[string]entry{}
		generation++
	}
	return generation
}

func remember(k string, b json.RawMessage) {
	revision++
	memory[k] = entry{b, revision}
	if len(memory) > maxEntries {
		var oldest string
		used := revision
		for k, e := range memory {
			if e.used < used {
				oldest, used = k, e.used
			}
		}
		delete(memory, oldest)
	}
}

func load(k string, epoch uint64) json.RawMessage {
	mu.Lock()
	defer mu.Unlock()
	if epoch != generation {
		return nil
	}
	if e, ok := memory[k]; ok {
		remember(k, e.data)
		_ = os.Chtimes(filepath.Join(root, k), time.Now(), time.Now())
		return e.data
	}
	p := filepath.Join(root, k)
	info, err := os.Stat(p)
	if err != nil || info.Size() > maxBytes {
		return nil
	}
	b, err := os.ReadFile(p)
	if err != nil || !json.Valid(b) {
		_ = os.Remove(p)
		return nil
	}
	remember(k, b)
	_ = os.Chtimes(p, time.Now(), time.Now())
	return b
}

func put(k string, b json.RawMessage, epoch uint64) {
	if len(b) > maxBytes {
		return
	}
	mu.Lock()
	defer mu.Unlock()
	if epoch != generation {
		return
	}
	remember(k, b)
	// 可重取快照写盘失败不阻塞浏览，内存命中仍可用。
	if os.MkdirAll(root, 0700) != nil {
		return
	}
	f, err := os.CreateTemp(root, ".snapshot-")
	if err != nil {
		return
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	_, err = f.Write(b)
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil || closeErr != nil || os.Rename(tmp, filepath.Join(root, k)) != nil {
		return
	}
	files, _ := os.ReadDir(root)
	infos := []os.FileInfo{}
	for _, f := range files {
		if len(f.Name()) == 64 {
			if info, err := f.Info(); err == nil {
				infos = append(infos, info)
			}
		}
	}
	sort.Slice(infos, func(i, j int) bool { return infos[i].ModTime().Before(infos[j].ModTime()) })
	for i := 0; i < len(infos)-maxEntries; i++ {
		_ = os.Remove(filepath.Join(root, infos[i].Name()))
	}
}

// Clear 与磁盘清理共锁，清理前的在途刷新不能把旧快照写回来。
func Clear(remove func() error) error {
	mu.Lock()
	defer mu.Unlock()
	generation++
	memory = map[string]entry{}
	return remove()
}

// Run 发出可选的旧展示partial，仍等待实时请求；不改最终成功结果或错误契约。
func Run(ctx context.Context, seq int64, command string, args map[string]any, fetch func() (any, error)) (any, error) {
	if !commands[command] {
		return fetch()
	}
	e := epoch()
	k := key(command, args)
	if b := load(k, e); b != nil && seq != 0 && ctx.Err() == nil {
		bus.Partial(seq, restore(b))
	}
	value, err := fetch()
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if err != nil {
		var fault *bus.Err
		if errors.As(err, &fault) && (fault.Code == bus.EAuth || fault.Code == bus.ENotFound || fault.Code == bus.EPermission) {
			mu.Lock()
			if e == generation {
				delete(memory, k)
				_ = os.Remove(filepath.Join(root, k))
			}
			mu.Unlock()
		}
		return value, err
	}
	b, marshalErr := json.Marshal(value)
	if marshalErr == nil {
		var raw any
		if json.Unmarshal(b, &raw) == nil {
			// 聚合以成功整份快照保存；单服失败不能把旧结果覆盖成空列表。
			if command == "emby.aggregateOverview" || command == "emby.aggregateSearch" {
				if rows, ok := raw.([]any); ok {
					for _, row := range rows {
						if o, ok := row.(map[string]any); ok && o["error"] != nil {
							return value, nil
						}
					}
				}
			}
			if command == "source.aggregateSearch" {
				if row, ok := raw.(map[string]any); ok && row["error"] != nil {
					return value, nil
				}
			}
			safe := project(raw)
			if command == "emby.itemDetail" {
				if o, ok := safe.(map[string]any); ok {
					delete(o, "resume_secs")
					delete(o, "played")
					delete(o, "is_favorite")
				}
			}
			if b, err := json.Marshal(safe); err == nil {
				put(k, b, e)
			}
		}
	}
	return value, nil
}

// 白名单保留形状和展示字段，未知字段不会隐式落盘。
var fields = map[string]bool{}

func init() {
	for _, f := range []string{"id", "name", "type_", "is_folder", "has_primary", "runtime_secs", "resume_secs", "series_name", "series_id", "season_id", "episode_no", "season_no", "played", "is_favorite", "unplayed_item_count", "unplayed_count_known", "has_backdrop", "has_logo", "genres", "year", "rating", "DoubanRating", "date_updated", "sort_name", "library_ids", "library_type", "collection_type", "overview", "premiere_date", "official_rating", "status", "tagline", "child_count", "index_no", "unplayed", "boxset", "bgm_id", "tags", "studios", "people", "role", "children", "items", "total", "next_index", "has_more", "movies", "series", "others", "years", "official_ratings", "unavailable", "unsupported", "empty", "capabilities", "filters", "hide_resume", "refresh", "similar", "collections", "chapters", "provider_lookup", "kind", "emby_items", "ms", "birthday", "death_day", "birth_place", "video_height", "bitrate", "size_bytes", "video_range", "server_id", "server_name", "source_kind", "is_file_browse", "active", "counts", "movie", "episode", "resume"} {
		fields[f] = true
	}
}
func project(v any) any {
	switch x := v.(type) {
	case map[string]any:
		out := map[string]any{}
		for k, v := range x {
			if fields[k] {
				if k == "server_id" {
					if s, ok := v.(string); ok {
						out[k] = hash(s)
					}
				} else {
					out[k] = project(v)
				}
			}
		}
		return out
	case []any:
		out := make([]any, len(x))
		for i, v := range x {
			out[i] = project(v)
		}
		return out
	default:
		return v
	}
}
func restore(b json.RawMessage) any {
	var v any
	_ = json.Unmarshal(b, &v)
	var walk func(any)
	walk = func(v any) {
		switch x := v.(type) {
		case map[string]any:
			if id, ok := x["server_id"].(string); ok {
				for _, acc := range config.Current().AccountList {
					if hash(acc.Server) == id {
						x["server_id"] = acc.Server
						break
					}
				}
			}
			for _, v := range x {
				walk(v)
			}
		case []any:
			for _, v := range x {
				walk(v)
			}
		}
	}
	walk(v)
	return v
}
