package emby

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"linplayer/core/blocklist"
)

func TestPersonVoiceSubtitle(t *testing.T) {
	for _, tc := range []struct{ role, want string }{
		{"voice", "配音"}, {" Voice ", "配音"}, {"(voice)", "配音"},
		{"江远 (voice)", "江远（配音）"}, {"江远（Voice）", "江远（配音）"},
		{"Voice of Reason", "Voice of Reason"}, {"voicemail", "voicemail"},
		{"角色甲", "角色甲"}, {"", ""},
	} {
		for _, typ := range []string{"Actor", "GuestStar"} {
			if got := personSubtitle(typ, tc.role); got != tc.want {
				t.Errorf("%s/%q = %q, want %q", typ, tc.role, got, tc.want)
			}
		}
	}
	if got := personSubtitle("Director", "voice"); got != "导演" {
		t.Fatal(got)
	}
}

func TestPersonItemsPageContract(t *testing.T) {
	old := blocklist.List()
	t.Cleanup(func() { blocklist.Replace(old) })
	blocklist.Replace([]blocklist.Entry{{ID: "blocked"}})
	for _, typ := range []string{"Movie", "Series"} {
		t.Run(typ, func(t *testing.T) {
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				q := r.URL.Query()
				for k, want := range map[string]string{
					"PersonIds": "person & 1", "Recursive": "true", "IncludeItemTypes": typ,
					"StartIndex": "30", "Limit": "30", "SortBy": "PremiereDate", "SortOrder": "Descending",
					"EnableTotalRecordCount": "true",
				} {
					if q.Get(k) != want {
						t.Errorf("%s = %q, want %q", k, q.Get(k), want)
					}
				}
				_ = json.NewEncoder(w).Encode(map[string]any{
					"Items":            []map[string]string{{"Id": "blocked", "Name": "屏蔽作品", "Type": typ}},
					"TotalRecordCount": 35,
				})
			}))
			defer up.Close()
			p, err := NewClient("test").PersonItemsPage(context.Background(), &Session{Server: up.URL, UserID: "u"}, "person & 1", typ, 30, 30)
			if err != nil {
				t.Fatal(err)
			}
			if len(p.Items) != 0 || p.NextIndex != 31 || !p.HasMore || p.Total == nil || *p.Total != 35 {
				t.Fatalf("filtered short page: %+v", p)
			}
		})
	}
}

func TestPersonItemsPageUnknownTotalAndErrors(t *testing.T) {
	for _, tc := range []struct {
		body string
		more bool
		next int
	}{
		{`{"Items":[{"Id":"a","Name":"A","Type":"Movie"}]}`, true, 31},
		{`{"Items":[]}`, false, 30},
		{`{"Items":[{"Id":"a","Name":"A","Type":"Movie"}],"TotalRecordCount":31}`, false, 31},
	} {
		up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte(tc.body)) }))
		p, err := NewClient("test").PersonItemsPage(context.Background(), &Session{Server: up.URL, UserID: "u"}, "p", "Movie", 30, 30)
		up.Close()
		if err != nil || p.HasMore != tc.more || p.NextIndex != tc.next {
			t.Fatalf("page=%+v err=%v", p, err)
		}
		if tc.more && p.Total != nil {
			t.Fatal("unknown total invented")
		}
	}
	c := NewClient("test")
	if _, err := c.PersonItemsPage(context.Background(), &Session{}, "", "Movie", 0, 30); err == nil {
		t.Fatal("empty person accepted")
	}
	if _, err := c.PersonItemsPage(context.Background(), &Session{}, "p", "Episode", 0, 30); err == nil {
		t.Fatal("unsupported type accepted")
	}
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusForbidden) }))
	defer up.Close()
	s := &Session{Server: up.URL, UserID: "u"}
	if _, err := c.PersonItemsPage(context.Background(), s, "p", "Movie", 0, 30); StatusOf(err) != http.StatusForbidden {
		t.Fatalf("error lost: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := c.PersonItemsPage(ctx, s, "p", "Movie", 0, 30); err == nil {
		t.Fatal("cancel ignored")
	}
}

func TestDetailPeopleCastFirstAndCrewJobs(t *testing.T) {
	input := []map[string]string{
		{"Id": "director", "Name": "导演甲", "Type": "Director", "Role": "不应当作角色"},
		{"Id": "actor1", "Name": "演员甲", "Type": "Actor", "Role": "角色甲"},
		{"Id": "writer", "Name": "编剧甲", "Type": "Writer"},
		{"Id": "guest", "Name": "客串甲", "Type": "GuestStar", "Role": "角色乙"},
		{"Id": "actor2", "Name": "演员乙", "Type": "Actor"},
		{"Id": "producer", "Name": "制片甲", "Type": "Producer"},
		{"Id": "unknown", "Name": "幕后甲", "Type": "CustomJob", "Role": "动作指导"},
		{"Id": "raw", "Name": "幕后乙", "Type": "OtherJob"},
		{"Id": "empty", "Name": "幕后丙"},
		{"Id": "skip", "Type": "Actor"},
	}
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"Id": "movie", "Type": "Movie", "People": input})
	}))
	defer up.Close()
	d, err := NewClient("test").Detail(context.Background(), &Session{Server: up.URL, UserID: "u"}, "movie", false)
	if err != nil {
		t.Fatal(err)
	}
	ids := []string{"actor1", "guest", "actor2", "director", "writer", "producer", "unknown", "raw", "empty"}
	roles := []string{"角色甲", "角色乙", "", "导演", "编剧", "制片人", "动作指导", "OtherJob", ""}
	if len(d.People) != len(ids) {
		t.Fatalf("people count = %d, want %d", len(d.People), len(ids))
	}
	for i, p := range d.People {
		if p.ID != ids[i] {
			t.Errorf("people[%d] = %s, want %s", i, p.ID, ids[i])
		}
		role := ""
		if p.Role != nil {
			role = *p.Role
		}
		if role != roles[i] {
			t.Errorf("people[%d].role = %q, want %q", i, role, roles[i])
		}
	}
}
