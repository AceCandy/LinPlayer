package prefs

import (
	"testing"

	"linplayer/core/config"
)

func Test主服绑定用户线路与删除不迁移(t *testing.T) {
	setup(t)
	c := config.Current()
	c.Upsert(config.Account{Server: "https://primary.invalid", UserID: "user", Token: "private"})
	c.Upsert(config.Account{Server: "https://secondary.invalid", UserID: "another", Token: "private"})
	if r := call(t, 801, "prefs.setPrimaryProgressServer", map[string]any{"server_id": "missing"}); r.OK {
		t.Fatal("missing account accepted")
	}
	r := call(t, 802, "prefs.setPrimaryProgressServer", map[string]any{"server_id": "https://primary.invalid"})
	if !r.OK || r.Data["server"] != "https://primary.invalid" || r.Data["user_id"] != "user" {
		t.Fatalf("binding=%+v", r)
	}
	if _, ok := r.Data["token"]; ok {
		t.Fatal("credentials exposed")
	}
	if c.PrimaryProgressAccount().Server != "https://primary.invalid" {
		t.Fatal("followed active account")
	}
	c.Find("https://primary.invalid").UserID = "relogged"
	if c.PrimaryProgressAccount() != nil {
		t.Fatal("relogin switched primary identity")
	}
	c.Remove("https://primary.invalid")
	if c.PrefsOf().PrimaryProgressServer != nil {
		t.Fatal("deleted primary was retained")
	}
	if r := call(t, 803, "prefs.setPrimaryProgressServer", map[string]any{"server_id": ""}); !r.OK || r.Data["server"] != "" {
		t.Fatalf("disable=%+v", r)
	}
}
