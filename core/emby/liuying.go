package emby

import (
	"context"
	"net/url"
	"strconv"
	"strings"

	"linplayer/core/bus"
)

type liuYingCard struct {
	ItemID         string   `json:"item_id"`
	Title          string   `json:"title"`
	Author         string   `json:"author"`
	Feed           string   `json:"feed"`
	Tab            string   `json:"tab"`
	Channel        string   `json:"channel"`
	ExposureLabels []string `json:"exposure_labels"`
	Favorite       bool     `json:"favorite"`
	Played         bool     `json:"played"`
}

func registerLiuYingCommands(list func(string, func(context.Context, *Session, map[string]any) (any, error))) {
	list("emby.extLiuYingItems", func(ctx context.Context, s *Session, a map[string]any) (any, error) {
		if _, err := defaultClient.extensionCapability(ctx, s, "liuying"); err != nil {
			return nil, err
		}
		if adult := AdultFilter(ctx); adult == nil || !*adult {
			return nil, bus.NewErr(bus.EPermission, "流影仅在隐私空间浏览")
		}
		feed := str(a, "feed")
		if feed == "" {
			feed = "normal"
		}
		offset := intArg(a, "offset", 0)
		q := url.Values{"feed": {feed}, "tab": {str(a, "tab")}, "channel": {str(a, "channel")}, "exposure": {str(a, "exposure")}, "seed": {str(a, "seed")}, "offset": {strconv.Itoa(offset)}, "favorite": {strconv.FormatBool(a["favorite"] == true)}}
		var out struct {
			Items    []liuYingCard `json:"items"`
			HasMore  bool          `json:"has_more"`
			Offset   int           `json:"offset"`
			PageSize int           `json:"page_size"`
		}
		if err := defaultClient.extensionJSON(ctx, s, "liuying/items?"+q.Encode(), nil, &out); err != nil {
			return nil, err
		}
		if out.Items == nil || len(out.Items) > 12 || out.PageSize != 12 || out.Offset != offset {
			return nil, bus.NewErr(bus.EInvalid, "流影列表格式无效")
		}
		for _, item := range out.Items {
			if !strings.HasPrefix(item.ItemID, "ly-work-") || item.Feed != feed {
				return nil, bus.NewErr(bus.EInvalid, "流影作品身份无效")
			}
		}
		return out, nil
	})
	list("emby.extLiuYingLabels", func(ctx context.Context, s *Session, a map[string]any) (any, error) {
		if _, err := defaultClient.extensionCapability(ctx, s, "liuying"); err != nil {
			return nil, err
		}
		if adult := AdultFilter(ctx); adult == nil || !*adult {
			return nil, bus.NewErr(bus.EPermission, "流影仅在隐私空间浏览")
		}
		var out struct {
			Items []struct {
				Name  string `json:"name"`
				Count int    `json:"count"`
			} `json:"items"`
		}
		if err := defaultClient.extensionJSON(ctx, s, "liuying/labels", nil, &out); err != nil {
			return nil, err
		}
		if out.Items == nil {
			return nil, bus.NewErr(bus.EInvalid, "流影标签格式无效")
		}
		titles := map[string]string{"tg-3398168133": "抖奶", "tg-2683421587-3412514649": "凸点", "upper_cleavage_exposure": "露上胸", "under_breast_exposure": "露下胸", "side_breast_exposure": "露左右", "none": "全不露"}
		rows := []map[string]any{}
		for _, label := range out.Items {
			title := titles[label.Name]
			if title == "" {
				title = label.Name
			}
			kind := "exposure"
			if strings.HasPrefix(label.Name, "tg-") {
				kind = "channel"
			}
			rows = append(rows, map[string]any{"name": label.Name, "title": title, "kind": kind, "count": label.Count})
		}
		return rows, nil
	})
}

// 首页仅隐藏已知流影身份，不改变库浏览、收藏或服务端返回。
func withoutLiuYing(items []Item) []Item {
	out := make([]Item, 0, len(items))
	for _, item := range items {
		if item.LibraryType != nil && *item.LibraryType == "liuying" || strings.HasPrefix(item.ID, "ly-work-") {
			continue
		}
		out = append(out, item)
	}
	return out
}
