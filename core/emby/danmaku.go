package emby

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"

	"linplayer/core/bus"
)

// RawDanmaku 仅在服务器明确声明能力时读取当前媒体，不缓存媒体权限和弹幕结果。
func (c *Client) RawDanmaku(ctx context.Context, s *Session, itemID string) ([]byte, error) {
	caps, err := c.extensionCapability(ctx, s, "")
	if err != nil {
		var e *bus.Err
		if errors.As(err, &e) && e.Code == bus.EUnsupported {
			return nil, nil
		}
		return nil, err
	}
	if !caps.Danmaku.Enabled || caps.Danmaku.Format != "bilibili_xml" {
		return nil, nil
	}
	if itemID == "" || itemID == "." || itemID == ".." {
		return nil, bus.NewErr(bus.EInvalid, "弹幕媒体标识无效")
	}
	base, err := extensionBase(s.Server)
	if err != nil {
		return nil, err
	}
	target := strings.TrimSuffix(base, "emby-ext/v1/") + "api/danmu/" + url.PathEscape(itemID) + "/raw"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return nil, bus.NewErr(bus.EInvalid, "服务器弹幕地址无效")
	}
	req.Header.Set("X-Emby-Token", s.Token)
	req.Header.Set("User-Agent", c.UA)
	req.Header.Set("Accept", "application/xml")
	client := *c.HTTP
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	resp, err := client.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, bus.NewErr(bus.ENetwork, "服务器弹幕请求失败")
	}
	defer resp.Body.Close()
	switch resp.StatusCode {
	case http.StatusOK:
	case http.StatusUnauthorized:
		return nil, bus.NewErr(bus.EAuth, "服务器弹幕登录已失效")
	case http.StatusForbidden:
		return nil, bus.NewErr(bus.EPermission, "无权读取服务器弹幕")
	case http.StatusNotFound:
		return nil, nil
	default:
		return nil, bus.NewErr(bus.EUpstream, "服务器弹幕返回 HTTP %d", resp.StatusCode)
	}
	const limit = 4 * 1024 * 1024
	b, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, bus.NewErr(bus.ENetwork, "服务器弹幕读取失败")
	}
	if len(b) > limit {
		return nil, bus.NewErr(bus.EInvalid, "服务器弹幕数据过大")
	}
	return b, nil
}
