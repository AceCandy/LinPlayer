package emby

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"time"

	"linplayer/core/bus"
	"linplayer/core/paths"
)

type extensionRefresh struct {
	done  chan struct{}
	token string
	caps  *extensionCapabilities
	err   error
}

func extensionCachePath(s *Session) string {
	base, _ := extensionBase(s.Server)
	hash := sha256.Sum256([]byte(base + "\x00" + s.UserID))
	return filepath.Join(paths.ExtensionCache(), hex.EncodeToString(hash[:])+".json")
}

// 日常读取只访问本地持久缓存，无过期探测；缓存不是播放授权。
func (c *Client) cachedExtensionCapabilities(s *Session) (*extensionCapabilities, error) {
	raw, err := os.ReadFile(extensionCachePath(s))
	var caps extensionCapabilities
	if err != nil || json.Unmarshal(raw, &caps) != nil || caps.Version != "1" {
		return nil, bus.NewErr(bus.EUnsupported, "尚无服务器能力缓存，请刷新普通首页或重新登录")
	}
	return &caps, nil
}

// 仅由登录及普通首页刷新调用。同一身份的并发刷新合并，失败不覆盖有效缓存。
func (c *Client) refreshExtensionCapabilities(ctx context.Context, s *Session) (*extensionCapabilities, error) {
	key := extensionCachePath(s)
	c.extMu.Lock()
	if c.extPending == nil {
		c.extPending = make(map[string]*extensionRefresh)
	}
	if pending := c.extPending[key]; pending != nil && pending.token == s.Token {
		c.extMu.Unlock()
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-pending.done:
			return pending.caps, pending.err
		}
	}
	pending := &extensionRefresh{done: make(chan struct{}), token: s.Token}
	c.extPending[key] = pending
	c.extMu.Unlock()
	requestCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	var caps extensionCapabilities
	err := c.extensionJSON(requestCtx, s, "capabilities", nil, &caps)
	if err == nil && caps.Version != "1" {
		err = bus.NewErr(bus.EUnsupported, "当前服务器不支持 MSG v1 扩展协议")
	}
	c.extMu.Lock()
	defer c.extMu.Unlock()
	if c.extPending[key] == pending {
		if err == nil && requestCtx.Err() == nil {
			raw, _ := json.Marshal(caps)
			err = os.MkdirAll(filepath.Dir(key), 0700)
			if err == nil {
				var file *os.File
				file, err = os.CreateTemp(filepath.Dir(key), ".capabilities-*")
				if err == nil {
					name := file.Name()
					_, err = file.Write(raw)
					closeErr := file.Close()
					if err == nil {
						err = closeErr
					}
					if err == nil {
						err = os.Rename(name, key)
					}
					_ = os.Remove(name)
				}
			}
			if err != nil {
				err = bus.NewErr(bus.EInternal, "保存服务器能力缓存失败")
			}
		} else if err == nil {
			err = requestCtx.Err()
		}
		delete(c.extPending, key)
	}
	if err == nil {
		pending.caps = &caps
	}
	pending.err = err
	close(pending.done)
	return pending.caps, err
}
