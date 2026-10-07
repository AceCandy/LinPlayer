package config

import (
	"sync"
	"sync/atomic"
)

var progressAccountEpoch atomic.Uint64
var progressAccountWrites sync.RWMutex

// InvalidateProgressAccount 使变更账号/主服前建立的播放绑定失效，不迁移待同步数据。
func InvalidateProgressAccount() {
	progressAccountWrites.Lock()
	defer progressAccountWrites.Unlock()
	progressAccountEpoch.Add(1)
}

// HoldProgressAccount 等待在途写入完成后才允许账号失效；成功时调用方必须释放。
func HoldProgressAccount(epoch uint64) (func(), bool) {
	progressAccountWrites.RLock()
	if epoch != progressAccountEpoch.Load() {
		progressAccountWrites.RUnlock()
		return nil, false
	}
	return progressAccountWrites.RUnlock, true
}
func ProgressAccountEpoch() uint64 { return progressAccountEpoch.Load() }

// ProgressServer 绑定主进度服的账号身份，不能只按地址跨用户复用。
type ProgressServer struct {
	Server string `json:"server"`
	UserID string `json:"user_id"`
}

func (c *AppConfig) PrimaryProgressAccount() *Account {
	binding := c.PrefsOf().PrimaryProgressServer
	if binding == nil {
		return nil
	}
	a := c.Find(binding.Server)
	if a == nil || a.IsFileBrowse() || a.UserID != binding.UserID {
		return nil
	}
	return a
}
