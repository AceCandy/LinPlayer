package emby

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestDoubanRatingKeepsServerFieldName(t *testing.T) {
	for _, value := range []string{"", "null", "8.6", "0", "10", "-1", "10.1"} {
		t.Run("score="+value, func(t *testing.T) {
			body := `{"Id":"m","Type":"Movie","CommunityRating":6`
			if value != "" {
				body += `,"DoubanRating":` + value
			}
			body += `}`
			var raw rawItem
			if err := json.Unmarshal([]byte(body), &raw); err != nil {
				t.Fatal(err)
			}
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				_, _ = w.Write([]byte(body))
			}))
			defer up.Close()
			detail, err := NewClient("test").Detail(context.Background(), &Session{Server: up.URL, UserID: "u"}, "m", false)
			if err != nil {
				t.Fatal(err)
			}
			for _, item := range []any{fromRaw(raw), detail} {
				b, err := json.Marshal(item)
				if err != nil {
					t.Fatal(err)
				}
				var payload map[string]json.RawMessage
				if err := json.Unmarshal(b, &payload); err != nil {
					t.Fatal(err)
				}
				if string(payload["rating"]) != "6" {
					t.Fatalf("通用评分被改变: %s", b)
				}
				_, alias := payload["douban_rating"]
				if alias {
					t.Fatalf("不应另起豆瓣字段别名: %s", b)
				}
				got, present := payload["DoubanRating"]
				known := value == "8.6" || value == "0" || value == "10"
				if present != known || known && string(got) != value {
					t.Fatalf("豆瓣字段没有正确透传: %s, want %s", b, value)
				}
			}
		})
	}
}
