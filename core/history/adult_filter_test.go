package history

import (
	"encoding/json"
	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/paths"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAdultHistorySeparatesCaptureAndResume(t *testing.T) {
	store := newStore(t)
	adult, normal := true, false
	candidate := epCandidate("episode")
	scope := ScopeKey("https://example.invalid", "viewer")
	for i, classification := range []*bool{nil, &adult, &normal} {
		candidate.IsAdult = classification
		store.Capture(CaptureOpts{ScopeKey: scope, Candidate: candidate, SeriesTmdbID: sp("series"),
			PositionTicks: int64(300-i*100) * TicksPerSec, Force: true, WatchedThresholdPercent: 90})
	}
	if len(store.LoadAll()) != 3 {
		t.Fatal("不同分类的同一集互相覆盖")
	}
	for i, classification := range []*bool{&adult, &normal} {
		candidate.IsAdult = classification
		want := int64(200-i*100) * TicksPerSec
		got := store.ResolveResumeTicks(scope, candidate, sp("series"), nil, false, true)
		if got == nil || *got != want {
			t.Fatalf("本服跨分类续播: got %v want %d", got, want)
		}
		got = store.ResolveResumeTicks("another", candidate, sp("series"), nil, false, true)
		if got == nil || *got != want {
			t.Fatalf("跨服跨分类续播: got %v want %d", got, want)
		}
	}
	// 旧宿主缺省写入不能把已分类的记录吞并成未分类。
	candidate.IsAdult = nil
	store.Capture(CaptureOpts{ScopeKey: scope, Candidate: candidate, SeriesTmdbID: sp("series"), PositionTicks: 400 * TicksPerSec, Force: true})
	if len(store.LoadAll()) != 3 || len(filterRecords(store.LoadAll(), &adult)) != 1 || len(filterRecords(store.LoadAll(), &normal)) != 1 {
		t.Fatal("缺省写入破坏分类记录")
	}
}

func TestClearNormalHistoryKeepsPrivateAndUnknown(t *testing.T) {
	store := newStore(t)
	normal, adult := false, true
	for _, classification := range []*bool{nil, &normal, &adult} {
		candidate := epCandidate("episode")
		candidate.IsAdult = classification
		store.Capture(CaptureOpts{ScopeKey: "scope", Candidate: candidate, Force: true})
	}
	count, err := store.clearRecords(filterRecords(store.LoadAll(), &normal))
	if err != nil || count != 1 || len(store.LoadAll()) != 2 || len(filterRecords(store.LoadAll(), &adult)) != 1 {
		t.Fatal("普通空间清理影响了隐私或未知记录")
	}
	count, err = store.clearRecords(store.LoadAll())
	if err != nil || count != 2 || len(store.LoadAll()) != 0 {
		t.Fatal("缺省清理应保留全量语义")
	}
}

func TestHistoryUsesCurrentLibraryClassificationAndVisibility(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	adult, visible := false, true
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		rows := []any{}
		if visible {
			rows = append(rows, map[string]any{"Id": "library", "Type": "CollectionFolder", "IsAdult": adult})
		}
		json.NewEncoder(w).Encode(map[string]any{"Items": rows})
	}))
	defer server.Close()
	config.Current().AccountList = []config.Account{{Server: server.URL, UserID: "viewer", Token: "test"}}
	normal := false
	records := []Record{{RecordID: "record", ScopeKey: ScopeKey(server.URL, "viewer"), IsAdult: &normal, LibraryIDs: []string{"library"}}}
	for _, flag := range []bool{false, true} {
		adult = flag
		ctx, _ := emby.AdultFilterContext(t.Context(), map[string]any{"is_adult": flag})
		rows, err := visibleHistoryRecords(ctx, records, &flag)
		if err != nil || len(rows) != 1 {
			t.Fatal("库重新分类后未按实际库显示记录", err)
		}
	}
	// 普通查询即使遇到忽略参数的服务端，也不能显示标为成人的库记录。
	ctx, _ := emby.AdultFilterContext(t.Context(), map[string]any{"is_adult": false})
	rows, err := visibleHistoryRecords(ctx, records, &normal)
	if err != nil || len(rows) != 0 {
		t.Fatal("旧普通标签泄露成人记录", err)
	}
	visible = false
	rows, err = visibleHistoryRecords(ctx, records, &normal)
	if err != nil || len(rows) != 0 {
		t.Fatal("撤销库权限后仍显示记录", err)
	}
	if requests != 4 {
		t.Fatal("单次按库核验出现重复查询")
	}
}

func TestPrivatePlayedDoesNotReadOrWriteLocalHistory(t *testing.T) {
	previous := Shared()
	store := newStore(t)
	SetShared(store)
	defer SetShared(previous)
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { requests++; w.WriteHeader(500) }))
	defer server.Close()
	ctx, _ := emby.AdultFilterContext(t.Context(), map[string]any{"is_adult": true})
	markPlayedLocally(ctx, &emby.Session{Server: server.URL, UserID: "viewer"}, "film", true)
	if requests != 0 || len(store.LoadAll()) != 0 {
		t.Fatal("私密已看标记仍接入本地历史")
	}
}
