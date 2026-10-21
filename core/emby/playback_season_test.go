package emby

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"

	"linplayer/core/bus"
)

func TestPlaybackSeasonUsesCurrentSeasonAndCompletePages(t *testing.T) {
	for _, tc := range []struct {
		name                   string
		seasonID               string
		total                  int
		repeat, missing, early bool
		omitTotal              bool
	}{
		{name: "跨页未知总数"}, {name: "季接口混入其它季", seasonID: "season"}, {name: "缺少总数", omitTotal: true}, {name: "重复页", total: 3, repeat: true}, {name: "当前集缺失", total: 1, missing: true}, {name: "提前空页", total: 3, early: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if strings.HasSuffix(r.URL.Path, "/Items/current") {
					json.NewEncoder(w).Encode(map[string]any{"Id": "current", "Name": "当前集", "Type": "Episode", "SeasonId": tc.seasonID, "SeriesId": "series", "ParentIndexNumber": 2, "ProviderIds": map[string]string{"Bangumi": "1"}})
					return
				}
				parent := "series"
				if tc.seasonID != "" {
					parent = tc.seasonID
				}
				if r.URL.Query().Get("ParentId") != parent {
					http.NotFound(w, r)
					return
				}
				calls++
				rows := []any{}
				if calls == 1 {
					rows = append(rows, map[string]any{"Id": "other-season", "Type": "Episode", "ParentIndexNumber": 1, "IndexNumber": 1})
				}
				if calls == 2 && !tc.early {
					rows = append(rows, map[string]any{"Id": "current", "Type": "Episode", "ParentIndexNumber": 2, "IndexNumber": 2})
				}
				if tc.repeat {
					rows = []any{map[string]any{"Id": "same", "Type": "Episode", "ParentIndexNumber": 2, "IndexNumber": 1}}
				}
				response := map[string]any{"Items": rows}
				if !tc.omitTotal {
					response["TotalRecordCount"] = tc.total
				}
				json.NewEncoder(w).Encode(response)
			}))
			defer server.Close()
			client := NewClient("fixture")
			got, err := client.PlaybackSeason(context.Background(), &Session{Server: server.URL, UserID: "u", Token: "fixture"}, "current")
			if tc.repeat || tc.missing || tc.early {
				if err == nil {
					t.Fatal("无效队列成功", got)
				}
				return
			}
			if err != nil || len(got) != 1 || got[0].ID != "current" || calls != 3 {
				t.Fatal(got, err, calls)
			}
		})
	}
}

func TestPlaybackSeasonBeyondTwoHundredWithoutTotal(t *testing.T) {
	offsets := []int{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/Items/e201") {
			json.NewEncoder(w).Encode(map[string]any{"Id": "e201", "Type": "Episode", "SeasonId": "season", "ParentIndexNumber": 1, "ProviderIds": map[string]string{"Bangumi": "1"}})
			return
		}
		start, _ := strconv.Atoi(r.URL.Query().Get("StartIndex"))
		limit, _ := strconv.Atoi(r.URL.Query().Get("Limit"))
		if r.URL.Query().Get("ParentId") != "season" || limit <= 0 {
			t.Error("分页参数丢失")
			http.Error(w, "query", 400)
			return
		}
		offsets = append(offsets, start)
		rows := []any{}
		for index := start; index < 205 && index < start+limit; index++ {
			rows = append(rows, map[string]any{"Id": fmt.Sprintf("e%d", index+1), "Type": "Episode", "ParentIndexNumber": 1, "IndexNumber": index + 1})
		}
		json.NewEncoder(w).Encode(map[string]any{"Items": rows})
	}))
	defer server.Close()
	result, err := NewClient("fixture").PlaybackSeason(context.Background(), &Session{Server: server.URL, UserID: "u", Token: "fixture"}, "e201")
	if err != nil || len(result) != 205 {
		t.Fatalf("完整季: count=%d err=%v", len(result), err)
	}
	if result[200].ID != "e201" || result[204].ID != "e205" || len(offsets) < 3 || offsets[len(offsets)-1] != 205 {
		t.Fatal("队列或分页游标错误", offsets)
	}
}

func TestSeriesPlaybackTarget(t *testing.T) {
	for _, tc := range []struct {
		name                                                   string
		noSeasons, allPlayed, empty, failSeasons, failEpisodes bool
		want                                                   string
	}{
		{name: "跨季首个未看", want: "season2-2"},
		{name: "全已看回首集", allPlayed: true, want: "season1-1"},
		{name: "成功空季回退剧", noSeasons: true, want: "series-2"},
		{name: "没有分集", empty: true},
		{name: "季失败不回退", failSeasons: true},
		{name: "分集失败不猜目标", failEpisodes: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			episodeCalls := 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("X-Emby-Token") != "fixture" {
					t.Error("丢失鉴权")
				}
				if r.URL.Query().Get("IncludeItemTypes") == "Season" {
					if tc.failSeasons {
						http.Error(w, "failed", 503)
						return
					}
					rows := []any{}
					if !tc.noSeasons {
						rows = []any{map[string]any{"Id": "season1"}, map[string]any{"Id": "season2"}}
					}
					json.NewEncoder(w).Encode(map[string]any{"Items": rows})
					return
				}
				episodeCalls++
				if tc.failEpisodes {
					http.Error(w, "failed", 503)
					return
				}
				parent := r.URL.Query().Get("ParentId")
				if parent == "series" && !tc.noSeasons {
					t.Error("非空季不应回退剧")
				}
				rows := []any{}
				if !tc.empty {
					for index := 1; index <= 2; index++ {
						rows = append(rows, map[string]any{"Id": fmt.Sprintf("%s-%d", parent, index), "Type": "Episode", "IndexNumber": index,
							"UserData": map[string]any{"Played": tc.allPlayed || parent == "season1" || index == 1}})
					}
				}
				json.NewEncoder(w).Encode(map[string]any{"Items": rows, "TotalRecordCount": len(rows)})
			}))
			defer server.Close()
			got, err := NewClient("fixture").SeriesPlaybackTarget(context.Background(), &Session{Server: server.URL, UserID: "u", Token: "fixture"}, "series")
			if tc.want == "" {
				if err == nil || got != nil {
					t.Fatalf("失败被当作可播: %v %v", got, err)
				}
			} else if err != nil || got == nil || got.ID != tc.want {
				t.Fatalf("目标: %v %v", got, err)
			}
			if tc.failSeasons && episodeCalls != 0 {
				t.Fatal("分季失败仍请求分集")
			}
		})
	}
}

func TestSeriesPlaybackTargetValidationAndCancellation(t *testing.T) {
	client := NewClient("fixture")
	session := &Session{Server: "http://example.invalid", UserID: "u", Token: "fixture"}
	var failure *bus.Err
	if _, err := client.SeriesPlaybackTarget(context.Background(), session, ""); !errors.As(err, &failure) || failure.Code != bus.EInvalid {
		t.Fatalf("缺少剧集编号未拒绝: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := client.SeriesPlaybackTarget(ctx, session, "series"); !errors.Is(err, context.Canceled) {
		t.Fatalf("取消未传播: %v", err)
	}
}

func TestSeriesPlaybackTargetBeyondTwoHundred(t *testing.T) {
	offsets := []int{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("IncludeItemTypes") == "Season" {
			json.NewEncoder(w).Encode(map[string]any{"Items": []any{map[string]any{"Id": "season"}}})
			return
		}
		start, _ := strconv.Atoi(r.URL.Query().Get("StartIndex"))
		limit, _ := strconv.Atoi(r.URL.Query().Get("Limit"))
		if r.URL.Query().Get("ParentId") != "season" || limit <= 0 {
			t.Error("未沿真实季分页")
			http.Error(w, "query", 400)
			return
		}
		offsets = append(offsets, start)
		rows := []any{}
		for index := start; index < 205 && index < start+limit; index++ {
			rows = append(rows, map[string]any{"Id": fmt.Sprintf("e%d", index+1), "Type": "Episode",
				"UserData": map[string]any{"Played": index < 200, "PlaybackPositionTicks": 600000000}})
		}
		json.NewEncoder(w).Encode(map[string]any{"Items": rows, "TotalRecordCount": 205})
	}))
	defer server.Close()
	target, err := NewClient("fixture").SeriesPlaybackTarget(context.Background(), &Session{Server: server.URL, UserID: "u", Token: "fixture"}, "series")
	if err != nil || target == nil || target.ID != "e201" || target.ResumeSecs != 60 || len(offsets) != 2 || offsets[1] != 200 {
		t.Fatalf("后页未看目标或续播丢失: %v %v %v", target, err, offsets)
	}
}

func TestSeriesPlaybackTargetRejectsMalformedSeasons(t *testing.T) {
	for _, body := range []string{`{}`, `{"Items":null}`, `{"Items":{}}`, `{"Items":[null]}`, `{"Items":[{}]}`} {
		t.Run(body, func(t *testing.T) {
			episodeCalls := 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Query().Get("IncludeItemTypes") == "Season" {
					_, _ = w.Write([]byte(body))
					return
				}
				episodeCalls++
				_, _ = w.Write([]byte(`{"Items":[{"Id":"guess","Type":"Episode"}],"TotalRecordCount":1}`))
			}))
			defer server.Close()
			got, err := NewClient("fixture").SeriesPlaybackTarget(context.Background(), &Session{Server: server.URL, UserID: "u", Token: "fixture"}, "series")
			if err == nil || got != nil || episodeCalls != 0 {
				t.Fatalf("异常季列表被回退或接受: target=%v err=%v calls=%d", got, err, episodeCalls)
			}
		})
	}
}

func TestSeriesPlaybackTargetRejectsMissingEpisodeArray(t *testing.T) {
	for _, body := range []string{`{}`, `{"Items":null}`} {
		t.Run(body, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch {
				case r.URL.Query().Get("IncludeItemTypes") == "Season":
					_, _ = w.Write([]byte(`{"Items":[{"Id":"season1"},{"Id":"season2"}]}`))
				case r.URL.Query().Get("ParentId") == "season1":
					_, _ = w.Write([]byte(`{"Items":[{"Id":"watched","Type":"Episode","UserData":{"Played":true}}],"TotalRecordCount":1}`))
				default:
					_, _ = w.Write([]byte(body))
				}
			}))
			defer server.Close()
			got, err := NewClient("fixture").SeriesPlaybackTarget(context.Background(), &Session{Server: server.URL, UserID: "u", Token: "fixture"}, "series")
			if err == nil || got != nil {
				t.Fatalf("缺分集数组被当成全部已看: %v %v", got, err)
			}
		})
	}
}
