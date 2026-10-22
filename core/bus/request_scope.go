package bus

import (
	"context"
	"strings"
)

// requestScope 属于单次宿主请求，不持久化，不作为进程全局隐私开关。
func scopedHandler(command string, handler Handler) Handler {
	return func(ctx context.Context, seq int64, args map[string]any) (any, error) {
		clean, scope, err := scopeArguments(command, args)
		if err != nil {
			return nil, err
		}
		value, err := handler(ctx, seq, clean)
		if err == nil && ctx.Err() == nil && scope != nil {
			if change := userStateChange(command); change != "" {
				// 只传不透明代次，不把身份凭据放入广播。
				Emit("data.userState", map[string]any{"id": scope["id"], "scope": change}, "")
			}
		}
		return value, err
	}
}

func userStateChange(command string) string {
	switch command {
	case "emby.setPlayed", "emby.setBlocked":
		return "all"
	case "emby.setFavorite":
		return "library"
	case "emby.hideResume":
		return "resume"
	}
	return ""
}

func scopeArguments(command string, args map[string]any) (map[string]any, map[string]any, error) {
	raw, exists := args["request_scope"]
	if !exists {
		return args, nil, nil
	}
	scope, ok := raw.(map[string]any)
	if !ok {
		return nil, nil, NewErr(EInvalid, "请求空间格式无效")
	}
	id, idOK := scope["id"].(string)
	private, privateOK := scope["private"].(bool)
	filtered, filterOK := scope["filter"].(bool)
	if !idOK || id == "" || !privateOK || !filterOK {
		return nil, nil, NewErr(EInvalid, "请求空间不完整")
	}
	if private {
		// 流影仅访问本服入库资源；能力读取不探测网络，刷新仍由命令层拒绝。
		localExtension := command == "emby.extCapabilities" || command == "emby.extLiuYingItems" || command == "emby.extLiuYingLabels"
		if strings.HasPrefix(command, "source.") || strings.HasPrefix(command, "plugin.") || strings.HasPrefix(command, "download.") || strings.HasPrefix(command, "emby.watchHistory") || (strings.HasPrefix(command, "emby.ext") && !localExtension) {
			return nil, nil, NewErr(EUnsupported, "隐私空间仅支持当前服务器，不使用外部来源或本地记录")
		}
		switch command {
		case "emby.aggregateOverview", "emby.aggregateSearch", "emby.aggregateVersions", "player.playLocal", "prefs.pushSearch":
			return nil, nil, NewErr(EUnsupported, "隐私空间仅支持当前服务器")
		}
		for _, key := range []string{"server", "user_id", "token", "server_id"} {
			expectedKey := key
			if key == "server_id" {
				expectedKey = "server"
			}
			expected, ok := scope[expectedKey].(string)
			if !ok || expected == "" {
				return nil, nil, NewErr(EAuth, "隐私空间缺少当前身份")
			}
			if value, present := args[key]; present {
				actual, valid := value.(string)
				if !valid || (actual != "" && actual != expected) {
					return nil, nil, NewErr(EUnsupported, "隐私空间仅支持当前服务器和账号")
				}
			}
		}
	}
	clean := make(map[string]any, len(args))
	for key, value := range args {
		if key != "request_scope" {
			clean[key] = value
		}
	}
	media := strings.HasPrefix(command, "emby.") || command == "source.aggregateSearch"
	switch command {
	case "prefs.preloadItem", "player.play", "player.prepareNext", "player.queuePrepared", "player.chapterInfo":
		media = true
	case "player.liuyingOpen", "player.liuyingPrepare", "player.liuyingWarm", "player.liuyingCancel",
		"player.liuyingPlay", "player.liuyingProgress", "player.liuyingStop":
		media = true
	}
	if (filtered || private) && media {
		clean["is_adult"] = private
	}
	if private {
		for _, key := range []string{"server", "user_id", "token"} {
			clean[key] = scope[key]
		}
	}
	if server, ok := clean["server_id"].(string); ok && server != "" && server != scope["server"] {
		scope = nil
		return clean, scope, nil
	}
	// 跨服显式请求可以执行，但不能通知当前服页面。
	for _, key := range []string{"server", "user_id", "token"} {
		if value, ok := clean[key]; ok {
			actual, valid := value.(string)
			expected, _ := scope[key].(string)
			if !valid || actual != expected {
				scope = nil
				break
			}
		}
	}
	return clean, scope, nil
}
