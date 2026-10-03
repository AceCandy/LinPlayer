package emby

import (
	"context"
	"fmt"
	"net/url"
	"strconv"
)

// FavoritePage 的游标按服务端原始条数前进,不受本地屏蔽影响。
type FavoritePage struct {
	Page
	NextIndex int  `json:"next_index"`
	HasMore   bool `json:"has_more"`
}

// FavoritesPage 按 MediaStationGo 支持的排序取单页;旧全量调用保留本地排序兼容。
func (c *Client) FavoritesPage(ctx context.Context, s *Session, start, limit int, sort, by, order string) (*FavoritePage, error) {
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
		"Filters": {"IsFavorite"}, "Recursive": {"true"}, "IncludeItemTypes": {"Movie,Series,Episode"},
		"Fields":     {"PrimaryImageAspectRatio,CommunityRating,DateCreated,DateLastMediaAdded,SortName"},
		"StartIndex": {strconv.Itoa(max(0, start))}, "Limit": {strconv.Itoa(min(max(1, limit), ServerPageCap))},
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
	out.Items = filterBlocked(out.Items)
	return out, nil
}
