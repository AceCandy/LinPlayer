package statefile

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"os"
)

// EnsurePrivacy 在全新安装先建立空名单。已有配置缺名单属于恢复失败，不能放开过滤。
func EnsurePrivacy(s Store) error {
	if _, err := s.Read("blocklist.json"); err == nil {
		return nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if _, err := s.Read("config.json"); err == nil {
		return errors.New("必要隐私状态缺失")
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return s.Write("blocklist.json", []byte("[]"))
}

func NewDeviceID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return "linplayer-" + hex.EncodeToString(b[:]), nil
}
