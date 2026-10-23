package prefetch

import (
	"context"
	"sync"
)

// ReadBudget 限制同一流影owner的投机块数，不限制正式播放或普通代理。
type ReadBudget struct{ slots chan struct{} }

func NewReadBudget() *ReadBudget { return &ReadBudget{slots: make(chan struct{}, 4)} }

// ReadControl 在分配块载体前门控投机读取；暂停不关闭代理或删除完整缓存。
type ReadControl struct {
	mu       sync.Mutex
	budget   *ReadBudget
	changed  chan struct{}
	active   context.Context
	cancel   context.CancelFunc
	enabled  bool
	promoted bool
}

func NewReadControl(budget *ReadBudget) *ReadControl {
	return &ReadControl{budget: budget, changed: make(chan struct{})}
}

func (r *ReadControl) SetEnabled(enabled bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.promoted || r.enabled == enabled {
		return
	}
	if r.cancel != nil {
		r.cancel()
	}
	r.enabled = enabled
	if enabled {
		r.active, r.cancel = context.WithCancel(context.Background())
	}
	close(r.changed)
	r.changed = make(chan struct{})
}

// Promote 保留已认领块直到收尾，新正式读取不再占投机额度。
func (r *ReadControl) Promote() {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.promoted {
		return
	}
	r.promoted = true
	if r.active == nil || r.active.Err() != nil {
		r.active, r.cancel = context.WithCancel(context.Background())
	}
	close(r.changed)
	r.changed = make(chan struct{})
}

// Demote 将已停止的流影代理退回准备池，终止正式读取，恢复投机预算和启停门控。
func (r *ReadControl) Demote() {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.cancel != nil {
		r.cancel()
	}
	r.promoted, r.enabled = false, false
	close(r.changed)
	r.changed = make(chan struct{})
}

func (r *ReadControl) speculative() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return !r.promoted
}

func (r *ReadControl) acquire(parent context.Context) (context.Context, func(), error) {
	for {
		r.mu.Lock()
		enabled, promoted, active, changed := r.enabled, r.promoted, r.active, r.changed
		r.mu.Unlock()
		if promoted {
			ctx, cancel := context.WithCancel(parent)
			stop := context.AfterFunc(active, cancel)
			return ctx, func() { stop(); cancel() }, nil
		}
		if !enabled {
			// 结束当前供给连接，不能让已关闭的预载连接无限等待恢复。
			return nil, nil, context.Canceled
		}
		select {
		case r.budget.slots <- struct{}{}:
		case <-changed:
			continue
		case <-active.Done():
			continue
		case <-parent.Done():
			return nil, nil, parent.Err()
		}
		r.mu.Lock()
		stale := r.promoted || r.active != active || !r.enabled
		r.mu.Unlock()
		if stale {
			<-r.budget.slots
			continue
		}
		ctx, cancel := context.WithCancel(parent)
		stop := context.AfterFunc(active, cancel)
		return ctx, func() { stop(); cancel(); <-r.budget.slots }, nil
	}
}
