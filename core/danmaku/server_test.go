package danmaku

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestServerXML(t *testing.T) {
	items, err := parseServerXML(`<i><d p="1.234,1,25,255,0,0,0,9999999999999999999">测试&lt;&amp;</d></i>`)
	if err != nil || len(items) != 1 || items[0].Time != 1.234 || items[0].Color != 255 || items[0].Text != "测试<&" || *items[0].CID != "9999999999999999999" {
		t.Fatalf("%+v %v", items, err)
	}
	for _, s := range []string{"", "<i></i>"} {
		if x, e := parseServerXML(s); e != nil || len(x) != 0 {
			t.Fatalf("%q: %v", s, e)
		}
	}
	for _, s := range []string{"<html>error</html>", "<i>", "<i/><i/>"} {
		if _, e := parseServerXML(s); e == nil {
			t.Fatalf("accepted %q", s)
		}
	}
}

func TestResolveServerOverlay(t *testing.T) {
	for _, tc := range []struct {
		name                string
		rawFail, sourceFail bool
		want                int
	}{
		{"both", false, false, 2}, {"raw failure", true, false, 1}, {"source failure", false, true, 1}, {"both failure", true, true, 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch r.URL.Path {
				case "/emby-ext/v1/capabilities":
					if r.Header.Get("X-Emby-Token") != "test-token" {
						t.Error("missing token")
					}
					_, _ = w.Write([]byte(`{"version":"1","danmaku":{"enabled":true,"format":"bilibili_xml"}}`))
				case "/api/danmu/item/raw":
					if tc.rawFail {
						w.WriteHeader(500)
						return
					}
					_, _ = w.Write([]byte(`<i><d p="0.5,1,25,255">服务器</d></i>`))
				default:
					if tc.sourceFail {
						w.WriteHeader(503)
						return
					}
					if strings.Contains(r.URL.Path, "/comment/") {
						_, _ = w.Write([]byte(`{"comments":[{"p":"1,1,16777215,u","m":"第三方"}]}`))
						return
					}
					_, _ = w.Write([]byte(`{"animes":[{"animeId":"a","animeTitle":"测试番","episodes":[{"episodeId":"ep","episodeTitle":"第一集","episodeNumber":"1"}]}]}`))
				}
			}))
			defer srv.Close()
			isolatedSources(t, []SourceConfig{{ID: "overlay", Name: "其它源", APIURL: srv.URL}})
			result, err := resolve(context.Background(), map[string]any{"server": srv.URL, "token": "test-token", "user_id": "u", "input": map[string]any{"title": "测试番", "item_id": "item", "episode_no": 1}})
			if len(result.Items) != tc.want || (err != nil) != (tc.want == 0) {
				t.Fatalf("%+v %v", result, err)
			}
			if tc.want == 2 && (result.Items[0].Text != "服务器" || !strings.Contains(result.Match.SourceName, "服务器弹幕")) {
				t.Fatalf("%+v", result)
			}
			if !tc.sourceFail {
				manual, e := resolve(context.Background(), map[string]any{"server": srv.URL, "token": "test-token", "user_id": "u", "input": map[string]any{"title": "测试番", "item_id": "item"}, "selection": map[string]any{"source_id": "overlay", "anime_id": "a", "episode_id": "ep"}})
				if e != nil || len(manual.Items) != tc.want {
					t.Fatalf("manual: %+v %v", manual, e)
				}
				ctx, cancel := context.WithCancel(context.Background())
				cancel()
				cancelled, e := resolve(ctx, map[string]any{"server": srv.URL, "token": "test-token", "user_id": "u", "input": map[string]any{"title": "测试番", "item_id": "item"}})
				if e == nil || len(cancelled.Items) != 0 {
					t.Fatalf("cancelled: %+v %v", cancelled, e)
				}
			}
			if (tc.rawFail || tc.sourceFail) && len(result.Warnings) == 0 {
				t.Fatal("missing warning")
			}
		})
	}
}
