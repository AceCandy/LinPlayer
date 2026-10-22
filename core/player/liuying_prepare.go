package player

import (
	"context"
	"sync"
	"time"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/net/prefetch"
)

// 流影准备池与通用下一集单槽隔离；在途解析也占用3项额度。
type liuyingPrepared struct {
	*preparedPlayback
	epoch uint64
	reads *prefetch.ReadControl
	done  chan struct{}
}

type liuyingOwner struct {
	id         string
	session    emby.Session
	generation int64
	ctx        context.Context
	cancel     context.CancelFunc
	budget     *prefetch.ReadBudget
	items      map[string]*liuyingPrepared
}

var liuyingMu sync.Mutex

// 只串行资源交接，不覆盖取流；旧代在途请求退出后才允许新代占槽。
var liuyingControlMu sync.Mutex
var liuyingPool *liuyingOwner

func closeLiuyingPrepared(p *liuyingPrepared) {
	p.cancel()
	<-p.done
	if p.expires != nil {
		p.expires.Stop()
	}
	if p.proxy != nil {
		p.proxy.Close()
	}
}

func openLiuying(s *emby.Session, owner string) error {
	liuyingControlMu.Lock()
	defer liuyingControlMu.Unlock()
	liuyingMu.Lock()
	if liuyingPool != nil && liuyingPool.id == owner && liuyingPool.session == *s {
		cancelled := liuyingPool.ctx.Err() != nil
		liuyingMu.Unlock()
		if cancelled {
			return bus.NewErr(bus.EInvalid, "流影会话已失效")
		}
		return nil
	}
	old := liuyingPool
	ctx, cancel := context.WithCancel(context.Background())
	if old != nil {
		old.cancel()
	}
	var removed []*liuyingPrepared
	if old != nil {
		for _, p := range old.items {
			removed = append(removed, p)
		}
		old.items = map[string]*liuyingPrepared{}
	}
	liuyingPool = nil
	liuyingMu.Unlock()
	if old != nil {
		for _, p := range removed {
			closeLiuyingPrepared(p)
		}
	}
	liuyingMu.Lock()
	liuyingPool = &liuyingOwner{id: owner, session: *s, generation: 1, ctx: ctx, cancel: cancel,
		budget: prefetch.NewReadBudget(), items: map[string]*liuyingPrepared{}}
	liuyingMu.Unlock()
	return nil
}

// 调用方持有controlMu和liuyingMu。换范围先回收旧代；旧代不能复活owner。
func liuyingOwnerLocked(s *emby.Session, owner string, generation int64) (*liuyingOwner, error) {
	o := liuyingPool
	if o == nil || o.id != owner || o.session != *s || generation < o.generation || o.ctx.Err() != nil {
		return nil, bus.NewErr(bus.EInvalid, "流影会话已失效")
	}
	if generation > o.generation {
		old := o.items
		o.cancel()
		o.generation, o.items = generation, map[string]*liuyingPrepared{}
		liuyingMu.Unlock()
		for _, p := range old {
			closeLiuyingPrepared(p)
		}
		liuyingMu.Lock()
		o.ctx, o.cancel = context.WithCancel(context.Background())
	}
	return o, nil
}

func cancelLiuying(s *emby.Session, owner string, generation int64, request string) bool {
	liuyingControlMu.Lock()
	defer liuyingControlMu.Unlock()
	liuyingMu.Lock()
	o := liuyingPool
	if o == nil || o.id != owner || o.session != *s || o.generation != generation {
		liuyingMu.Unlock()
		return false
	}
	var removed []*liuyingPrepared
	if request == "" {
		o.cancel()
		for _, p := range o.items {
			removed = append(removed, p)
		}
		o.items = map[string]*liuyingPrepared{}
	} else if p := o.items[request]; p != nil {
		delete(o.items, request)
		removed = append(removed, p)
	}
	liuyingMu.Unlock()
	for _, p := range removed {
		closeLiuyingPrepared(p)
	}
	return true
}

func prepareLiuying(ctx context.Context, s *emby.Session, owner string, generation int64, request, item, source string) (any, error) {
	prefs := config.Current().PrefsOf()
	if !prefs.PreloadEnabled {
		return map[string]any{"skipped": true}, nil
	}
	liuyingControlMu.Lock()
	liuyingMu.Lock()
	o, err := liuyingOwnerLocked(s, owner, generation)
	if err != nil {
		liuyingMu.Unlock()
		liuyingControlMu.Unlock()
		return nil, err
	}
	if o.items[request] != nil || len(o.items) >= 3 {
		liuyingMu.Unlock()
		liuyingControlMu.Unlock()
		return nil, bus.NewErr(bus.EInvalid, "流影准备窗口已满或请求重复")
	}
	warmCtx, cancel := context.WithCancel(context.WithoutCancel(ctx))
	stopOwner := context.AfterFunc(o.ctx, cancel)
	p := &liuyingPrepared{
		preparedPlayback: &preparedPlayback{requestID: request, session: *s, itemID: item, sourceID: source,
			versionRegex: prefs.VersionRegex, ctx: warmCtx, cancel: func() { stopOwner(); cancel() }},
		epoch: prefetch.MediaCacheEpoch(), reads: prefetch.NewReadControl(o.budget), done: make(chan struct{}),
	}
	o.items[request] = p
	liuyingMu.Unlock()
	liuyingControlMu.Unlock()
	success := false
	var h *prefetch.Handle
	defer func() {
		if !success {
			p.cancel()
			liuyingMu.Lock()
			if o.items[request] == p {
				delete(o.items, request)
			}
			liuyingMu.Unlock()
			if h != nil {
				h.Close()
			}
		}
		close(p.done)
	}()
	stopRequest := context.AfterFunc(ctx, cancel)
	defer stopRequest()
	resolveCtx, done := context.WithTimeout(warmCtx, 20*time.Second)
	defer done()
	target, err := prefsClient.ResolveStream(resolveCtx, s, item, source, prefs.VersionRegex)
	if err != nil {
		return nil, err
	}
	canPreload := target.PlayMethod == "DirectStream" && target.RunTimeTicks > 0 && !target.IsLive
	if canPreload {
		options := mediaCacheOptions(s, target)
		options.Epoch, options.Reads = p.epoch, p.reads
		h, err = prefetch.StartCached(resolveCtx, target.URL, prefs.PrefetchThreads, prefs.PrefetchCacheBytes, false, options)
		if err != nil {
			return nil, err
		}
	}
	liuyingMu.Lock()
	if liuyingPool != o || o.items[request] != p || warmCtx.Err() != nil || p.epoch != prefetch.MediaCacheEpoch() {
		liuyingMu.Unlock()
		return nil, context.Canceled
	}
	p.target, p.proxy = target, h
	p.expires = time.AfterFunc(2*time.Minute, func() { cancelLiuying(s, owner, generation, request) })
	success = true
	liuyingMu.Unlock()
	url := target.URL
	if h != nil {
		url = h.URL
	}
	return map[string]any{"request_id": request, "play_url": url, "media_source_id": target.MediaSourceID,
		"external_subs": target.ExternalSubs, "can_preload": canPreload}, nil
}

func warmLiuying(s *emby.Session, owner string, generation int64, request string, enabled bool) bool {
	liuyingMu.Lock()
	defer liuyingMu.Unlock()
	o := liuyingPool
	if o == nil || o.id != owner || o.session != *s || o.generation != generation || o.ctx.Err() != nil {
		return false
	}
	p := o.items[request]
	if p == nil || p.target == nil || p.proxy == nil || p.epoch != prefetch.MediaCacheEpoch() {
		return false
	}
	// 样本深度由手机Media3管理器执行；核心只放行同一代理的有界读取。
	p.reads.SetEnabled(enabled)
	return true
}

func takeLiuying(s *emby.Session, start *liuyingStart, item, source, request string, prefs config.Prefs, adult *bool) (*emby.PlaybackTarget, *prefetch.Handle) {
	if request == "" {
		return nil, nil
	}
	liuyingMu.Lock()
	o := liuyingPool
	if o == nil || o.id != start.owner || o.session != *s || o.generation != start.generation {
		liuyingMu.Unlock()
		return nil, nil
	}
	p := o.items[request]
	if p == nil || p.target == nil || p.ctx.Err() != nil || p.epoch != start.epoch ||
		p.itemID != item || p.sourceID != source || p.versionRegex != prefs.VersionRegex ||
		!emby.SameAdultFilter(emby.AdultFilter(p.ctx), adult) {
		liuyingMu.Unlock()
		cancelLiuying(s, start.owner, start.generation, request)
		return nil, nil
	}
	delete(o.items, request)
	p.expires.Stop()
	p.cancel()
	p.reads.Promote()
	start.consumed = request
	liuyingMu.Unlock()
	return p.target, p.proxy
}
