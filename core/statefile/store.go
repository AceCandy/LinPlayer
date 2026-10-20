// Package statefile 为不可依赖本地文件保留的平台接入必要状态的安全存储。
// JSON、事务及错误策略仍由 config / blocklist 拥有；普通平台继续使用文件。
package statefile

import (
	"os"
	"path/filepath"
	"sync"
)

type Store interface {
	Read(name string) ([]byte, error)
	Write(name string, data []byte) error
}

var mu sync.RWMutex
var secure Store

// Install 仅在初始化、命令注册前调用；nil 恢复文件后端。
func Install(s Store) { mu.Lock(); secure = s; mu.Unlock() }

func Secure() Store { mu.RLock(); defer mu.RUnlock(); return secure }

func Read(path string) ([]byte, error) {
	if s := Secure(); s != nil {
		return s.Read(filepath.Base(path))
	}
	return os.ReadFile(path)
}
