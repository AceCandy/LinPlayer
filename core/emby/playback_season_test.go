package emby

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
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
