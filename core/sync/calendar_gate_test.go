package sync

import (
	"context"
	"net/http"
	"net/http/httptest"
	gosync "sync"
	"testing"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/paths"
)

var regOnce gosync.Once

// registerOnce 整个包只能注册一次(bus.Register 重复注册 panic)。
func withCommands(t *testing.T) {
	t.Helper()
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	regOnce.Do(func() { RegisterCommands("test") })
}

// 无订单也能进入日历处理器；上游账号鉴权、参数校验和提醒设置仍然生效。
func TestCalendarWithoutSponsorOrder(t *testing.T) {
	withCommands(t)
	for _, cmd := range []string{"sync.traktCalendar", "sync.bangumiCalendar"} {
		_, err := bus.Invoke(context.Background(), cmd, map[string]any{"only_mine": true})
		if e, ok := err.(*bus.Err); !ok || e.Code != bus.EAuth {
			t.Fatalf("%s 应只要求上游账号，实际 %v", cmd, err)
		}
	}
	for _, cmd := range []string{"sync.calendarLibrary", "sync.calendarDue"} {
		_, err := bus.Invoke(context.Background(), cmd, map[string]any{"entries": []any{}})
		if err != nil {
			t.Fatalf("%s 无订单也应能调用: %v", cmd, err)
		}
	}
	_, err := bus.Invoke(context.Background(), "sync.calendarLibrary", map[string]any{"entries": "invalid"})
	if e, ok := err.(*bus.Err); !ok || e.Code != bus.EInvalid {
		t.Fatalf("无订单仍应校验 entries，实际 %v", err)
	}
}

func TestBangumiCalendarRequiresAccount(t *testing.T) {
	withCommands(t)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("未登录不得访问上游")
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()
	defer setBangumiBaseForTest(srv.URL)()
	for _, args := range []map[string]any{{}, {"only_mine": false}} {
		_, err := bus.Invoke(t.Context(), "sync.bangumiCalendar", args)
		if e, ok := err.(*bus.Err); !ok || e.Code != bus.EAuth {
			t.Fatalf("旧匿名日历入口应要求账号，实际 %v", err)
		}
	}
	if got := BangumiCalendar(t.Context(), nil); got == nil || len(got) != 0 {
		t.Fatalf("无账号内部调用应返回空数组，实际 %v", got)
	}
}

func TestPersonalCalendarWithoutSponsorOrder(t *testing.T) {
	withCommands(t)
	defer calendarServer(t, calendarFixture)()
	if err := Save("bangumi", &Account{Service: "bangumi", AccessToken: "test-token"}); err != nil {
		t.Fatal(err)
	}
	value, err := bus.Invoke(t.Context(), "sync.bangumiCalendar", map[string]any{})
	if err != nil {
		t.Fatal(err)
	}
	entries, ok := value.([]CalendarEntry)
	if !ok || len(entries) != 3 {
		t.Fatalf("无订单应返回个人在看放送表，实际 %v", value)
	}
	for _, entry := range entries {
		if entry.BangumiID == nil || *entry.BangumiID == 999 {
			t.Fatalf("个人日历包含未收藏条目: %v", entry)
		}
	}
}
