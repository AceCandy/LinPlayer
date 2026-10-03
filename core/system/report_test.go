package system

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
)

func Test共用隐私语料(t *testing.T) {
	b, err := os.ReadFile("../../testdata/privacy.json")
	if err != nil {
		t.Fatal(err)
	}
	var corpus struct {
		Home     string `json:"home"`
		DataRoot string `json:"data_root"`
		Cases    []struct {
			Input    string `json:"input"`
			Expected string `json:"expected"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(b, &corpus); err != nil {
		t.Fatal(err)
	}
	for _, tc := range corpus.Cases {
		if got := Scrub(tc.Input, corpus.Home, corpus.DataRoot); got != tc.Expected {
			t.Errorf("脱敏不一致: %q != %q", got, tc.Expected)
		}
	}
}

func TestScrub(t *testing.T) {
	home := `C:\Users\zhangsan`
	data := `C:\Users\zhangsan\Downloads\LinPlayer\userdata`
	cases := []struct{ in, want string }{
		{`open c:\users\zhangsan\Downloads\LinPlayer\userdata\logs\x.log`, `open <data>\logs\x.log`},
		{`read C:\Users\zhangsan\Videos\a.mkv`, `read ~\Videos\a.mkv`},
		{`GET https://emby.example.com:80/Items?api_key=SECRET&x=1`, `GET https://<host>/Items?api_key=<redacted>&x=1`},
		{`X-Emby-Token: SECRET`, `X-Emby-Token: <redacted>`},
		{`{"password":"SECRET"}`, `{"password":"<redacted>"}`},
		{`Authorization: Bearer SECRET`, `Authorization: <redacted>`},
		// 本机回环是核心层自己的本地通道,排查要看,不抹
		{`http://127.0.0.1:41234/img/1`, `http://127.0.0.1:41234/img/1`},
	}
	for _, c := range cases {
		if got := Scrub(c.in, home, data); got != c.want {
			t.Errorf("Scrub(%q)\n got  %q\n want %q", c.in, got, c.want)
		}
		if strings.Contains(Scrub(c.in, home, data), "SECRET") {
			t.Errorf("凭据漏出去了: %q", c.in)
		}
	}
}

func TestBuildReportTruncatesLogTail(t *testing.T) {
	log := strings.Repeat("a", maxLogBytes) + "尾巴"
	r := buildReport("x", "", "", log)
	if r.Kind != "feedback" && r.Kind != "x" {
		t.Fatalf("kind=%q", r.Kind)
	}
	if !strings.HasSuffix(r.Log, "尾巴") || len(r.Log) > maxLogBytes+64 {
		t.Fatalf("日志该留尾部并截到上限,len=%d", len(r.Log))
	}
}

func Test报告截断不能截掉脱敏参数名(t *testing.T) {
	r := buildReport("feedback", "", "", "api_key="+strings.Repeat("SECRET", maxLogBytes))
	if strings.Contains(r.Log, "SECRET") {
		t.Fatal("日志截断后丢失参数名,令牌尾部外泄")
	}
}
