package player

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/paths"
)

func TestSkipMarkersScopesAndOffsets(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	setSkipPrefs(t, false, false, false, false)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Path[strings.LastIndex(r.URL.Path, "/")+1:]
		season := "season1"
		if id == "e3" {
			season = "season2"
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"Id": id, "Type": "Episode", "SeriesId": "series", "SeasonId": season, "ParentIndexNumber": 1, "Chapters": []any{}})
	}))
	defer server.Close()
	seq := int64(9700)
	command := func(cmd, id, scope, action string, pos, duration float64, success bool) map[string]any {
		t.Helper()
		seq++
		a := map[string]any{"server": server.URL, "token": "tk", "user_id": "u", "device_id": "d", "item_id": id, "scope": scope, "action": action, "position": pos, "runtime_secs": duration}
		r := call(t, seq, cmd, a)
		if r.OK != success {
			t.Fatalf("%s %s %s: ok=%v %s", cmd, scope, action, r.OK, r.Msg)
		}
		var m map[string]any
		_ = json.Unmarshal(r.Data, &m)
		return m
	}
	set := func(id, scope, action string, pos float64) {
		command("player.setSkipRange", id, scope, action, pos, 1400, true)
	}
	get := func(id string, duration float64) map[string]any {
		return command("player.getSkipRange", id, "episode", "", 0, duration, true)
	}
	check := func(m map[string]any, k string, start, end float64) {
		t.Helper()
		r, ok := m[k].(map[string]any)
		if !ok || r["start"] != start || r["end"] != end {
			t.Fatalf("%s: %v want %v..%v", k, m[k], start, end)
		}
	}
	set("e1", "season", "intro_end", 90)
	set("e1", "season", "intro_start", 20)
	set("e1", "season", "outro_start", 1250)
	set("e1", "season", "outro_end", 1350)
	check(get("e2", 1500), "intro", 20, 90)
	check(get("e2", 1500), "outro", 1350, 1450)
	if m := get("e3", 1400); m["intro"] != nil || m["outro"] != nil {
		t.Fatal("跨季泄漏", m)
	}
	set("e1", "episode", "intro_end", 110)
	check(get("e1", 1400), "intro", 20, 110)
	check(get("e1", 1400), "outro", 1250, 1350)
	check(chapterInfoOf(t, 9850, server.URL), "intro", 20, 110)
	check(get("e2", 1400), "intro", 20, 90)
	set("e1", "episode", "clear", 0)
	check(get("e1", 1400), "intro", 20, 90)
	for _, v := range []struct {
		action string
		pos    float64
	}{{"intro_end", 10}, {"intro_start", 95}, {"outro_end", 1200}, {"outro_start", 1390}, {"intro_end", 1500}, {"intro_end", -1}, {"unknown", 100}} {
		command("player.setSkipRange", "e1", "season", v.action, v.pos, 1400, false)
	}
	check(get("e1", 1400), "outro", 1250, 1350)
	// 写盘失败既不能误报成功，也不能留下未落盘的内存值。
	root := paths.Root()
	blocker := filepath.Join(t.TempDir(), "blocked")
	if err := os.WriteFile(blocker, []byte("x"), 0600); err != nil {
		t.Fatal(err)
	}
	paths.SetRoot(blocker)
	command("player.setSkipRange", "e1", "episode", "intro_end", 120, 1400, false)
	paths.SetRoot(root)
	check(get("e1", 1400), "intro", 20, 90)
}

func TestSkipMarkerIdentityAndLegacy(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Path[strings.LastIndex(r.URL.Path, "/")+1:]
		it := map[string]any{"Id": id, "Type": "Episode"}
		switch id {
		case "season-only":
			it["SeasonId"] = "known"
		case "special-a", "special-b":
			it["SeriesId"] = "series"
			it["ParentIndexNumber"] = 0
		case "movie":
			it["Type"] = "Movie"
		}
		_ = json.NewEncoder(w).Encode(it)
	}))
	defer server.Close()
	keys := func(id string) map[string]string {
		k, _ := skipKeysOf(context.Background(), &emby.Session{Server: server.URL, Token: "tk", UserID: "u"}, id, 100)
		return k
	}
	if keys("season-only")["season"] == "" {
		t.Fatal("有季ID时应能按季保存")
	}
	if keys("special-a")["season"] == "" || keys("special-a")["season"] != keys("special-b")["season"] {
		t.Fatal("特别篇季号0未按季共享")
	}
	if keys("movie")["season"] != "" || keys("unknown")["season"] != "" {
		t.Fatal("缺季身份不应推断季")
	}
	if keys("special-a")["episode"] == keys("special-b")["episode"] {
		t.Fatal("本集身份混用")
	}
	_, outro := skipRanges(config.SkipRange{OutroLen: 120, OutroStart: 50, OutroEnd: 90}, 100)
	if outro == nil || outro.Start != 50 || outro.End != 90 {
		t.Fatal("旧长度无效时丢失绝对片尾")
	}
	_, outro = skipRanges(config.SkipRange{OutroLen: 100, OutroEndOffset: 20}, 100)
	if outro == nil || outro.Start != 0 || outro.End != 80 {
		t.Fatal("零起点片尾无法使用")
	}
}
