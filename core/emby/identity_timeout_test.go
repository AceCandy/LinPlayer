package emby

import (
	"context"
	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/httpx"
	"net/http"
	"net/http/httptest"
	"testing"
)

func Test重新登录接受备用线路且保留账号身份(t *testing.T) {
	freshConfig(t)
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/Users/AuthenticateByName" {
			t.Errorf("错误登录端点: %s", r.URL.Path)
		}
		_, _ = w.Write([]byte(`{"AccessToken":"new","User":{"Id":"u","Name":"user"}}`))
	}))
	defer up.Close()
	c := config.Current()
	c.Upsert(config.Account{Server: "https://primary.example.test", Token: "old", Lines: []config.ServerLine{{URL: up.URL}}})
	_, err := bus.Invoke(context.Background(), "emby.relogin", map[string]any{"server_id": up.URL, "username": "user"})
	if err != nil {
		t.Fatal(err)
	}
	if len(c.AccountList) != 1 || c.AccountList[0].Server != "https://primary.example.test" || c.AccountList[0].Token != "new" {
		t.Fatal("重登改变了账号身份或未更新凭据")
	}
}

func TestEmby复用空闲超时出口(t *testing.T) {
	c := NewClient("test")
	if c.HTTP != httpx.EmbyClient() || c.HTTP.Timeout != 0 {
		t.Fatal("Emby 未复用空闲超时出口")
	}
}
