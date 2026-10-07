package progress

import (
	"context"
	"errors"
	"math"

	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/history"
)

// Link 将本次播放绑定到固定主服条目及起播版本，不随活跃账号变化。
type Link struct {
	store    *Store
	client   *emby.Client
	session  emby.Session
	binding  config.ProgressServer
	itemID   string
	baseline emby.ProgressSnapshot
	blocked  bool
	epoch    uint64
}

// Prepare 返回可信主服状态；失败/不唯一/版本不兼容均由调用方正常按所在服起播。
func (s *Store) Prepare(ctx context.Context, client *emby.Client, cfg *config.AppConfig, source *emby.Session, candidate history.Candidate, seriesTmdb *string, sourceID string, sourceTicks int64) (*Link, *emby.ProgressSnapshot, error) {
	epoch := config.ProgressAccountEpoch()
	account := cfg.PrimaryProgressAccount()
	if account == nil {
		return nil, nil, errors.New("主进度服账号已失效")
	}
	binding := config.ProgressServer{Server: account.Server, UserID: account.UserID}
	server, token, userID, deviceID, ok := cfg.SessionOf(account.Server)
	if !ok {
		return nil, nil, errors.New("主进度服账号不可用")
	}
	session := emby.Session{Server: server, Token: token, UserID: userID, DeviceID: deviceID}
	base := cfg.Resolve(source.Server)
	isPrimary := base != nil && base.Server == binding.Server && source.UserID == binding.UserID
	itemID := candidate.ID
	if !isPrimary {
		match, err := history.FindPrimaryCandidate(ctx, client, &session, candidate, seriesTmdb)
		if err != nil || match == nil {
			return nil, nil, err
		}
		itemID, sourceID = match.ID, ""
	}
	if err := s.enter(ctx); err != nil {
		return nil, nil, err
	}
	defer func() { <-s.gate }()
	state, err := client.ProgressSnapshot(ctx, &session, itemID, sourceID)
	if err != nil {
		return nil, nil, err
	}
	if epoch != config.ProgressAccountEpoch() {
		return nil, nil, errors.New("主服账号已变化，未使用旧进度")
	}
	if isPrimary {
		return nil, state, nil // 主服正常上报保留既有门槛，不与自己的普通回报做 CAS。
	}
	if sourceTicks <= 0 || state.RunTimeTicks <= 0 || math.Abs(float64(sourceTicks-state.RunTimeTicks)) > float64(history.TicksPerSec) {
		return nil, nil, errors.New("两服所选版本时长不一致或未知，未同步进度")
	}
	if epoch != config.ProgressAccountEpoch() {
		return nil, nil, errors.New("主服账号已变化，未使用旧进度")
	}
	link := &Link{store: s, client: client, session: session, binding: binding, itemID: itemID, baseline: *state, epoch: epoch}
	k := key(binding.Server, binding.UserID, itemID)
	s.mu.Lock()
	rows, loadErr := s.load()
	if loadErr == nil {
		_, link.blocked = rows[k]
		s.owners[k] = link
	}
	s.mu.Unlock()
	if loadErr != nil {
		return nil, state, loadErr
	}
	return link, state, nil
}

// Sync 先落待同步记录再联网，失败不会丢进度；旧播放实例不能改写新实例的目标。
func (l *Link) Sync(ctx context.Context, positionSecs float64, watched bool) error {
	if l == nil {
		return nil
	}
	if positionSecs < 0 || math.IsNaN(positionSecs) || math.IsInf(positionSecs, 0) {
		return errors.New("播放位置无效")
	}
	s := l.store
	if err := s.enter(ctx); err != nil {
		return err
	}
	defer func() { <-s.gate }()
	release, valid := config.HoldProgressAccount(l.epoch)
	if !valid {
		return nil
	}
	defer release()
	k := key(l.binding.Server, l.binding.UserID, l.itemID)
	s.mu.Lock()
	if s.owners[k] != l || l.epoch != config.ProgressAccountEpoch() {
		s.mu.Unlock()
		return nil
	}
	rows, err := s.load()
	if err != nil {
		s.mu.Unlock()
		return err
	}
	if l.blocked {
		s.mu.Unlock()
		return errors.New("此条目仍有之前的待同步进度，未覆盖旧记录")
	}
	state := l.baseline
	position := int64(positionSecs*1000) * 10_000
	if position > state.RunTimeTicks+history.TicksPerSec {
		s.mu.Unlock()
		return errors.New("播放位置超出主服版本时长，未同步")
	}
	state.PositionTicks = min(position, state.RunTimeTicks)
	state.Played = l.baseline.Played || watched
	if watched {
		state.PositionTicks = 0
	}
	row := Pending{Server: l.binding.Server, UserID: l.binding.UserID, ItemID: l.itemID, State: state}
	if prior, ok := rows[k]; ok {
		row.Conflict = prior.Conflict
	}
	rows[k] = row
	err = s.save(rows)
	s.mu.Unlock()
	if err != nil {
		return err
	}
	out, conflict, syncErr := s.syncPending(ctx, l.client, &l.session, row)
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err = s.load()
	if err != nil {
		return err
	}
	if syncErr == nil {
		delete(rows, k)
		l.baseline = *out
	} else {
		row.Conflict = conflict
		rows[k] = row
	}
	if err := s.save(rows); err != nil {
		return err
	}
	return syncErr
}
