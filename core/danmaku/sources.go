package danmaku

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"net/url"
	"strings"
	"time"

	"linplayer/core/bus"
)

const sourceTimeout = 8 * time.Second

// managedSources 返回可编辑描述符，官方凭据仅在请求时注入。
func managedSources() []SourceConfig {
	list := LoadSources()
	for _, c := range list {
		if c.ID == OfficialSourceID {
			return list
		}
	}
	return append([]SourceConfig{{ID: OfficialSourceID, Name: "弹弹Play(官方)", Official: true}}, list...)
}

func normalizeSource(c SourceConfig) (SourceConfig, error) {
	c.ID, c.Name = strings.TrimSpace(c.ID), strings.TrimSpace(c.Name)
	if c.ID == OfficialSourceID {
		return SourceConfig{ID: OfficialSourceID, Name: "弹弹Play(官方)", Official: true, Disabled: c.Disabled}, nil
	}
	if c.Official || c.ID == "" || c.Name == "" {
		return c, fmt.Errorf("弹幕源名称和标识不能为空，官方标识不可自定义")
	}
	u, err := url.Parse(strings.TrimSpace(c.APIURL))
	if err != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || u.Fragment != "" {
		return c, fmt.Errorf("请输入有效的 HTTP 或 HTTPS 弹幕 API 地址")
	}
	clean, auth, token := DeriveAuth(c.APIURL)
	c.APIURL = clean
	if c.AuthType == "" || c.AuthType == AuthNone {
		c.AuthType = auth
		if token != "" {
			c.Token = token
		}
	}
	switch c.AuthType {
	case AuthNone, AuthPathToken:
	case AuthHeaderToken, AuthQueryToken:
		if strings.TrimSpace(c.Token) == "" {
			return c, fmt.Errorf("请填写鉴权令牌")
		}
	case AuthSignature:
		if strings.TrimSpace(c.AppID) == "" || firstSecret(c.AppSecret) == "" {
			return c, fmt.Errorf("请填写 App ID 和 App Secret")
		}
	default:
		return c, fmt.Errorf("不支持的鉴权方式")
	}
	return c, nil
}

func sourceFingerprint(c *SourceConfig) string {
	// 名称、排序与启停不影响内容；地址或凭据改变时禁止沿用旧匹配。
	b, _ := json.Marshal([]any{c.ID, c.BaseURL(), c.AuthType, c.Token, c.AppID, c.AppSecret})
	return fmt.Sprintf("%x", sha256.Sum256(b))
}

func registerSourceTest() {
	bus.Register("danmaku.testSource", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		b, err := json.Marshal(a["source"])
		if err != nil {
			return nil, bus.NewErr(bus.EInvalid, "源配置无效")
		}
		var c SourceConfig
		if json.Unmarshal(b, &c) != nil {
			return nil, bus.NewErr(bus.EInvalid, "源配置无效")
		}
		c, err = normalizeSource(c)
		if err != nil {
			return nil, bus.NewErr(bus.EInvalid, "%s", err)
		}
		if c.Official {
			var ok bool
			c, ok = OfficialSource()
			if !ok {
				return nil, bus.NewErr(bus.EUnsupported, "官方源凭据不可用")
			}
		}
		ctx, cancel := context.WithTimeout(ctx, sourceTimeout)
		defer cancel()
		v, err := c.getJSON(ctx, "/search/anime", url.Values{"keyword": {"测试"}, "v2": {"true"}})
		if err != nil {
			return nil, upstream(err)
		}
		_, old := v["animes"].([]any)
		_, modern := v["bangumiList"].([]any)
		if !old && !modern {
			return nil, bus.NewErr(bus.EUpstream, "接口响应不兼容：缺少作品列表")
		}
		return map[string]any{"ok": true}, nil
	})
}
