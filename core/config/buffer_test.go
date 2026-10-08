package config

import (
	"encoding/json"
	"testing"
)

// 旧配置自动，坏目标回自动，合法目标与未知配置往返保留。
func TestBufferTarget配置兼容(t *testing.T) {
	for _, input := range []string{`{}`, `{"buffer_target_bytes":-1}`, `{"buffer_target_bytes":1}`, `{"buffer_target_bytes":900000000}`} {
		if got := ParsePrefs(json.RawMessage(input)).BufferTargetBytes; got != 0 {
			t.Fatalf("应自动: %d", got)
		}
	}
	p := ParsePrefs(json.RawMessage(`{"buffer_target_bytes":134217728,"future_buffer_mode":"keep"}`))
	raw, err := json.Marshal(p)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err = json.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	if got["buffer_target_bytes"] != float64(134217728) || got["future_buffer_mode"] != "keep" {
		t.Fatalf("丢失配置: %s", raw)
	}
}
