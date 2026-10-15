package emby

import (
	"context"
	"net/http"
	"strconv"
	"sync"

	"linplayer/core/bus"
)

type adultFilterKey struct{}
type adultValidationKey struct{}

// AdultFilterContext 校验命令的可选分类，缺省不发送筛选，显式 false 不得丢失。
func AdultFilterContext(ctx context.Context, args map[string]any) (context.Context, error) {
	value, exists := args["is_adult"]
	if !exists {
		return ctx, nil
	}
	adult, ok := value.(bool)
	if !ok {
		return nil, bus.NewErr(bus.EInvalid, "is_adult 必须是布尔值")
	}
	ctx = context.WithValue(ctx, adultFilterKey{}, adult)
	if adult {
		ctx = context.WithValue(ctx, adultValidationKey{}, &sync.Map{})
	}
	return ctx, nil
}

// applyAdultFilter 只影响本次请求，不修改共享客户端或账号设置。
func applyAdultFilter(ctx context.Context, req *http.Request) {
	if adult, ok := ctx.Value(adultFilterKey{}).(bool); ok {
		query := req.URL.Query()
		query.Set("IsAdult", strconv.FormatBool(adult))
		req.URL.RawQuery = query.Encode()
	}
}

// AdultFilter 返回本次请求的分类；nil 表示调用方未启用分类隔离。
func AdultFilter(ctx context.Context) *bool {
	if ctx == nil {
		return nil
	}
	value, ok := ctx.Value(adultFilterKey{}).(bool)
	if !ok {
		return nil
	}
	return &value
}

// SameAdultFilter 比较完整三态，避免未分类的历史进入已隔离的空间。
func SameAdultFilter(a, b *bool) bool {
	return a == nil && b == nil || a != nil && b != nil && *a == *b
}

// requireAdultSupport 避免聚合或换源把忽略分类参数的旧服混入私密浏览。
// 确认只在本次命令内复用，账号权限与库分类变更不会被长期能力缓存掩盖。
func (c *Client) requireAdultSupport(ctx context.Context, s *Session) error {
	adult := AdultFilter(ctx)
	if adult == nil || !*adult {
		return nil
	}
	verified, _ := ctx.Value(adultValidationKey{}).(*sync.Map)
	identity := s.Server + "\x00" + s.UserID + "\x00" + s.Token
	if verified != nil {
		if _, ok := verified.Load(identity); ok {
			return nil
		}
	}
	if _, err := c.Views(ctx, s); err != nil {
		return err
	}
	if verified != nil {
		verified.Store(identity, true)
	}
	return nil
}
