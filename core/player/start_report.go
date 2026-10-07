package player

import (
	"context"
	"sync"
	"time"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/progress"
)

// 每次播放独立记账:开始异步,进度和停止串行,终止后拒绝迟到进度。
// 状态随播放目标释放,不保留无界的历史任务队列。
type playbackReport struct {
	started      chan struct{}
	mu           sync.Mutex
	stopped      bool
	session      emby.Session
	primary      *progress.Link
	runtimeTicks int64
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
	if r != nil {
		watched := config.Current().PrefsOf().WatchedAt(pos, float64(r.runtimeTicks)/1e7)
		r.syncPrimary(pos, watched)
	}
	return err == nil, err
}

func (r *playbackReport) syncPrimary(pos float64, watched bool) {
	if r.primary == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := r.primary.Sync(ctx, pos, watched); err != nil {
		progress.Shared().Note("主服同步未完成，请查看同步状态")
		bus.Emit("progress.primary", map[string]any{"message": "主服同步未完成，请查看同步状态"}, "")
	} else {
		progress.Shared().Note("")
	}
}
