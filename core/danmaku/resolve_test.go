package danmaku

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"sync/atomic"
	"testing"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/paths"
)

func isolatedSources(t *testing.T, sources []SourceConfig) {
	t.Helper()
	oldRoot := paths.Root()
	paths.SetRoot(t.TempDir())
	t.Cleanup(func() { paths.SetRoot(oldRoot) })
	c, err := config.Load()
	if err != nil {
		t.Fatal(err)
	}
	old := c.DanmakuSources
	t.Cleanup(func() { c.DanmakuSources = old })
	c.DanmakuSources, _ = json.Marshal(append([]SourceConfig{{ID: OfficialSourceID, Official: true, Disabled: true}}, sources...))
}

func TestResolvePriorityFailureAndStop(t *testing.T) {
	var first, second, third atomic.Int32
	handler := func(count *atomic.Int32, fail bool) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			count.Add(1)
			if fail {
				w.WriteHeader(503)
				return
			}
			if r.URL.Path == "/api/v2/comment/episode-x" {
				_, _ = w.Write([]byte(`{"comments":[{"p":"1,1,16777215,u","m":"正确弹幕"}]}`))
				return
			}
			_, _ = w.Write([]byte(`{"animes":[{"animeId":"a","animeTitle":"测试番","episodes":[{"episodeId":"episode-x","episodeTitle":"第一集","episodeNumber":"1"}]}]}`))
		}
	}
	one := httptest.NewServer(handler(&first, true))
	defer one.Close()
	two := httptest.NewServer(handler(&second, false))
	defer two.Close()
	three := httptest.NewServer(handler(&third, false))
	defer three.Close()
	isolatedSources(t, []SourceConfig{{ID: "one", Name: "故障源", APIURL: one.URL}, {ID: "two", Name: "可用源", APIURL: two.URL}, {ID: "three", Name: "备用源", APIURL: three.URL}})
	r, err := resolve(context.Background(), map[string]any{"input": map[string]any{"title": "测试番", "episode_no": 1}})
	if err != nil || r.Match == nil || r.Match.SourceID != "two" || first.Load() == 0 || second.Load() == 0 || third.Load() != 0 {
		t.Fatalf("优先级失败: %+v %v requests=%d/%d/%d", r, err, first.Load(), second.Load(), third.Load())
	}
}

func TestRememberedUsesRealEpisodeListAndAccountScope(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"bangumi":{"episodes":[{"episodeId":"11","episodeNumber":"1"},{"episodeId":"12","episodeNumber":"SP1"},{"episodeId":"95","episodeNumber":2}]}}`))
	}))
	defer srv.Close()
	cfg := SourceConfig{ID: "source", APIURL: srv.URL, Name: "源"}
	isolatedSources(t, []SourceConfig{cfg})
	ep := int64(1)
	in := &MatchInput{Title: "作品", ItemID: "item1", SeriesID: "series", SeasonID: "season", EpisodeNo: &ep}
	a := map[string]any{"server": "server-a", "user_id": "user-a"}
	c := MatchCandidate{SourceID: cfg.ID, AnimeID: "anime", EpisodeID: "11"}
	if err := remember(context.Background(), a, in, c, &cfg); err != nil {
		t.Fatal(err)
	}
	ep2 := int64(2)
	next := *in
	next.ItemID = "item2"
	next.EpisodeNo = &ep2
	got, err := remembered(context.Background(), a, &next, []SourceConfig{cfg})
	if err != nil || got == nil || got.EpisodeID != "95" {
		t.Fatalf("应读真实集表: %+v %v", got, err)
	}
	a["user_id"] = "user-b"
	if got, err = remembered(context.Background(), a, in, []SourceConfig{cfg}); err != nil || got != nil {
		t.Fatalf("跨账号记忆: %+v %v", got, err)
	}
	a["user_id"] = "user-a"
	cfg.Token = "changed"
	if got, err = remembered(context.Background(), a, in, []SourceConfig{cfg}); err != nil || got != nil {
		t.Fatalf("源改动仍复用: %+v %v", got, err)
	}
}

func TestMemoryCorruptionAndCancellationNeverOverwrite(t *testing.T) {
	isolatedSources(t, nil)
	bad := []byte(`{"broken":`)
	if err := os.WriteFile(paths.DanmakuMatchesFile(), bad, 0600); err != nil {
		t.Fatal(err)
	}
	in := &MatchInput{Title: "作品", ItemID: "item"}
	a := map[string]any{"server": "server", "user_id": "user"}
	cfg := SourceConfig{ID: "s"}
	if err := remember(context.Background(), a, in, MatchCandidate{}, &cfg); err == nil {
		t.Fatal("损坏文件不应覆盖")
	}
	got, _ := os.ReadFile(paths.DanmakuMatchesFile())
	if string(got) != string(bad) {
		t.Fatal("损坏文件已改变")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := remember(ctx, a, in, MatchCandidate{}, &cfg); err == nil {
		t.Fatal("取消后不应保存")
	}
}

func TestStrictEpisodeNumber(t *testing.T) {
	n := int64(1)
	for _, raw := range []string{"SP1", "1.5", "OVA1", "预告1"} {
		ep := Episode{EpisodeNumber: &raw}
		if episodeMatches(&ep, &n) {
			t.Fatalf("%s 被误配", raw)
		}
	}
	if pickEpisode([]Episode{{EpisodeID: "wrong"}}, &n) != nil {
		t.Fatal("不得按位置猜集号")
	}
}

func TestLongEpisodeCacheKeysDoNotCollide(t *testing.T) {
	prefix := string(make([]byte, 140))
	if cachePath("source-fingerprint", prefix+"a") == cachePath("source-fingerprint", prefix+"b") {
		t.Fatal("长分集 ID 的缓存键碰撞")
	}
}

func TestSourceProbeRejectsWrongProtocolAndHTTPError(t *testing.T) {
	registerSourceTest()
	for _, tc := range []struct {
		name      string
		status    int
		body      string
		wantError bool
	}{
		{"empty results", 200, `{"animes":[]}`, false},
		{"wrong protocol", 200, `{}`, true},
		{"unauthorized", 401, `{"animes":[]}`, true},
		{"quota", 200, `{"errorCode":429,"errorMessage":"配额不足"}`, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tc.status)
				_, _ = w.Write([]byte(tc.body))
			}))
			defer srv.Close()
			_, err := bus.Invoke(context.Background(), "danmaku.testSource", map[string]any{"source": SourceConfig{ID: "test", Name: "测试", APIURL: srv.URL}})
			if (err != nil) != tc.wantError {
				t.Fatalf("测试结果不符: %v", err)
			}
		})
	}
}
