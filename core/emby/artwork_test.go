package emby

import (
	"encoding/json"
	"testing"
)

func TestArtworkParentsDoNotBecomeOwnPrimary(t *testing.T) {
	for _, input := range []string{
		`{"Id":"episode","Type":"Episode","SeasonId":"season","SeriesId":"series","ImageTags":{}}`,
		`{"Id":"episode","Type":"Episode","SeasonId":"","ImageTags":{}}`,
	} {
		var raw rawItem
		if err := json.Unmarshal([]byte(input), &raw); err != nil {
			t.Fatal(err)
		}
		item := fromRaw(raw)
		data, err := json.Marshal(item)
		if err != nil {
			t.Fatal(err)
		}
		var payload map[string]any
		if err := json.Unmarshal(data, &payload); err != nil {
			t.Fatal(err)
		}
		if item.HasPrimary {
			t.Fatal("parent images must not become episode primary")
		}
		if raw.SeasonID != nil && *raw.SeasonID != "" {
			if payload["season_id"] != "season" || payload["series_id"] != "series" {
				t.Fatalf("missing image parents: %s", data)
			}
		} else if _, ok := payload["season_id"]; ok {
			t.Fatal("empty season must remain omitted")
		}
	}
}
