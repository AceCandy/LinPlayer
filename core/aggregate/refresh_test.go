package aggregate

import (
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

	"linplayer/core/config"
	"linplayer/core/displaycache"
)

func TestCountsRefreshKeepsResumeAndUsesSourceLine(t *testing.T) {
	c := setup(t)
	var resumeCalls atomic.Int32
	var failCounts atomic.Bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/Items/Counts" {
			if failCounts.Load() {
				w.WriteHeader(503)
				return
			}
			_, _ = w.Write([]byte(`{"MovieCount":17}`))
		} else {
			resumeCalls.Add(1)
			_, _ = w.Write([]byte(`{"Items":[{"Id":"same-id","Name":"续播","Type":"Movie"}]}`))
		}
	}))
	defer srv.Close()
	c.Upsert(config.Account{Server: "https://origin.invalid", Token: "fixture", UserID: "u", Lines: []config.ServerLine{{URL: srv.URL}}})
	var rows []map[string]any
	call(t, 8201, "emby.aggregateOverview", map[string]any{"counts_only": true}, &rows)
	if rows[0]["line_url"] != srv.URL {
		t.Fatal("图片必须返回生效线路")
	}
	if resumeCalls.Load() != 1 {
		t.Fatal("新服首次必须取得续播")
	}
	failCounts.Store(true)
	call(t, 8202, "emby.aggregateOverview", map[string]any{"counts_only": true}, &rows)
	if resumeCalls.Load() != 1 {
		t.Fatal("有快照时统计刷新不应重复请求续播")
	}
	if rows[0]["counts_error"] == nil {
		t.Fatal("统计失败不能伪装成功零值")
	}
	stored := displaycache.Snapshot("emby.aggregateOverview", nil).([]any)[0].(map[string]any)
	if stored["counts"].(map[string]any)["movie"] != float64(17) || len(stored["resume"].([]any)) != 1 {
		t.Fatal("统计失败必须保留持久统计与续播")
	}
	if stored["line_url"] != srv.URL {
		t.Fatal("快照回放必须恢复有效图片线路")
	}
	// 旧调用不传 counts_only 时仍实时更新续播。
	call(t, 8203, "emby.aggregateOverview", nil, &rows)
	if resumeCalls.Load() != 2 {
		t.Fatal("旧宿主完整刷新语义被改变")
	}
}

func TestFirstCountsFailureStaysUnknownInSnapshot(t *testing.T) {
	c := setup(t)
	srv := embyServer(t, "unknown", nil, true)
	c.Upsert(config.Account{Server: srv.URL, Token: "fixture", UserID: "u"})
	var rows []SourceOverview
	for n := int64(8301); n < 8303; n++ {
		call(t, n, "emby.aggregateOverview", map[string]any{"counts_only": true}, &rows)
		stored := displaycache.Snapshot("emby.aggregateOverview", nil).([]any)[0].(map[string]any)
		if stored["counts_known"] != false || rows[0].CountsKnown {
			t.Fatal("首次失败不能回放为已知的零")
		}
	}
}
