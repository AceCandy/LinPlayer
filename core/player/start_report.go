package player

import (
	"context"
	"sync"
	"time"

	"linplayer/core/bus"
	"linplayer/core/emby"
)

// 每次播放独立记账:开始异步,进度和停止串行,终止后拒绝迟到进度。
// 状态随播放目标释放,不保留无界的历史任务队列。
type playbackReport struct {
	started chan struct{}
	mu      sync.Mutex
	stopped bool
	session emby.Session
}

func (r *playbackReport) startAsync(s emby.Session, t *emby.PlaybackTarget, pos float64) {
	go func() {
		defer close(r.started)
		// 命令返回后请求上下文会取消,记账使用独立且有界的预算。
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := prefsClient.ReportStart(ctx, &s, t, pos); err != nil {
			bus.Logf("warn", "report_start 失败(不影响播放): %v", err)
		}
	}()
}

func (r *playbackReport) waitStart(ctx context.Context) error {
	select {
	case <-r.started:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (r *playbackReport) progress(ctx context.Context, s *emby.Session, t *emby.PlaybackTarget, pos float64, paused bool) (bool, error) {
	if r != nil {
		r.mu.Lock()
		defer r.mu.Unlock()
		if r.stopped {
			return false, nil
		}
		if err := r.waitStart(ctx); err != nil {
			return false, err
		}
		s = &r.session
	}
	err := prefsClient.ReportProgress(ctx, s, t, pos, paused)
	return err == nil, err
}
