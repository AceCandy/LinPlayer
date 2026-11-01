package companion

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"sync"
	"time"

	"linplayer/core/bus"
)

var host struct {
	sync.Mutex
	platform string
	private  bool
	backup   bool
	epoch    string
	pending  map[string]*hostPending
}

type hostPending struct {
	context context.Context
	cancel  context.CancelFunc
	result  chan hostResult
}

type hostResult struct {
	data any
	err  error
}

// SetHostPlatform 在开放命令前声明播放器是否由电视宿主持有。
func SetHostPlatform(platform string) { host.Lock(); host.platform = platform; host.Unlock() }
func usesHost() bool                  { host.Lock(); defer host.Unlock(); return host.platform == "tvos" }
func hostPrivate() bool {
	host.Lock()
	defer host.Unlock()
	return host.platform == "tvos" && host.private
}

func backupCommand(name string) bool {
	return name == "prefs.backupExport" || name == "prefs.backupPreview"
}

func hostBackupCommand(name string) bool {
	host.Lock()
	defer host.Unlock()
	return host.platform == "tvos" && !host.private && host.backup &&
		backupCommand(name)
}
func randomID() string { var b [16]byte; _, _ = rand.Read(b[:]); return hex.EncodeToString(b[:]) }

func revokeHost() {
	host.Lock()
	defer host.Unlock()
	for _, waiting := range host.pending {
		waiting.cancel()
		waiting.result <- hostResult{err: bus.NewErr(bus.EPermission, "遥控会话已撤销，请重新扫码")}
	}
	host.pending = make(map[string]*hostPending)
	host.backup = false
	host.epoch = randomID()
}

func requestHost(ctx context.Context, name string, args map[string]any) (any, error) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	host.Lock()
	if host.private {
		host.Unlock()
		return nil, bus.NewErr(bus.EPermission, "隐私空间不开放手机遥控")
	}
	if len(host.pending) >= 32 {
		host.Unlock()
		return nil, bus.NewErr(bus.EBusy, "遥控请求过多，请稍后重试")
	}
	if host.pending == nil {
		host.pending = make(map[string]*hostPending)
	}
	if host.epoch == "" {
		host.epoch = randomID()
	}
	id, epoch := randomID(), host.epoch
	result := make(chan hostResult, 1)
	host.pending[id] = &hostPending{context: ctx, cancel: cancel, result: result}
	host.Unlock()
	defer func() { host.Lock(); delete(host.pending, id); host.Unlock() }()
	if err := bus.EmitContext(ctx, "companion.request", map[string]any{"request_id": id, "epoch": epoch, "name": name, "args": args}); err != nil {
		return nil, err
	}
	select {
	case result := <-result:
		return result.data, result.err
	case <-ctx.Done():
		// 让 tvOS 取消仍在执行的宿主任务，避免 HTTP 超时后电视继续改变状态。
		bus.Emit("companion.cancel", map[string]any{"request_id": id}, "")
		return nil, bus.NewErr(bus.ENetwork, "电视未完成遥控操作，请重试")
	}
}

func registerHostCommands() {
	bus.Register("companion.hostSession", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		private, ok := a["private"].(bool)
		if !ok || !usesHost() {
			return nil, bus.NewErr(bus.EInvalid, "宿主会话参数无效")
		}
		host.Lock()
		host.private = private
		host.Unlock()
		backup, _ := a["backup"].(bool)
		stop()
		if !private {
			host.Lock()
			host.backup = backup
			host.Unlock()
			start()
		}
		host.Lock()
		epoch := host.epoch
		host.Unlock()
		return map[string]any{"epoch": epoch}, nil
	})
	bus.Register("companion.hostReply", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		id, _ := a["request_id"].(string)
		host.Lock()
		defer host.Unlock()
		waiting := host.pending[id]
		if waiting == nil || host.private || waiting.context.Err() != nil {
			return nil, bus.NewErr(bus.EInvalid, "遥控请求已过期")
		}
		if validate, _ := a["validate"].(bool); validate {
			return map[string]any{"valid": true, "epoch": host.epoch}, nil
		}
		delete(host.pending, id)
		var err error
		if message, _ := a["error"].(string); message != "" {
			err = bus.NewErr(bus.EInvalid, "%s", message)
		}
		waiting.result <- hostResult{data: a["data"], err: err}
		return map[string]any{"accepted": true}, nil
	})
}

// Close 撤销等待中的宿主操作并关闭手机监听。
func Close() { stop() }
