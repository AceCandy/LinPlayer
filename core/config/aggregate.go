package config

import "encoding/json"

// AllowAggregate 「允许聚合」:一个开关管聚合搜索、聚合视界、换源(D233 D235)。
// 用户改过就听用户的;没改过:Emby 开,其它源关。
func (a Account) AllowAggregate() bool {
	if raw := a.RestValue("aggregate"); raw != nil {
		var v bool
		if json.Unmarshal(raw, &v) == nil {
			return v
		}
	}
	return !a.IsFileBrowse()
}

// SetAllowAggregate 用户改「允许聚合」。
func (a *Account) SetAllowAggregate(v bool) { a.SetRestValue("aggregate", v) }
