package danmaku

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestBgmSubjectID(t *testing.T) {
	for _, s := range []string{
		"https://bgm.tv/subject/253", "https://bangumi.tv/subject/253/",
		"bgm:253", "bgm 253",
	} {
		if got := BgmSubjectID(s); got != 253 {
			t.Fatalf("%q 该抠出 253,实得 %d", s, got)
		}
	}
	// ☠ 纯数字不算:「253」多半是片名的一部分,猜错了用户看到的是另一部作品
	for _, s := range []string{"253", "全职猎人 2011", ""} {
		if got := BgmSubjectID(s); got != 0 {
			t.Fatalf("%q 不该被当成条目号,实得 %d", s, got)
		}
	}
}

func TestBgmTitlesBecomeSearchTitle(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"name": "とある科学の超電磁砲", "name_cn": "某科学的超电磁炮",
		})
	}))
	defer srv.Close()
	defer SetBgmAPIBase(srv.URL)()

	id := int64(2585)
	in := &MatchInput{Title: "某科学的超电磁炮", BgmID: &id}
	out := withBgmTitles(context.Background(), in)
	if out.Title != "とある科学の超電磁砲" {
		t.Fatalf("主标题该换成 bgm 的原名(弹幕源收的是日文名),实得 %q", out.Title)
	}
	found := false
	for _, a := range out.AltTitles {
		if a == "某科学的超电磁炮" {
			found = true
		}
	}
	if !found {
		t.Fatalf("原来的标题该降级成别名留着兜底,实得别名表 %v", out.AltTitles)
	}
}

func TestSearchGroupKeepsOrderAndCaps(t *testing.T) {
	mk := func(n int) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			list := []any{}
			for i := 0; i < n; i++ {
				list = append(list, map[string]any{
					"animeId": 1000 + i, "animeTitle": fmt.Sprintf("作品%d", i),
				})
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"animes": list})
		}
	}
	big := httptest.NewServer(mk(30))
	defer big.Close()
	small := httptest.NewServer(mk(2))
	defer small.Close()

	// 少结果的源排在前面 —— 组的顺序必须**照这个顺序**出,不许按条数重排
	cfgs := []SourceConfig{
		{ID: "few", Name: "少的", APIURL: small.URL + "/api/v2"},
		{ID: "many", Name: "多的", APIURL: big.URL + "/api/v2"},
	}
	gs := searchAllGrouped(context.Background(), cfgs, "x")
	if len(gs) != 2 || gs[0].SourceID != "few" {
		t.Fatalf("组序该是设置里的顺序(few 在前),实得 %v/%v", gs[0].SourceID, gs[1].SourceID)
	}
	if len(gs[1].Animes) != SearchGroupCap {
		t.Fatalf("一个源最多 %d 部(方便换源),实得 %d 部", SearchGroupCap, len(gs[1].Animes))
	}
}
