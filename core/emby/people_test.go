package emby

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

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
