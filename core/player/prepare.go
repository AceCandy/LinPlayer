package player

import (
	"context"
	"math"
	"sync"
	"time"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/net/prefetch"
	"linplayer/core/net/preload"
)

// 下一集只有一个独立准备槽；不改变当前目标、播放上报和当前代理。
// requestID由宿主生成，离页即使尚未拿到结果也能精确取消，旧取消不能关闭新槽。
type preparedPlayback struct {
	requestID                      string
	session                        emby.Session
	itemID, sourceID, versionRegex string
	target                         *emby.PlaybackTarget
	proxy                          *prefetch.Handle
	cancel                         context.CancelFunc
	expires                        *time.Timer
	ctx                            context.Context
	warmCancel                     context.CancelFunc
	warmFinished                   bool
}

var preparedMu sync.Mutex
var preparedNext *preparedPlayback

func cancelPrepared(requestID string) {
	cancelQueued(requestID)
	preparedMu.Lock()
	p := preparedNext
	if p == nil || (requestID != "" && p.requestID != requestID) {
		preparedMu.Unlock()
		return
	}
	preparedNext = nil
	p.cancel()
	if p.expires != nil {
		p.expires.Stop()
	}
	h := p.proxy
	preparedMu.Unlock()
	if h != nil {
		h.Close()
	}
}

// takePrepared一次消费；账号、授权、选版偏好变化后不得使用旧地址或旧会话。
func takePrepared(s *emby.Session, itemID, sourceID, requestID string, prefs config.Prefs, filters ...*bool) (*emby.PlaybackTarget, *prefetch.Handle) {
	var adult *bool
	if len(filters) > 0 {
		adult = filters[0]
	}
	preparedMu.Lock()
	p := preparedNext
	if p == nil || !emby.SameAdultFilter(emby.AdultFilter(p.ctx), adult) || requestID == "" || p.requestID != requestID || p.target == nil ||
		p.session != *s || p.itemID != itemID || p.sourceID != sourceID || p.versionRegex != prefs.VersionRegex {
		preparedMu.Unlock()
		return nil, nil
	}
	preparedNext = nil
	p.cancel()
	p.expires.Stop()
	preparedMu.Unlock()
	return p.target, p.proxy
}

func prepareNext(ctx context.Context, s *emby.Session, itemID, sourceID, requestID string) (any, error) {
	prefs := config.Current().PrefsOf()
	if !prefs.PreloadEnabled {
		return map[string]any{"skipped": true}, nil
	}
	warmCtx, cancel := context.WithCancel(context.WithoutCancel(ctx))
	p := &preparedPlayback{requestID: requestID, session: *s, itemID: itemID, sourceID: sourceID, versionRegex: prefs.VersionRegex, cancel: cancel, ctx: warmCtx}
	preparedMu.Lock()
	// 同时提交的准备命令仍只保留一个资源槽。
	old := preparedNext
	preparedNext = p
	preparedMu.Unlock()
	if old != nil {
		old.cancel()
		if old.expires != nil {
			old.expires.Stop()
		}
		if old.proxy != nil {
			old.proxy.Close()
		}
	}
	success := false
	defer func() {
		if !success {
			cancelPrepared(requestID)
		}
	}()
	// 请求返回之后预热继续；请求尚在解析时取消必须中断取流与探测。
	stopCancel := context.AfterFunc(ctx, cancel)
	defer stopCancel()
	resolveCtx, done := context.WithTimeout(warmCtx, 20*time.Second)
	defer done()
	target, err := prefsClient.ResolveStream(resolveCtx, s, itemID, sourceID, prefs.VersionRegex)
	if err != nil {
		return nil, err
	}
	// 非直连结果不保留准备代理，继续走正式起播路径。
	if target.PlayMethod != "DirectStream" {
		return map[string]any{"skipped": true}, nil
	}
	h, err := prefetch.StartCached(resolveCtx, target.URL, prefs.PrefetchThreads, prefs.PrefetchCacheBytes, false, mediaCacheOptions(s, target))
	if err != nil {
		return nil, err
	}
	preparedMu.Lock()
	if preparedNext != p || warmCtx.Err() != nil {
		preparedMu.Unlock()
		h.Close()
		return nil, context.Canceled
	}
	p.target, p.proxy = target, h
	p.expires = time.AfterFunc(2*time.Minute, func() { cancelPrepared(requestID) })
	preparedMu.Unlock()
	success = true

	return map[string]any{"request_id": requestID, "play_url": h.URL, "media_source_id": target.MediaSourceID, "external_subs": target.ExternalSubs}, nil
}

// 视频预热由当前播放的缓冲状态放行；暂停保留解析结果、代理和已完成缓存块。
func warmPrepared(s *emby.Session, requestID string, enabled bool) bool {
	preparedMu.Lock()
	defer preparedMu.Unlock()
	p := preparedNext
	if p == nil || p.requestID != requestID || p.session != *s || p.proxy == nil {
		return false
	}
	if !enabled {
		if p.warmCancel != nil {
			p.warmCancel()
			p.warmCancel = nil
		}
		return true
	}
	if p.warmCancel != nil || p.warmFinished {
		return true
	}
	ctx, cancel := context.WithCancel(p.ctx)
	p.warmCancel = cancel
	head := min(config.Current().PrefsOf().PreloadHeadMB, 8) << 20
	go func() {
		defer cancel()
		preload.New().Warm(ctx, p.itemID, p.proxy.URL, head, p.target.URL, preload.DefaultTailBytes)
		preparedMu.Lock()
		if ctx.Err() == nil {
			p.warmFinished = true
		}
		preparedMu.Unlock()
	}()
	return true
}

func registerPrepareCommands() {
	bus.Register("player.prepareNext", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		id, _ := a["item_id"].(string)
		request, _ := a["request_id"].(string)
		source, _ := a["media_source_id"].(string)
		if id == "" || request == "" {
			return nil, bus.NewErr(bus.EInvalid, "缺少 item_id 或 request_id")
		}
		s, err := sessionFrom(a)
		if err != nil {
			return nil, err
		}
		ctx, err = emby.AdultFilterContext(ctx, a)
		if err != nil {
			return nil, err
		}
		return prepareNext(ctx, s, id, source, request)
	})
	bus.Register("player.warmPrepared", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		request, _ := a["request_id"].(string)
		enabled, ok := a["enabled"].(bool)
		if request == "" || !ok {
			return nil, bus.NewErr(bus.EInvalid, "缺少 request_id 或 enabled")
		}
		s, err := sessionFrom(a)
		if err != nil {
			return nil, err
		}
		return map[string]any{"matched": warmPrepared(s, request, enabled)}, nil
	})

	bus.Register("player.queuePrepared", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		request, _ := a["request_id"].(string)
		previous, _ := a["current_item_id"].(string)
		engine, _ := a["engine"].(string)
		if request == "" || previous == "" || (engine != "mpv" && engine != "exo") {
			return nil, bus.NewErr(bus.EInvalid, "排队参数无效")
		}
		s, err := sessionFrom(a)
		if err != nil {
			return nil, err
		}
		ctx, err = emby.AdultFilterContext(ctx, a)
		if err != nil {
			return nil, err
		}
		return queuePrepared(ctx, s, request, previous, engine)
	})
	bus.Register("player.advanceQueued", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		request, _ := a["request_id"].(string)
		pos, _ := a["pos"].(float64)
		if request == "" || pos < 0 || math.IsNaN(pos) || math.IsInf(pos, 0) {
			return nil, bus.NewErr(bus.EInvalid, "排队接管参数无效")
		}
		s, err := sessionFrom(a)
		if err != nil {
			return nil, err
		}
		return advanceQueued(s, request, pos)
	})

	bus.Register("player.cancelPrepared", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		request, _ := a["request_id"].(string)
		if request == "" {
			return nil, bus.NewErr(bus.EInvalid, "缺少 request_id")
		}
		cancelPrepared(request)
		return map[string]any{"canceled": true}, nil
	})
}
