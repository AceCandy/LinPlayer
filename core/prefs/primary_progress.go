package prefs

import (
	"context"
	"time"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/progress"
)

type PrimaryProgressSettings struct {
	Server string `json:"server"`
	UserID string `json:"user_id"`
	Name   string `json:"name"`
	Valid  bool   `json:"valid"`
	progress.Status
}

func primaryProgressSettings() PrimaryProgressSettings {
	c := config.Current()
	out := PrimaryProgressSettings{Status: progress.Shared().Status()}
	if binding := c.PrefsOf().PrimaryProgressServer; binding != nil {
		out.Server, out.UserID = binding.Server, binding.UserID
		if account := c.PrimaryProgressAccount(); account != nil {
			out.Valid, out.Name = true, account.DisplayName()
		}
	}
	return out
}

func registerPrimaryProgressCommands(version string) {
	client := emby.NewClient(version)
	bus.Register("prefs.getPrimaryProgressServer", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		return primaryProgressSettings(), nil
	})
	bus.Register("prefs.setPrimaryProgressServer", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		serverID, ok := a["server_id"].(string)
		if !ok {
			return nil, bus.NewErr(bus.EInvalid, "缺少 server_id；空字符串表示关闭主服同步")
		}
		c := config.Current()
		p := c.PrefsOf()
		oldBinding := p.PrimaryProgressServer
		p.PrimaryProgressServer = nil
		if serverID != "" {
			account := c.Resolve(serverID)
			if account == nil || account.IsFileBrowse() || account.UserID == "" {
				return nil, bus.NewErr(bus.EInvalid, "请选择已登录的 Emby 账号")
			}
			p.PrimaryProgressServer = &config.ProgressServer{Server: account.Server, UserID: account.UserID}
		}
		if (oldBinding == nil && p.PrimaryProgressServer == nil) || (oldBinding != nil && p.PrimaryProgressServer != nil && *oldBinding == *p.PrimaryProgressServer) {
			return primaryProgressSettings(), nil
		}
		previous := c.Prefs
		if err := save(c, p); err != nil {
			c.Prefs = previous
			return nil, err
		}
		progress.Shared().Invalidate()
		config.InvalidateProgressAccount()
		return primaryProgressSettings(), nil
	})
	bus.Register("prefs.retryPrimaryProgressSync", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		epoch := config.ProgressAccountEpoch()
		c := config.Current()
		account := c.PrimaryProgressAccount()
		if account == nil {
			return nil, bus.NewErr(bus.EInvalid, "请先选择有效的主进度服")
		}
		server, token, userID, deviceID, _ := c.SessionOf(account.Server)
		session := emby.Session{Server: server, Token: token, UserID: userID, DeviceID: deviceID}
		binding := config.ProgressServer{Server: account.Server, UserID: account.UserID}
		ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
		defer cancel()
		err := progress.Shared().Retry(ctx, client, binding, session, epoch)
		out := primaryProgressSettings()
		if err != nil {
			out.Error = "重试未完成，待同步记录已保留；冲突不会自动覆盖"
		}
		return out, nil
	})
}
