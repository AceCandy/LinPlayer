package emby

import (
	"encoding/json"
	"testing"
)

func TestHomeUnplayedCountPresence(t *testing.T) {
	for _, tc := range []struct {
		raw   string
		known bool
		count int64
	}{
		{`{"Id":"s","Type":"Series"}`, false, 0},
		{`{"Id":"e","Type":"Episode","UserData":{"UnplayedItemCount":0}}`, false, 0},
		{`{"Id":"s","Type":"Series","UserData":{"Played":true}}`, false, 0},
		{`{"Id":"s","Type":"Series","UserData":{"Played":false,"UnplayedItemCount":24}}`, true, 24},
		{`{"Id":"s","Type":"Series","UserData":{"Played":false,"UnplayedItemCount":8}}`, true, 8},
		{`{"Id":"s","Type":"Series","UserData":{"Played":true,"UnplayedItemCount":0}}`, true, 0},
		{`{"Id":"s","Type":"Series","UserData":{"UnplayedItemCount":-1}}`, false, -1},
	} {
		var raw rawItem
		if err := json.Unmarshal([]byte(tc.raw), &raw); err != nil {
			t.Fatal(err)
		}
		item := fromRaw(raw)
		if item.UnplayedCountKnown != tc.known || item.UnplayedItemCount != tc.count {
			t.Fatalf("known=%v count=%d, want %v/%d", item.UnplayedCountKnown, item.UnplayedItemCount, tc.known, tc.count)
		}
	}
}

func TestHomeImageCapabilities(t *testing.T) {
	var raw rawItem
	if err := json.Unmarshal([]byte(`{"Id":"m","ImageTags":{"Logo":"art"},"BackdropImageTags":["wide"]}`), &raw); err != nil {
		t.Fatal(err)
	}
	item := fromRaw(raw)
	if !item.HasBackdrop || !item.HasLogo {
		t.Fatal("must preserve real image capabilities")
	}
	empty := fromRaw(rawItem{ID: "empty"})
	if empty.HasBackdrop || empty.HasLogo {
		t.Fatal("missing art must not be fabricated")
	}
}
