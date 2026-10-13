package danmaku

import "sync"

// 兼容旧宿主；新宿主从 resolve 的单次响应读取匹配信息。
var (
	lastMu    sync.Mutex
	lastMatch *MatchCandidate
)

func setLastMatch(c *MatchCandidate) {
	lastMu.Lock()
	defer lastMu.Unlock()
	if c == nil {
		lastMatch = nil
		return
	}
	cp := *c
	lastMatch = &cp
}
