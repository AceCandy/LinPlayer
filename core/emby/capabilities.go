package emby

import (
	"context"
	"time"

	"linplayer/core/bus"
)

// Capabilities 只描述已核实的兼容限制,缺省沿用其他 Emby 的既有行为。
// 当前基准为 MediaStationGo 6e20252;不能用显示名或成功空数组识别能力。
type Capabilities struct {
	Filters        bool `json:"filters"`
	HideResume     bool `json:"hide_resume"`
	Refresh        bool `json:"refresh"`
	Similar        bool `json:"similar"`
	Collections    bool `json:"collections"`
	Chapters       bool `json:"chapters"`
	ProviderLookup bool `json:"provider_lookup"`
}

type capabilityEntry struct {
	done  chan struct{}
	caps  *Capabilities
	until time.Time
}

// 同一客户端按服务器共享只读探测,不在普通列表或起播路径上追加探测。
// 失败只短暂缓存未知状态,不把未知服务器当成 MediaStationGo。
func (c *Client) capabilities(ctx context.Context, s *Session) *Capabilities {
	c.capMu.Lock()
	if c.capCache == nil {
		c.capCache = make(map[string]*capabilityEntry)
	}
	if e := c.capCache[s.Server]; e != nil && time.Now().Before(e.until) {
		c.capMu.Unlock()
		select {
		case <-e.done:
			return e.caps
		case <-ctx.Done():
			return nil
		}
	}
	// 能力元数据可丢弃,限制账号地址不断变化时的缓存规模。
	if len(c.capCache) >= 64 {
		clear(c.capCache)
	}
	e := &capabilityEntry{done: make(chan struct{}), until: time.Now().Add(time.Second)}
	c.capCache[s.Server] = e
	c.capMu.Unlock()
	pctx, cancel := context.WithTimeout(ctx, time.Second)
	info, err := c.ProbeServer(pctx, s.Server)
	cancel()
	c.capMu.Lock()
	if err == nil && info != nil && info.ID != "" {
		e.caps = capabilitiesForID(info.ID)
		e.until = time.Now().Add(5 * time.Minute)
	} else {
		e.until = time.Now().Add(10 * time.Second)
	}
	// 媒体列表可能已在探测期间确认身份,晚到的失败不能恢复成未知。
	if newer := c.capCache[s.Server]; newer != nil && newer != e {
		select {
		case <-newer.done:
			e.caps, e.until = newer.caps, newer.until
		default:
		}
	}
	close(e.done)
	c.capMu.Unlock()
	return e.caps
}

func capabilitiesForID(id string) *Capabilities {
	if id == "mediastation-go-001" {
		return &Capabilities{}
	}
	return nil
}

// 复用登录探测和详情中的固定 ServerId,不额外查询公开信息。
func (c *Client) rememberIdentity(server, id string) {
	if id == "" {
		return
	}
	e := &capabilityEntry{done: make(chan struct{}), caps: capabilitiesForID(id), until: time.Now().Add(5 * time.Minute)}
	close(e.done)
	c.capMu.Lock()
	defer c.capMu.Unlock()
	if c.capCache == nil {
		c.capCache = make(map[string]*capabilityEntry)
	}
	if len(c.capCache) >= 64 {
		clear(c.capCache)
	}
	c.capCache[server] = e
}

func unsupported(action string) error {
	return bus.NewErr(bus.EUnsupported, "当前服务器不支持"+action)
}

// ProviderLookupNotice 给名称搜索及跨服匹配明确的降级说明,不触发额外探测。
func (c *Client) ProviderLookupNotice(server string) string {
	c.capMu.Lock()
	defer c.capMu.Unlock()
	if e := c.capCache[server]; e != nil && time.Now().Before(e.until) {
		select {
		case <-e.done:
			if e.caps != nil && !e.caps.ProviderLookup {
				return "服务端不支持标识查询,名称搜索可能遗漏不同译名的条目"
			}
		default:
		}
	}
	return ""
}
