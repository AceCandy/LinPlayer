package player

import (
	"context"

	"linplayer/core/emby"
)

// 只在停止上报在途时保留；私密进度不落盘，也不能跨身份或浏览空间接管。
type stopResumeKey struct {
	session    emby.Session
	item       string
	classified bool
	private    bool
}

type stopResume struct {
	key     stopResumeKey
	source  string
	runtime int64
	pos     float64
	watched bool
}

var stoppingPositions = map[stopResumeKey]*stopResume{} // stopReportMu保护，随报告完成移除。

func pendingResumeFor(ctx context.Context, s *emby.Session, item string) *stopResume {
	stopPlaybackMu.Lock()
	defer stopPlaybackMu.Unlock()
	adult := emby.AdultFilter(ctx)
	stopReportMu.Lock()
	defer stopReportMu.Unlock()
	return stoppingPositions[stopResumeKey{*s, item, adult != nil, adult != nil && *adult}]
}

func rememberStopResume(s emby.Session, t *emby.PlaybackTarget, adult *bool, pos float64, watched bool) *stopResume {
	r := &stopResume{key: stopResumeKey{s, t.ItemID, adult != nil, adult != nil && *adult}, source: t.MediaSourceID, runtime: t.RunTimeTicks, pos: pos, watched: watched}
	stopReportMu.Lock()
	stoppingPositions[r.key] = r
	stopReportMu.Unlock()
	return r
}

func (r *stopResume) finish() {
	stopReportMu.Lock()
	if stoppingPositions[r.key] == r {
		delete(stoppingPositions, r.key)
	}
	stopReportMu.Unlock()
}
