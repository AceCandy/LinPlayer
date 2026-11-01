package companion

import (
	"context"
	"testing"
	"time"

	"linplayer/core/bus"
)

func setupHost(t *testing.T) {
	t.Helper()
	SetHostPlatform("tvos")
	revokeHost()
	drain()
	t.Cleanup(func() {
		Close()
		SetHostPlatform("")
		host.Lock()
		host.private = false
		host.Unlock()
		drain()
	})
}

func TestHostReplyWaitsAndSeparatesConcurrentRequests(t *testing.T) {
	setupHost(t)
	first, second := make(chan hostResult, 1), make(chan hostResult, 1)
	go func() { value, err := requestHost(t.Context(), "state", nil); first <- hostResult{value, err} }()
	a := waitEvent("companion.request", time.Second)
	if a == nil {
		t.Fatal("宿主未收到状态请求")
	}
	select {
	case <-first:
		t.Fatal("宿主未执行就返回成功")
	default:
	}
	go func() {
		value, err := requestHost(t.Context(), "player.seek", map[string]any{"pos": 12.0})
		second <- hostResult{value, err}
	}()
	b := waitEvent("companion.request", time.Second)
	if b == nil || a["request_id"] == b["request_id"] {
		t.Fatal("并发请求身份错误")
	}
	invoke(t, "companion.hostReply", map[string]any{"request_id": b["request_id"], "data": "seeked"})
	if result := <-second; result.err != nil || result.data != "seeked" {
		t.Fatal(result)
	}
	invoke(t, "companion.hostReply", map[string]any{"request_id": a["request_id"], "error": "无播放器"})
	if result := <-first; result.err == nil {
		t.Fatal("宿主失败被忽略")
	}
	if _, err := bus.Invoke(t.Context(), "companion.hostReply", map[string]any{"request_id": a["request_id"], "data": true}); err == nil {
		t.Fatal("重复应答未拒绝")
	}
}

func TestHostCancellationAndRevocationRejectQueuedActions(t *testing.T) {
	setupHost(t)
	for _, revoke := range []bool{false, true} {
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan error, 1)
		go func() { _, err := requestHost(ctx, "key", map[string]any{"key": "ok"}); done <- err }()
		event := waitEvent("companion.request", time.Second)
		if event == nil {
			cancel()
			t.Fatal("未投递请求")
		}
		if revoke {
			revokeHost()
		} else {
			cancel()
		}
		cancelled := waitEvent("companion.cancel", time.Second)
		if cancelled == nil || cancelled["request_id"] != event["request_id"] {
			t.Fatal("宿主取消事件未投递")
		}
		select {
		case err := <-done:
			if err == nil {
				t.Fatal("撤销后仍然成功")
			}
		case <-time.After(time.Second):
			t.Fatal("未解除等待")
		}
		cancel()
		if _, err := bus.Invoke(t.Context(), "companion.hostReply", map[string]any{"request_id": event["request_id"], "validate": true}); err == nil {
			t.Fatal("已撤销事件仍可执行")
		}
	}
}

func TestPrivateHostStopsHTTPAndRejectsRequests(t *testing.T) {
	setupHost(t)
	reopen(t)
	result := invoke(t, "companion.hostSession", map[string]any{"private": true})
	if result["epoch"] == "" || Status()["running"] != false {
		t.Fatal("隐私切换未撤销监听")
	}
	if _, err := requestHost(t.Context(), "state", nil); err == nil {
		t.Fatal("隐私空间仍开放状态")
	}
	invoke(t, "companion.hostSession", map[string]any{"private": false})
	if Status()["running"] != true {
		t.Fatal("退出隐私空间未恢复原开关")
	}
}

func TestBackupTransferIsExplicitTemporaryAndCannotReadPaths(t *testing.T) {
	setupHost(t)
	invoke(t, "companion.setEnabled", map[string]any{"enabled": false})
	if hostBackupCommand("prefs.backupExport") {
		t.Fatal("未授权即开放备份")
	}
	invoke(t, "companion.hostSession", map[string]any{"private": false, "backup": true})
	if Status()["running"] != true || Status()["enabled"] != false {
		t.Fatal("临时传输改变了常驻遥控开关")
	}
	url := Status()["url"].(string)
	for _, name := range []string{"prefs.backupExport", "prefs.backupPreview", "prefs.backupImport"} {
		code, _ := do(t, "POST", url+"/api/"+name, `{"path":"/fixture/private"}`)
		if code != 403 {
			t.Fatalf("%s 开放了路径或直接导入: %d", name, code)
		}
	}
	invoke(t, "companion.hostSession", map[string]any{"private": false})
	if Status()["running"] != false || hostBackupCommand("prefs.backupPreview") {
		t.Fatal("离开传输没有撤销授权")
	}
}
