package emby

import (
	"encoding/json"
	"testing"
)

func Test字幕格式采用交付地址而非原始ASS编码(t *testing.T) {
	var ms rawMediaSource
	if err := json.Unmarshal([]byte(`{"MediaStreams":[{"Type":"Subtitle","Index":3,"IsExternal":true,"Codec":"ass","DeliveryUrl":"/Videos/item/Subtitles/3/Stream.vtt","IsDefault":true}]}`), &ms); err != nil {
		t.Fatal(err)
	}
	subs := externalSubs(&Session{Server: "", Token: "test"}, ms, "item", "media")
	b, _ := json.Marshal(subs)
	var out []map[string]any
	json.Unmarshal(b, &out)
	if len(out) != 1 || out[0]["mime_type"] != "text/vtt" {
		t.Fatalf("未按服务端交付格式解析: %s", b)
	}
}

func Test字幕交付地址扩展名未知时保留编码格式(t *testing.T) {
	if got := subtitleMimeType("/subtitle.php", "vtt"); got != "text/vtt" {
		t.Fatalf("未知扩展名覆盖了有效编码: %q", got)
	}
}
