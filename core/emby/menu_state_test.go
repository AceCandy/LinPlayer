package emby

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestMenuUserState(t *testing.T) {
	for _, state := range []string{`{"IsFavorite":true,"Played":true}`, `{"IsFavorite":false,"Played":false}`, `{}`} {
		t.Run(state, func(t *testing.T) {
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Write([]byte(`{"Items":[{"Id":"s1","Type":"Season","UserData":` + state + `}],"TotalRecordCount":1}`))
			}))
			defer up.Close()
			c, s := NewClient("test"), &Session{Server: up.URL, UserID: "u"}
			page, err := c.SeasonEpisodes(context.Background(), s, "series", 0, 10)
			if err != nil {
				t.Fatal(err)
			}
			seasons, err := c.Seasons(context.Background(), s, "series")
			if err != nil {
				t.Fatal(err)
			}
			var source map[string]any
			json.Unmarshal([]byte(state), &source)
			for key, value := range map[string]any{"is_favorite": page.Items[0], "played": seasons[0]} {
				b, _ := json.Marshal(value)
				var got map[string]any
				json.Unmarshal(b, &got)
				sourceKey := "IsFavorite"
				if key == "played" {
					sourceKey = "Played"
				}
				if got[key] != source[sourceKey] {
					t.Errorf("%s: got %v want %v", key, got[key], source[sourceKey])
				}
			}
		})
	}
}
