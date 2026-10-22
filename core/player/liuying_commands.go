package player

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"math"
	"sync"
	"time"

	"linplayer/core/bus"
	"linplayer/core/emby"
	"linplayer/core/net/prefetch"
)

type liuyingStart struct {
	owner, playback, consumed string
	generation                int64
	epoch, revision           uint64
	ctx                       context.Context
	proxy                     *prefetch.Handle
}

// 归属绑定正式report实例，而不是可能重复的item或PlaySessionId；由currentMu保护。
type liuyingBinding struct {
	owner, playback string
	report          *playbackReport
	proxy           *prefetch.Handle
}

var liuyingCurrent *liuyingBinding
var playbackRevision uint64 // currentMu保护；普通起播也使旧流影解析失效。
var liuyingTransportMu sync.Mutex

func liuyingID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b[:])
}

func liuyingIdentity(ctx context.Context, a map[string]any) (context.Context, *emby.Session, string, int64, error) {
	ctx, err := emby.AdultFilterContext(ctx, a)
	if err != nil {
		return ctx, nil, "", 0, err
	}
	adult := emby.AdultFilter(ctx)
	if adult == nil || !*adult {
		return ctx, nil, "", 0, bus.NewErr(bus.EUnsupported, "流影播放仅支持当前隐私空间")
	}
	owner, _ := a["owner_id"].(string)
	generation, ok := a["generation"].(float64)
	if owner == "" || len(owner) > 128 || !ok || generation < 1 || generation > 1<<31-1 || math.Trunc(generation) != generation {
		return ctx, nil, "", 0, bus.NewErr(bus.EInvalid, "流影会话参数无效")
	}
	s, err := sessionFrom(a)
	return ctx, s, owner, int64(generation), err
}

func liuyingMatchesLocked(s *emby.Session, owner, playback, item string) bool {
	b := liuyingCurrent
	return b != nil && b.owner == owner && b.playback == playback && b.report == currentReport &&
		b.report.session == *s && current != nil && current.ItemID == item
}

func playLiuying(ctx context.Context, s *emby.Session, owner string, generation int64, request, item, source string) (any, error) {
	liuyingTransportMu.Lock()
	defer liuyingTransportMu.Unlock()
	liuyingControlMu.Lock()
	liuyingMu.Lock()
	o, err := liuyingOwnerLocked(s, owner, generation)
	if err != nil {
		liuyingMu.Unlock()
		liuyingControlMu.Unlock()
		return nil, err
	}
	ownerCtx := o.ctx
	liuyingMu.Unlock()
	liuyingControlMu.Unlock()
	currentMu.Lock()
	busy := current != nil
	currentMu.Unlock()
	if busy {
		return nil, bus.NewErr(bus.EInvalid, "已有正式播放，请先结束当前会话")
	}
	ctx, cancel := context.WithTimeout(ctx, 25*time.Second)
	defer cancel()
	stopOwner := context.AfterFunc(ownerCtx, cancel)
	defer stopOwner()
	start := &liuyingStart{owner: owner, generation: generation, epoch: prefetch.MediaCacheEpoch(), playback: liuyingID(), ctx: ownerCtx}
	result, err := playWithLiuying(ctx, s, item, -1, source, false, false, "liuying", request, start)
	if err != nil {
		return nil, err
	}
	return struct {
		*PlayResult
		PlaybackID        string `json:"playback_id"`
		ConsumedRequestID string `json:"consumed_request_id"`
	}{result, start.playback, start.consumed}, nil
}

func registerLiuyingCommands() {
	bus.Register("player.liuyingOpen", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		_, s, owner, generation, err := liuyingIdentity(ctx, a)
		if err != nil {
			return nil, err
		}
		if generation != 1 {
			return nil, bus.NewErr(bus.EInvalid, "流影初始代数必须为1")
		}
		return map[string]any{"opened": true}, openLiuying(s, owner)
	})
	bus.Register("player.liuyingPrepare", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		ctx, s, owner, generation, err := liuyingIdentity(ctx, a)
		if err != nil {
			return nil, err
		}
		request, _ := a["request_id"].(string)
		item, _ := a["item_id"].(string)
		source, _ := a["media_source_id"].(string)
		if request == "" || item == "" {
			return nil, bus.NewErr(bus.EInvalid, "缺少流影条目或准备请求")
		}
		return prepareLiuying(ctx, s, owner, generation, request, item, source)
	})
	bus.Register("player.liuyingWarm", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		_, s, owner, generation, err := liuyingIdentity(ctx, a)
		if err != nil {
			return nil, err
		}
		request, _ := a["request_id"].(string)
		enabled, ok := a["enabled"].(bool)
		depth, depthOK := a["depth"].(float64)
		if request == "" || !ok || !depthOK || (depth != 1 && depth != 3 && depth != 5) {
			return nil, bus.NewErr(bus.EInvalid, "流影预热参数无效")
		}
		return map[string]any{"matched": warmLiuying(s, owner, generation, request, enabled)}, nil
	})
	bus.Register("player.liuyingCancel", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		_, s, owner, generation, err := liuyingIdentity(ctx, a)
		if err != nil {
			return nil, err
		}
		request, _ := a["request_id"].(string)
		return map[string]any{"matched": cancelLiuying(s, owner, generation, request)}, nil
	})
	bus.Register("player.liuyingPlay", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		ctx, s, owner, generation, err := liuyingIdentity(ctx, a)
		if err != nil {
			return nil, err
		}
		item, _ := a["item_id"].(string)
		source, _ := a["media_source_id"].(string)
		request, _ := a["request_id"].(string)
		if item == "" {
			return nil, bus.NewErr(bus.EInvalid, "缺少流影条目")
		}
		return playLiuying(ctx, s, owner, generation, request, item, source)
	})
	bus.Register("player.liuyingProgress", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		_, s, owner, _, err := liuyingIdentity(ctx, a)
		if err != nil {
			return nil, err
		}
		item, playback, pos, err := liuyingPosition(a)
		if err != nil {
			return nil, err
		}
		paused, ok := a["paused"].(bool)
		if !ok {
			return nil, bus.NewErr(bus.EInvalid, "缺少暂停状态")
		}
		currentMu.Lock()
		matched := liuyingMatchesLocked(s, owner, playback, item)
		target, report := current, currentReport
		currentMu.Unlock()
		if !matched {
			return map[string]any{"reported": false}, nil
		}
		reported, err := report.progress(ctx, s, target, pos, paused)
		if err != nil {
			bus.Logf("warn", "流影进度上报失败")
		}
		return map[string]any{"reported": reported}, nil
	})
	bus.Register("player.liuyingStop", func(ctx context.Context, seq int64, a map[string]any) (any, error) {
		_, s, owner, _, err := liuyingIdentity(ctx, a)
		if err != nil {
			return nil, err
		}
		item, playback, pos, err := liuyingPosition(a)
		if err != nil {
			return nil, err
		}
		handoff, _ := a["handoff"].(bool)
		liuyingTransportMu.Lock()
		defer liuyingTransportMu.Unlock()
		guard := &liuyingBinding{owner: owner, playback: playback}
		if err := stopPlaybackOwned(ctx, s, pos, handoff, item, guard); err != nil {
			return nil, err
		}
		return map[string]any{"stopped": true}, nil
	})
}

func liuyingPosition(a map[string]any) (string, string, float64, error) {
	item, _ := a["item_id"].(string)
	playback, _ := a["playback_id"].(string)
	pos, ok := a["pos"].(float64)
	if item == "" || playback == "" || !ok || pos < 0 || math.IsNaN(pos) || math.IsInf(pos, 0) {
		return "", "", 0, bus.NewErr(bus.EInvalid, "流影播放位置无效")
	}
	return item, playback, pos, nil
}
