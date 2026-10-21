package emby

import (
	"context"
	"fmt"
	"sort"

	"linplayer/core/bus"
)

// SeriesPlaybackTarget 为首页主播放按钮实时选集，不使用展示缓存的已看或续播状态。
func (c *Client) SeriesPlaybackTarget(ctx context.Context, s *Session, seriesID string) (*Item, error) {
	if seriesID == "" {
		return nil, bus.NewErr(bus.EInvalid, "缺少剧集编号")
	}
	seasons, err := c.Seasons(ctx, s, seriesID)
	if err != nil {
		return nil, err
	}
	parents := []string{}
	for _, season := range seasons {
		if season.ID == "" {
			return nil, bus.NewErr(bus.EUpstream, "分季列表缺少编号")
		}
		parents = append(parents, season.ID)
	}
	if len(parents) == 0 {
		parents = append(parents, seriesID)
	}
	var first *Item
	for _, parent := range parents {
		page, err := c.AllSeasonEpisodes(ctx, s, parent)
		if err != nil {
			return nil, err
		}
		for _, item := range filterBlocked(page.Items) {
			if item.Type != "Episode" {
				return nil, bus.NewErr(bus.EUpstream, "分集列表包含不可播放的条目")
			}
			if first == nil {
				copy := item
				first = &copy
			}
			if !item.Played {
				return &item, nil
			}
		}
	}
	if first == nil {
		return nil, bus.NewErr(bus.ENotFound, "没有可播放的分集")
	}
	return first, nil
}

// AllSeasonEpisodes 只发布完整季；重复页或提前空页不能成为可用于连播的队列。
func (c *Client) AllSeasonEpisodes(ctx context.Context, s *Session, parent string) (*Page, error) {
	result := &Page{Items: []Item{}}
	seen := map[string]bool{}
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		page, err := c.SeasonEpisodes(ctx, s, parent, len(result.Items), 200)
		if err != nil {
			return nil, err
		}
		if !page.itemsKnown {
			return nil, bus.NewErr(bus.EUpstream, "分集列表缺少有效数组")
		}
		for _, item := range page.Items {
			if item.ID == "" || seen[item.ID] {
				return nil, fmt.Errorf("分集分页返回重复或无效内容，请重试")
			}
			seen[item.ID] = true
		}
		result.Items = append(result.Items, page.Items...)
		result.Total = page.Total
		if len(page.Items) == 0 {
			if page.totalKnown && page.Total > int64(len(result.Items)) {
				return nil, fmt.Errorf("分集列表尚未完整，请重试")
			}
			result.Total = int64(len(result.Items))
			return result, nil
		}
		if page.totalKnown && page.Total > 0 && int64(len(result.Items)) >= page.Total {
			return result, nil
		}
	}
}

// PlaybackSeason 从实时条目确认所属季；未知队列与真实季末必须区分。
func (c *Client) PlaybackSeason(ctx context.Context, s *Session, itemID string) ([]Item, error) {
	if itemID == "" {
		return nil, fmt.Errorf("缺少条目编号")
	}
	detail, err := c.Detail(ctx, s, itemID, false)
	if err != nil {
		return nil, err
	}
	if detail.Type != "Episode" {
		return []Item{}, nil
	}
	parent := deref(detail.SeasonID)
	if parent == "" && detail.SeasonNo != nil {
		parent = deref(detail.SeriesID)
	}
	if parent == "" {
		return nil, fmt.Errorf("无法确认当前季，请重试")
	}
	page, err := c.AllSeasonEpisodes(ctx, s, parent)
	if err != nil {
		return nil, err
	}
	items := []Item{}
	found := false
	for _, item := range page.Items {
		if detail.SeasonNo != nil && ((item.SeasonNo != nil && *item.SeasonNo != *detail.SeasonNo) || (deref(detail.SeasonID) == "" && item.SeasonNo == nil)) {
			continue
		}
		items = append(items, item)
		found = found || item.ID == itemID
	}
	if !found {
		return nil, fmt.Errorf("当前集不在分集列表中，请重试")
	}
	sort.SliceStable(items, func(i, j int) bool {
		if items[i].EpisodeNo == nil {
			return false
		}
		return items[j].EpisodeNo == nil || *items[i].EpisodeNo < *items[j].EpisodeNo
	})
	return items, nil
}
