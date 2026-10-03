package emby

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"linplayer/core/bus"
	"linplayer/core/config"
)

func Test登录缺少身份字段不能落库(t *testing.T) {
	for _, body := range []string{`{}`, `{"AccessToken":"test"}`, `{"User":{"Id":"u"}}`, `{"AccessToken":" ","User":{"Id":"u"}}`, `{"AccessToken":"test","User":{"Id":" "}}`} {
		t.Run(body, func(t *testing.T) {
			freshConfig(t)
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte(body)) }))
			defer up.Close()
			_, err := bus.Invoke(context.Background(), "emby.login", map[string]any{
				"server": up.URL, "username": "user", "password": "", "device_id": "test",
			})
			if err == nil {
				t.Fatal("缺少有效身份却登录成功")
			}
			if len(config.Current().AccountList) != 0 || config.Current().ActiveAccount() != nil {
				t.Fatal("无效登录仍保存了账号")
			}
		})
	}
}
