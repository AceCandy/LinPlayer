package emby

import (
	"context"
	"fmt"
	"net/url"
	"strconv"

	"linplayer/core/bus"
)

// FavoritePage 的游标按服务端原始条数前进,不受本地屏蔽影响。
type FavoritePage struct {
	Page
	NextIndex int  `json:"next_index"`
	HasMore   bool `json:"has_more"`
}

// FavoritesPage 按 MediaStationGo 支持的排序取单页;旧全量调用保留本地排序兼容。
func (c *Client) FavoritesPage(ctx context.Context, s *Session, start, limit int, sort, by, order string) (*FavoritePage, error) {
	return c.watchStatePage(ctx, s, start, limit, sort, by, order, "")
}

// WatchedPage 读取当前用户跨库已看作品；类型只允许电影或整剧。
func (c *Client) WatchedPage(ctx context.Context, s *Session, kind string, start, limit int, sort, by, order string) (*FavoritePage, error) {
	if kind != "Movie" && kind != "Series" {
		return nil, bus.NewErr(bus.EInvalid, "历史已看类型必须是 Movie 或 Series")
	}
	return c.watchStatePage(ctx, s, start, limit, sort, by, order, kind)
}

func (c *Client) watchStatePage(ctx context.Context, s *Session, start, limit int, sort, by, order, kind string) (*FavoritePage, error) {
	if by == "" {
		by = map[string]string{"名称": "name", "评分": "rating", "年份": "year"}[sort]
		if by == "" {
			by = "updated"
		}
	}
	sortBy := map[string]string{"name": "SortName", "rating": "CommunityRating", "year": "ProductionYear", "updated": "DateLastContentAdded"}[by]
	direction := "Descending"
	if order == "asc" || (order == "" && by == "name") {
		direction = "Ascending"
	}
	q := url.Values{
		// 收藏类型由服务端决定，避免不支持的类型使整个收藏查询返回空。
		"Filters": {"IsFavorite,IsUnplayed"}, "Recursive": {"true"},
		"EnableTotalRecordCount": {"true"},
		"Fields":                 {"PrimaryImageAspectRatio,CommunityRating,DateCreated,DateLastMediaAdded,SortName"},
		"StartIndex":             {strconv.Itoa(max(0, start))}, "Limit": {strconv.Itoa(min(max(1, limit), ServerPageCap))},
	}
	if kind != "" {
		q.Set("Filters", "IsPlayed")
		q.Set("IncludeItemTypes", kind)
	}
	if sortBy != "" {
		q.Set("SortBy", sortBy)
		q.Set("SortOrder", direction)
	}
	u := fmt.Sprintf("%s/Users/%s/Items?%s", s.Server, url.PathEscape(s.UserID), q.Encode())
	p, err := c.fetchPage(ctx, s, u)
	if err != nil {
		return nil, err
	}
	got := len(p.Items)
	next := max(0, start) + got
	out := &FavoritePage{Page: *p, NextIndex: next, HasMore: got > 0 && (p.Total <= 0 || int64(next) < p.Total)}
	out.Items = filterWatchState(withoutLiuYing(filterBlocked(out.Items)), kind != "")
	if kind != "" {
		items := out.Items[:0]
		for _, item := range out.Items {
			if item.Type == kind {
				items = append(items, item)
			}
		}
		out.Items = items
	}
	return out, nil
}

// 明确的未看集数优先于容器标记；缺统计时不把默认零推导为完成。
func filterWatchState(items []Item, watched bool) []Item {
	out := items[:0]
	for _, item := range items {
		played := item.Played
		if item.Type == "Series" && item.UnplayedCountKnown {
			played = item.UnplayedItemCount == 0
		}
		if played == watched {
			out = append(out, item)
		}
	}
	return out
}
