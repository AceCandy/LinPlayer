package emby

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// 字幕轨要透出**轨道真名**,不是服务器拼的「语言 + 格式」。
//
// ☠ Emby 的 `DisplayTitle` 长得像名字(「Chinese - PGS」)但不是名字:
// 压制组写的「简体中文特效」在 `Title` 里。上一版只映射 DisplayTitle,
// 于是三端的字幕列表整张表都是格式标签,谁是谁分不出来(用户 2026-09-07 第二次报)。
func Test字幕轨透出的是轨道名不是格式串(t *testing.T) {
	const raw = `{"Id":"v1","Name":"版本","MediaStreams":[
		{"Type":"Subtitle","Codec":"ass","Title":"简体中文特效","DisplayTitle":"Chinese - ASS","Language":"chi"},
		{"Type":"Subtitle","Codec":"pgs","DisplayTitle":"English - PGS","Language":"eng"}]}`
	var m rawMediaSource
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		t.Fatal(err)
	}
	v := versionFrom(m)
	if len(v.Streams) != 2 {
		t.Fatalf("要 2 条字幕轨,实得 %d", len(v.Streams))
	}
	if v.Streams[0].Title == nil || *v.Streams[0].Title != "简体中文特效" {
		t.Fatalf("第一条要透出 Title,实得 %v", deref(v.Streams[0].Title))
	}
	// 没有 Title 的那条要留空,**不许拿 DisplayTitle 冒充** —— 由 UI 自己决定怎么回落
	if v.Streams[1].Title != nil {
		t.Fatalf("没有 Title 的轨该是 nil,实得 %v", *v.Streams[1].Title)
	}
	if v.Streams[1].DisplayTitle == nil || *v.Streams[1].DisplayTitle != "English - PGS" {
		t.Fatalf("DisplayTitle 仍然要在,实得 %v", deref(v.Streams[1].DisplayTitle))
	}
}

func Test媒体信息文件与轨道扩展字段透传(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"MediaSources":[{"Id":"v1","Path":"/Videos/fixture/stream.mp4","DateCreated":"2026-09-30T02:36:00Z","MediaStreams":[{"Type":"Video","Codec":"h264","BitDepth":8,"ColorSpace":"bt709","PixelFormat":"yuv420p","IsForced":false}]}]}`))
	}))
	defer up.Close()
	vs, err := NewClient("test").MediaVersions(context.Background(),
		&Session{Server: up.URL, UserID: "u", Token: "t"}, "m1", "")
	if err != nil {
		t.Fatal(err)
	}
	b, err := json.Marshal(vs)
	if err != nil {
		t.Fatal(err)
	}
	var payload []map[string]json.RawMessage
	if err := json.Unmarshal(b, &payload); err != nil {
		t.Fatal(err)
	}
	if string(payload[0]["path"]) != `"/Videos/fixture/stream.mp4"` {
		t.Fatal("未透传媒体路径")
	}
	if string(payload[0]["date_created"]) != `"2026-09-30T02:36:00Z"` {
		t.Fatal("未透传添加时间")
	}
	var streams []map[string]json.RawMessage
	if err := json.Unmarshal(payload[0]["streams"], &streams); err != nil {
		t.Fatal(err)
	}
	for key, want := range map[string]string{"bit_depth": "8", "color_space": `"bt709"`, "pixel_format": `"yuv420p"`, "is_forced": "false"} {
		if string(streams[0][key]) != want {
			t.Fatalf("%s 未正确透传", key)
		}
	}
	// 缺字段时仍保持旧 JSON，不用零值冒充已获取的媒体参数。
	var raw rawMediaSource
	if err := json.Unmarshal([]byte(`{"Id":"v2","MediaStreams":[{"Type":"Video"}]}`), &raw); err != nil {
		t.Fatal(err)
	}
	b, _ = json.Marshal(versionFrom(raw))
	var old map[string]json.RawMessage
	if err := json.Unmarshal(b, &old); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"path", "date_created"} {
		if _, ok := old[key]; ok {
			t.Fatalf("缺字段不应输出 %s", key)
		}
	}
	streams = nil
	if err := json.Unmarshal(old["streams"], &streams); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"bit_depth", "color_space", "pixel_format", "is_forced"} {
		if _, ok := streams[0][key]; ok {
			t.Fatalf("缺字段不应输出 %s", key)
		}
	}
}
