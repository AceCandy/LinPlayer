package account

import (
	"os"
	"testing"

	"linplayer/core/config"
	"linplayer/core/paths"
)

func TestAggregatePrimarySurvivesSwitchAndReload(t *testing.T) {
	paths.SetRoot(t.TempDir())
	c, err := config.Load()
	if err != nil {
		t.Fatal(err)
	}
	c.Upsert(config.Account{Server: "https://primary.invalid", Token: "fixture", UserID: "one"})
	c.Upsert(config.Account{Server: "https://other.invalid", Token: "fixture", UserID: "two"})
	call(t, 8101, "account.setAggregatePrimaryServer", map[string]any{"server_id": "https://primary.invalid"})
	call(t, 8102, "account.setActiveServer", map[string]any{"server_id": "https://other.invalid"})
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	rows := call(t, 8103, "account.listAccounts", nil)["list"].([]any)
	if rows[0].(map[string]any)["aggregate_primary"] != true || rows[1].(map[string]any)["active"] != true {
		t.Fatal("切服或重启改变了聚合主服")
	}
	call(t, 8104, "account.removeAccount", map[string]any{"server_id": "https://primary.invalid"})
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	rows = call(t, 8105, "account.listAccounts", nil)["list"].([]any)
	if rows[0].(map[string]any)["aggregate_primary"] == true {
		t.Fatal("删除主服后不能自动指定其它服")
	}
}

func TestAggregatePrimaryRejectsInvalidAndRollsBackSaveFailure(t *testing.T) {
	paths.SetRoot(t.TempDir())
	c, err := config.Load()
	if err != nil {
		t.Fatal(err)
	}
	c.Upsert(config.Account{Server: "https://primary.invalid", UserID: "one", Token: "fixture"})
	call(t, 8111, "account.setAggregatePrimaryServer", map[string]any{"server_id": "https://primary.invalid"})
	code, _ := callErr(t, 8112, "account.setAggregatePrimaryServer", map[string]any{"server_id": "missing"})
	if code != "E_INVALID" || c.AggregatePrimaryServer != "https://primary.invalid" {
		t.Fatal("无效主服改变了配置")
	}
	if err := os.Remove(paths.ConfigFile()); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(paths.ConfigFile(), 0700); err != nil {
		t.Fatal(err)
	}
	code, _ = callErr(t, 8113, "account.setAggregatePrimaryServer", map[string]any{"server_id": ""})
	if code != "E_INTERNAL" || c.AggregatePrimaryServer != "https://primary.invalid" {
		t.Fatal("保存失败未恢复主服")
	}
}
