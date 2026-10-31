package player

import (
	"context"
	"encoding/json"
	"strconv"
	"sync"
	"time"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/httpx"
	"linplayer/core/progress"
)

// 排队只持有下一集资源；内核确认媒体切换后才转移历史和上报归属。
type queuedPlayback struct {
	prepared         *preparedPlayback
	previous         *emby.PlaybackTarget
	history          *historyContext
	primary          *progress.Link
	resume           float64
	previousDuration float64
	engine           string
	prefetch         string
	keepOpen         string
	entryID          int64
}

var queueMu sync.Mutex
var queuedNext *queuedPlayback

var queuePlaylist = func() string { return prop("playlist") }

func queuedResult(q *queuedPlayback) map[string]any {
	p := q.prepared
	return map[string]any{"request_id": p.requestID, "item_id": p.itemID, "play_url": p.proxy.URL,
		"resume_secs": q.resume, "media_source_id": p.target.MediaSourceID, "external_subs": p.target.ExternalSubs}
}

// queuePrepared 完成续播计算后再排队；慢查询期间仍允许旧集播放和取消准备。
func queuePrepared(ctx context.Context, s *emby.Session, request, previousID, engine string) (any, error) {
	queueMu.Lock()
	if queuedNext != nil {
		q := queuedNext
		if q.prepared.requestID == request && q.prepared.session == *s && q.engine == engine && q.previous.ItemID == previousID {
			out := queuedResult(q)
			queueMu.Unlock()
			return out, nil
		}
		queueMu.Unlock()
		return nil, bus.NewErr(bus.EInvalid, "已有下一集排队")
	}
	queueMu.Unlock()
	if err := awaitStopReport(ctx); err != nil {
		return nil, err
	}
	currentMu.Lock()
	previous, report := current, currentReport
	currentMu.Unlock()
	if previous == nil || previous.ItemID != previousID || report == nil || report.session != *s {
		return nil, bus.NewErr(bus.EInvalid, "播放目标已变化")
	}
	preparedMu.Lock()
	p := preparedNext
	if p == nil || p.requestID != request || p.session != *s || p.target == nil || p.proxy == nil ||
		!emby.SameAdultFilter(emby.AdultFilter(ctx), emby.AdultFilter(p.ctx)) {
		preparedMu.Unlock()
		return nil, bus.NewErr(bus.EInvalid, "下一集准备已失效")
	}
	target := p.target
	preparedMu.Unlock()
	prefs := config.Current().PrefsOf()
	h := buildPlaybackHistoryContext(ctx, s, p.itemID, prefs.CrossServerResume || prefs.PrimaryProgressServer != nil)
	// 不能拿缺失的历史猜测续播位置；本次仍可走常规切集。
	if h == nil {
		return nil, bus.NewErr(bus.ENetwork, "下一集进度暂未取得")
	}
	resume, primary, err := resolvePlaybackPosition(ctx, s, target, h, prefs, 0, false)
	if err != nil {
		return nil, err
	}
	// Media3队列项默认从零开始；已有续播位置继续使用正常起播，避免先闪片头再seek。
	if engine == "exo" && resume > 0 {
		return nil, bus.NewErr(bus.EUnsupported, "下一集需要续播，使用正常切换")
	}
	queueMu.Lock()
	defer queueMu.Unlock()
	currentMu.Lock()
	valid := current == previous && currentReport == report
	currentMu.Unlock()
	preparedMu.Lock()
	defer preparedMu.Unlock()
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if !valid || queuedNext != nil || preparedNext != p || p.ctx.Err() != nil || p.versionRegex != config.Current().PrefsOf().VersionRegex {
		return nil, bus.NewErr(bus.EInvalid, "下一集准备已失效")
	}
	q := &queuedPlayback{prepared: p, previous: previous, history: h, primary: primary, resume: resume, engine: engine, entryID: -1}
	if engine == "mpv" {
		q.previousDuration = propF("duration")
		// 当前Emby流已清理逐流请求头；下一集仍明确限定为同一会话的本地代理。
		q.prefetch = prop("prefetch-playlist")
		q.keepOpen = prop("keep-open")
		setProp("prefetch-playlist", "yes")
		setProp("keep-open", "yes")
		rollback := true
		defer func() {
			if rollback {
				q.restoreMpvOptions()
			}
		}()
		if err := appendQueuedMpv(p.proxy.URL, resume); err != nil {
			return nil, err
		}
		var entries []struct {
			ID       int64  `json:"id"`
			Filename string `json:"filename"`
		}
		if err := json.Unmarshal([]byte(queuePlaylist()), &entries); err != nil {
			_ = mpvCommand("playlist-remove", "-1")
			return nil, bus.NewErr(bus.EInternal, "读取播放队列失败")
		}
		for _, entry := range entries {
			if entry.Filename == p.proxy.URL {
				q.entryID = entry.ID
			}
		}
		if q.entryID < 0 {
			_ = mpvCommand("playlist-remove", "-1")
			return nil, bus.NewErr(bus.EInternal, "下一集未进入播放队列")
		}
		rollback = false
	}
	p.expires.Stop()
	p.expires = time.AfterFunc(2*time.Minute, func() { cancelQueued(request) })
	preparedNext = nil
	queuedNext = q
	return queuedResult(q), nil
}

func appendQueuedMpv(url string, resume float64) error {
	build := func(legacy bool) []string {
		args := loadArgs(url, resume, legacy, platformBufferTarget())
		args[2] = "append"
		if len(args) == 3 {
			if !legacy {
				args = append(args, "-1")
			}
			args = append(args, "")
		}
		i := len(args) - 1
		if args[i] != "" {
			args[i] += ","
		}
		args[i] += "http-header-fields=,user-agent=" + httpx.UA()
		return args
	}
	legacy := loadfileLegacy.Load()
	if err := mpvCommand(build(legacy)...); err != nil {
		if retry := mpvCommand(build(!legacy)...); retry != nil {
			return err
		}
		loadfileLegacy.Store(!legacy)
	}
	return nil
}

func (q *queuedPlayback) restoreMpvOptions() {
	if q.engine != "mpv" {
		return
	}
	if q.prefetch != "" {
		setProp("prefetch-playlist", q.prefetch)
	}
	if q.keepOpen != "" {
		setProp("keep-open", q.keepOpen)
	}
}

// cancelQueued 只移除尚未播放的一项，不能关闭已被内核接管的代理。
func cancelQueued(request string) {
	queueMu.Lock()
	defer queueMu.Unlock()
	cancelQueuedLocked(request)
}

func cancelQueuedLocked(request string) {
	q := queuedNext
	if q == nil || (request != "" && q.prepared.requestID != request) {
		return
	}
	if q.engine == "mpv" {
		var entries []struct {
			ID      int64 `json:"id"`
			Current bool  `json:"current"`
		}
		if json.Unmarshal([]byte(queuePlaylist()), &entries) == nil {
			for i, e := range entries {
				if e.ID == q.entryID {
					if e.Current {
						activateQueuedLocked(q, 0)
						return
					}
					_ = command("playlist-remove", strconv.Itoa(i))
					break
				}
			}
		}
	}
	queuedNext = nil
	q.restoreMpvOptions()
	q.prepared.expires.Stop()
	q.prepared.cancel()
	q.prepared.proxy.Close()
	bus.Emit("player.queueCanceled", map[string]any{"request_id": q.prepared.requestID}, "")
}

// activateQueuedLocked 不发stop/loadfile，不联网取流；旧Stopped仍先于新Playing。
func activateQueuedLocked(q *queuedPlayback, previousPosition float64) map[string]any {
	p := q.prepared
	currentMu.Lock()
	if current != q.previous {
		currentMu.Unlock()
		return nil
	}
	old, oldReport := current, currentReport
	if previousPosition <= 0 {
		previousPosition = q.previousDuration
		if previousPosition <= 0 && currentCtx != nil && currentCtx.candidate.RunTimeTicks != nil {
			previousPosition = float64(*currentCtx.candidate.RunTimeTicks) / 1e7
		}
		if previousPosition <= 0 {
			previousPosition = float64(old.RunTimeTicks) / 1e7
		}
	}
	captureHistoryLocked(previousPosition, true)
	watched := watchedNow(previousPosition)
	cancelHistoryRetryLocked()
	previousStop := pendingStopReport()
	done := make(chan struct{})
	stopReportMu.Lock()
	stopReportDone = done
	stopReportMu.Unlock()
	current, currentCtx = p.target, q.history
	report := &playbackReport{started: make(chan struct{}), previousStop: done, session: p.session, primary: q.primary, runtimeTicks: p.target.RunTimeTicks, useMpv: q.engine == "mpv"}
	if oldReport != nil {
		report.adult = oldReport.adult
		oldReport.stopped.Store(true)
	}
	currentReport = report
	pendingSubs = p.target.ExternalSubs
	currentMu.Unlock()
	queuedNext = nil
	q.restoreMpvOptions()
	p.expires.Stop()
	p.cancel()
	prefs := config.Current().PrefsOf()
	acc := config.Current().ActiveAccount()
	p.proxy.SetReadAhead(prefs.PrefetchThreads, acc != nil && prefs.PrefetchEnabledFor(acc.Server), p.target.Bitrate)
	proxyMu.Lock()
	oldProxy := sharedProxy
	sharedProxy = p.proxy
	proxyMu.Unlock()
	// 旧连接收尾不占媒体事件线程；每次衔接复用停止上报屏障。
	go func() {
		defer close(done)
		if previousStop != nil {
			<-previousStop
		}
		if oldProxy != nil {
			oldProxy.Close()
		}
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		_ = reportStopped(ctx, &p.session, old, oldReport, previousPosition, watched)
	}()
	setShaderScope(seriesScope(p.session.Server, q.history))
	if q.engine == "mpv" {
		_ = command("playlist-clear")
		setPlayURL(p.proxy.URL)
		ResetCadence()
		// 同一播放会话连播保留实际倍速，不能被起播默认值覆盖。
		if speed := propF("speed"); speed > 0 {
			prefs.DefaultSpeed = speed
		}
		applyPlaybackDefaults(prefs, p.target.IsDolbyVision)
	}
	report.startAsync(p.session, p.target, q.resume)
	out := queuedResult(q)
	bus.Emit("player.queueAdvanced", out, "")
	return out
}

func onQueuedMpvStart(entry int64) {
	queueMu.Lock()
	defer queueMu.Unlock()
	if q := queuedNext; q != nil && q.engine == "mpv" && q.entryID == entry {
		activateQueuedLocked(q, 0)
	}
}

func advanceQueued(s *emby.Session, request string, previousPosition float64) (any, error) {
	queueMu.Lock()
	defer queueMu.Unlock()
	q := queuedNext
	if q == nil || q.engine != "exo" || q.prepared.requestID != request || q.prepared.session != *s {
		return nil, bus.NewErr(bus.EInvalid, "排队目标已失效")
	}
	if out := activateQueuedLocked(q, previousPosition); out != nil {
		return out, nil
	}
	return nil, bus.NewErr(bus.EInvalid, "播放目标已变化")
}

// 状态采样也核对实际队列项，避免START_FILE尚在事件队列时给新时钟贴旧条目ID。
func syncQueuedMpvLocked() {
	q := queuedNext
	if q == nil || q.engine != "mpv" {
		return
	}
	var entries []struct {
		ID      int64 `json:"id"`
		Current bool  `json:"current"`
	}
	if json.Unmarshal([]byte(queuePlaylist()), &entries) != nil {
		return
	}
	for _, e := range entries {
		if e.Current && e.ID == q.entryID {
			activateQueuedLocked(q, 0)
			return
		}
	}
}
