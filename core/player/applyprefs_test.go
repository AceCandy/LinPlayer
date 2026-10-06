package player

import (
	"testing"

	"linplayer/core/config"
)

// 安卓 mpv 内核「字幕显示出问题」那次查出来的(2026-09-17):选轨偏好从没在起播时应用过。
func TestChooseTracks起播选字幕(t *testing.T) {
	ext := []Track{
		{ID: "1", Kind: "audio"},
		{ID: "1", Kind: "sub", Title: "内封 英文", Lang: "eng"},
		{ID: "2", Kind: "sub", Title: "简体中文", External: true},
	}
	on := config.DefaultPrefs()
	off := config.DefaultPrefs()
	off.SubEnabled = false
	zh := config.DefaultPrefs()
	zh.SubRegex = "简体"

	for _, tc := range []struct {
		name   string
		tracks []Track
		p      config.Prefs
		want   string
	}{
		{"关了字幕就显式 no,不留上一片的轨", ext, off, "no"},
		{"开着且没偏好时优先简体外挂字幕", ext, on, "2"},
		{"正则优先", ext, zh, "2"},
		{"mpv 已经按 default 标记选了就不动", []Track{{ID: "1", Kind: "sub"}, {ID: "2", Kind: "sub", Selected: true}}, on, ""},
		{"没有字幕轨就不设", []Track{{ID: "1", Kind: "audio"}}, on, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got, _ := chooseTracks(tc.tracks, tc.p); got != tc.want {
				t.Fatalf("sid = %q,该是 %q", got, tc.want)
			}
		})
	}
}

func Test字幕默认简体不覆盖显式偏好(t *testing.T) {
	tracks := []Track{{ID: "1", Kind: "sub", Title: "繁體中文", Lang: "chi", Selected: true},
		{ID: "2", Kind: "sub", Title: "Simplified Chinese", Lang: "chi"},
		{ID: "3", Kind: "sub", Title: "English", Lang: "eng"}}
	p := config.DefaultPrefs()
	if sid, _ := chooseTracks(tracks, p); sid != "2" {
		t.Fatalf("默认字幕=%s", sid)
	}
	p.SubRegex = "繁體"
	if sid, _ := chooseTracks(tracks, p); sid != "1" {
		t.Fatalf("正则字幕=%s", sid)
	}
	p.SubRegex = ""
	lang := "eng"
	p.SubLang = &lang
	if sid, _ := chooseTracks(tracks, p); sid != "3" {
		t.Fatalf("语言字幕=%s", sid)
	}
	p.SubEnabled = false
	if sid, _ := chooseTracks(tracks, p); sid != "no" {
		t.Fatalf("字幕关闭=%s", sid)
	}
}
