package history

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"linplayer/core/emby"
)

func Test主服电影只接受完整唯一Provider匹配(t *testing.T) {
	provider := "42"
	for _, test := range []struct {
		name        string
		total       int
		ids         []string
		wrong, fail bool
		want        bool
	}{
		{"同标识不同译名", 1, []string{"a"}, false, false, true},
		{"同名不同标识", 1, []string{"a"}, true, false, false},
		{"重复候选", 2, []string{"a", "b"}, false, false, false},
		{"候选未返回完整", 2, []string{"a"}, false, false, false},
		{"候选核验失败", 2, []string{"a", "b"}, false, true, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch {
				case strings.Contains(r.URL.Path, "System/Info"):
					fmt.Fprint(w, `{"Id":"mediastation-go-001"}`)
				case r.URL.Path == "/Users/user/Items":
					if r.URL.Query().Get("EnableTotalRecordCount") != "true" {
						t.Error("uniqueness query omitted accurate total")
					}
					items := []string{}
					for _, id := range test.ids {
						items = append(items, fmt.Sprintf(`{"Id":%q,"Type":"Movie","Name":"Movie"}`, id))
					}
					fmt.Fprintf(w, `{"Items":[%s],"TotalRecordCount":%d}`, strings.Join(items, ","), test.total)
				default:
					if strings.HasSuffix(r.URL.Path, "/b") && test.fail {
						w.WriteHeader(503)
						return
					}
					id := r.URL.Path[strings.LastIndex(r.URL.Path, "/")+1:]
					tmdb := provider
					if test.wrong {
						tmdb = "different"
					}
					fmt.Fprintf(w, `{"Id":%q,"Type":"Movie","Name":"Translated","ProviderIds":{"Tmdb":%q}}`, id, tmdb)
				}
			}))
			defer up.Close()
			match, err := FindPrimaryCandidate(t.Context(), emby.NewClient("test"), &emby.Session{Server: up.URL, UserID: "user"}, Candidate{ID: "self", Type: "Movie", Name: "Movie", TmdbID: &provider}, nil)
			if (match != nil) != test.want {
				t.Fatalf("match=%+v err=%v", match, err)
			}
		})
	}
}

func Test主服分集按实际页长推进并核对剧与季集号(t *testing.T) {
	provider, name := "42", "Series"
	season, episode := int64(1), int64(2)
	for _, duplicate := range []bool{false, true} {
		t.Run(fmt.Sprint(duplicate), func(t *testing.T) {
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if strings.Contains(r.URL.Path, "System/Info") {
					fmt.Fprint(w, `{"Id":"mediastation-go-001"}`)
					return
				}
				if r.URL.Path == "/Users/user/Items" {
					switch r.URL.Query().Get("IncludeItemTypes") {
					case "Series":
						fmt.Fprint(w, `{"Items":[{"Id":"series","Type":"Series","Name":"Series","ProviderIds":{"Tmdb":"42"}}],"TotalRecordCount":1}`)
					case "Season":
						fmt.Fprint(w, `{"Items":[{"Id":"season","Type":"Season","IndexNumber":1}],"TotalRecordCount":1}`)
					case "Episode":
						if r.URL.Query().Get("EnableTotalRecordCount") != "true" {
							t.Error("episode count omitted")
						}
						index := 1
						if r.URL.Query().Get("StartIndex") == "1" || duplicate {
							index = 2
						}
						id := "first"
						if r.URL.Query().Get("StartIndex") == "1" {
							id = "second"
						}
						fmt.Fprintf(w, `{"Items":[{"Id":%q,"Type":"Episode","ParentIndexNumber":1,"IndexNumber":%d,"SeriesId":"series"}],"TotalRecordCount":2}`, id, index)
					}
					return
				}
				id := r.URL.Path[strings.LastIndex(r.URL.Path, "/")+1:]
				if id == "series" {
					fmt.Fprint(w, `{"Id":"series","Type":"Series","Name":"Series","ProviderIds":{"Tmdb":"42"}}`)
					return
				}
				fmt.Fprintf(w, `{"Id":%q,"Type":"Episode","SeriesId":"series","ParentIndexNumber":1,"IndexNumber":2}`, id)
			}))
			defer up.Close()
			match, err := FindPrimaryCandidate(t.Context(), emby.NewClient("test"), &emby.Session{Server: up.URL, UserID: "user"}, Candidate{ID: "self", Type: "Episode", SeriesName: &name, SeasonNo: &season, EpisodeNo: &episode}, &provider)
			if err != nil {
				t.Fatal(err)
			}
			if (match != nil) == duplicate {
				t.Fatalf("duplicate=%v match=%+v", duplicate, match)
			}
		})
	}
}
