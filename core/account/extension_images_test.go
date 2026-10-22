package account

import (
	"bytes"
	"encoding/json"
	"image"
	"image/png"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"

	"linplayer/core/config"
	"linplayer/core/net/localserve"
	"linplayer/core/paths"
)

func TestCalendarImageSurvivesAccountAllowlistRefresh(t *testing.T) {
	paths.SetRoot(t.TempDir())
	c, err := config.Load()
	if err != nil {
		t.Fatal(err)
	}
	var picture bytes.Buffer
	if err := png.Encode(&picture, image.NewRGBA(image.Rect(0, 0, 2, 2))); err != nil {
		t.Fatal(err)
	}
	images := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Emby-Token") != "" || r.Header.Get("Authorization") != "" {
			t.Error("第三方图片收到服务器凭据")
		}
		if r.URL.RawQuery != "signed=fixture" {
			t.Error("修改了第三方图片查询参数")
		}
		_, _ = w.Write(picture.Bytes())
	}))
	defer images.Close()
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Emby-Token") != "fixture" {
			t.Error("扩展请求未鉴权")
		}
		if r.URL.Path == "/emby-ext/v1/capabilities" {
			_, _ = w.Write([]byte(`{"version":"1","calendar":{"enabled":true}}`))
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"items": []any{map[string]any{
			"source": "bangumi", "bangumi_id": 42, "media_type": "anime", "title": "日历作品", "weekday": 1,
			"poster_url": images.URL + "/poster.png?signed=fixture",
		}}})
	}))
	defer upstream.Close()
	c.Upsert(config.Account{Server: upstream.URL, Token: "fixture", UserID: "user"})
	local, err := localserve.Start()
	if err != nil {
		t.Fatal(err)
	}
	defer local.Close()
	localserve.SetDefault(local)
	defer localserve.SetDefault(nil)
	SyncImageAllowlist()
	call(t, 8400, "emby.extCapabilities", map[string]any{"server_id": upstream.URL, "refresh": true})
	rows := call(t, 8401, "emby.extCalendar", map[string]any{"server_id": upstream.URL})["list"].([]any)
	pictureURL := rows[0].(map[string]any)["image_url"].(string)
	// 修改账号备注、主服或当前服均会重建白名单，已展示的外部海报仍应可取。
	SyncImageAllowlist()
	req, _ := http.NewRequest(http.MethodGet, local.BaseURL()+"/img?src="+url.QueryEscape(pictureURL)+"&h=330", nil)
	req.Header.Set("X-LP-Token", local.Token)
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	data, _ := io.ReadAll(response.Body)
	if response.StatusCode != 200 || !bytes.Equal(data, picture.Bytes()) {
		t.Fatalf("图片代理失败: HTTP %d", response.StatusCode)
	}
}
