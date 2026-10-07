// Package progress 将主服待同步状态与本地观看历史分开；本地数据不参与权威续播。
package progress

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"

	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/paths"
)

// Pending 不保存凭据；重试必须重新核验相同服务器及用户的当前账号。
type Pending struct {
	Server, UserID, ItemID string
	State                  emby.ProgressSnapshot
	Conflict               bool
}

// Store 原子保存待同步条目；网络操作串行，防止旧会话在新基线之后回写。
type Store struct {
	mu     sync.Mutex
	gate   chan struct{}
	path   string
	owners map[string]*Link
	notice string
}

func NewStore(path string) *Store {
	return &Store{path: path, gate: make(chan struct{}, 1), owners: map[string]*Link{}}
}

var shared = NewStore("")

func Shared() *Store { return shared }

func (s *Store) file() string {
	if s.path != "" {
		return s.path
	}
	return paths.ProgressSyncFile()
}

func key(server, userID, itemID string) string {
	b, _ := json.Marshal([]string{server, userID, itemID})
	return string(b)
}

func (s *Store) load() (map[string]Pending, error) {
	rows := map[string]Pending{}
	b, err := os.ReadFile(s.file())
	if errors.Is(err, os.ErrNotExist) {
		return rows, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(b, &rows); err != nil || rows == nil {
		return nil, errors.New("待同步进度文件损坏，已保留原文件")
	}
	return rows, nil
}

func (s *Store) save(rows map[string]Pending) error {
	b, err := json.Marshal(rows)
	if err != nil {
		return err
	}
	p := s.file()
	if err := os.MkdirAll(filepath.Dir(p), 0700); err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(p), ".progress-*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	if _, err := f.Write(b); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	return os.Rename(f.Name(), p)
}

func (s *Store) enter(ctx context.Context) error {
	select {
	case s.gate <- struct{}{}:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// Invalidate 停用旧播放的回写资格，保留历史待同步条目且不迁移目标。
func (s *Store) Invalidate() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.owners = map[string]*Link{}
	s.notice = ""
}

func (s *Store) Note(message string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.notice = message
}

func sameState(a, b emby.ProgressSnapshot) bool {
	return a.MediaSourceID == b.MediaSourceID && a.PositionTicks == b.PositionTicks && a.RunTimeTicks == b.RunTimeTicks && a.Played == b.Played
}

// syncPending 的调用方已持有网络 gate；不在网络请求期间持有磁盘锁。
func (s *Store) syncPending(ctx context.Context, client *emby.Client, session *emby.Session, pending Pending) (*emby.ProgressSnapshot, bool, error) {
	current, err := client.ProgressSnapshot(ctx, session, pending.ItemID, pending.State.MediaSourceID)
	if err != nil {
		return nil, pending.Conflict, err
	}
	if current.Revision != pending.State.Revision {
		if !pending.Conflict && sameState(*current, pending.State) {
			return current, false, nil // 丢失提交响应时，用实际回读确认。
		}
		return nil, true, errors.New("主服进度已变化，待同步记录已保留")
	}
	if pending.Conflict {
		return nil, true, errors.New("主服进度存在冲突，未自动覆盖")
	}
	out, err := client.SyncProgressSnapshot(ctx, session, pending.ItemID, pending.State)
	if err != nil {
		return nil, emby.StatusOf(err) == 409, err
	}
	if !sameState(*out, pending.State) || out.Revision == pending.State.Revision {
		return nil, false, errors.New("主服未确认准确保存进度")
	}
	verified, err := client.ProgressSnapshot(ctx, session, pending.ItemID, pending.State.MediaSourceID)
	if err != nil {
		return nil, false, err
	}
	if verified.Revision != out.Revision || !sameState(*verified, *out) {
		return nil, true, errors.New("主服在同步后出现了新进度，未自动覆盖")
	}
	return verified, false, nil
}

type Status struct {
	Pending   int    `json:"pending"`
	Conflicts int    `json:"conflicts"`
	Error     string `json:"error,omitempty"`
}

func (s *Store) Status() Status {
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.load()
	if err != nil {
		return Status{Error: err.Error()}
	}
	status := Status{Pending: len(rows), Error: s.notice}
	for _, row := range rows {
		if row.Conflict {
			status.Conflicts++
		}
	}
	return status
}

// Retry 只重试当前指定主服及用户的记录；旧主服记录继续保留。
func (s *Store) Retry(ctx context.Context, client *emby.Client, binding config.ProgressServer, session emby.Session, epoch uint64) error {
	if epoch != config.ProgressAccountEpoch() {
		return errors.New("主服账号已变化，重试已停止")
	}
	if err := s.enter(ctx); err != nil {
		return err
	}
	defer func() { <-s.gate }()
	s.mu.Lock()
	rows, err := s.load()
	s.mu.Unlock()
	if err != nil {
		return err
	}
	var firstErr error
	for k, row := range rows {
		if epoch != config.ProgressAccountEpoch() {
			return errors.New("主服账号已变化，重试已停止")
		}
		if row.Server != binding.Server || row.UserID != binding.UserID {
			continue
		}
		release, valid := config.HoldProgressAccount(epoch)
		if !valid {
			return errors.New("主服账号已变化，重试已停止")
		}
		out, conflict, syncErr := s.syncPending(ctx, client, &session, row)
		s.mu.Lock()
		latest, loadErr := s.load()
		if loadErr == nil {
			if syncErr == nil {
				delete(latest, k)
				if owner := s.owners[k]; owner != nil {
					owner.baseline = *out
					owner.blocked = false
				}
			} else {
				row.Conflict = conflict
				latest[k] = row
			}
			loadErr = s.save(latest)
		}
		s.mu.Unlock()
		release()
		if firstErr == nil {
			if syncErr != nil {
				firstErr = syncErr
			} else {
				firstErr = loadErr
			}
		}
		if ctx.Err() != nil {
			break
		}
	}
	return firstErr
}
