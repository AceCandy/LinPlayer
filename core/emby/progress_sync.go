package emby

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
)

// ProgressSnapshot 是主进度同步扩展；零位置和 false 均是确切状态。
type ProgressSnapshot struct {
	MediaSourceID string `json:"MediaSourceId"`
	PositionTicks int64  `json:"PositionTicks"`
	RunTimeTicks  int64  `json:"RunTimeTicks"`
	Played        bool   `json:"Played"`
	Revision      string `json:"Revision"`
}

func progressURL(s *Session, itemID string) string {
	return s.Server + "/Users/" + url.PathEscape(s.UserID) + "/Items/" + url.PathEscape(itemID) + "/PlaybackProgress"
}

// ProgressMatchItems 必须确认候选完整；列表接口的下界总数不能证明唯一匹配。
func (c *Client) ProgressMatchItems(ctx context.Context, s *Session, typ, provider, name string) ([]Item, error) {
	u := searchURL(s, name, []string{typ}, ServerPageCap, "")
	if caps := c.capabilities(ctx, s); caps == nil || caps.ProviderLookup {
		u = fmt.Sprintf("%s/Users/%s/Items?Recursive=true&IncludeItemTypes=%s&AnyProviderIdEquals=%s&Fields=%s&Limit=%d",
			s.Server, url.PathEscape(s.UserID), url.QueryEscape(typ), url.QueryEscape("tmdb."+provider), HistoryFields, ServerPageCap)
	}
	page, err := c.fetchPage(ctx, s, u+"&EnableTotalRecordCount=true")
	if err != nil {
		return nil, err
	}
	if page.Total > int64(len(page.Items)) || len(page.Items) >= ServerPageCap {
		return nil, errors.New("主服匹配候选未完整返回")
	}
	return page.Items, nil
}

func (c *Client) ProgressMatchEpisodes(ctx context.Context, s *Session, parentID string, start int) (*Page, error) {
	u := fmt.Sprintf("%s/Users/%s/Items?ParentId=%s&IncludeItemTypes=Episode&Recursive=true&SortBy=ParentIndexNumber,IndexNumber&SortOrder=Ascending&Fields=%s&StartIndex=%d&Limit=%d&EnableTotalRecordCount=true",
		s.Server, url.PathEscape(s.UserID), url.QueryEscape(parentID), HistoryFields, start, ServerPageCap)
	return c.fetchPage(ctx, s, u)
}

func (c *Client) ProgressMatchSeasons(ctx context.Context, s *Session, seriesID string) ([]SeasonInfo, error) {
	u := fmt.Sprintf("%s/Users/%s/Items?ParentId=%s&IncludeItemTypes=Season&Limit=%d&EnableTotalRecordCount=true",
		s.Server, url.PathEscape(s.UserID), url.QueryEscape(seriesID), ServerPageCap)
	b, err := c.getBytes(ctx, s, u)
	if err != nil {
		return nil, err
	}
	var page struct {
		Items []struct {
			ID    string `json:"Id"`
			Index *int64 `json:"IndexNumber"`
		} `json:"Items"`
		Total int64 `json:"TotalRecordCount"`
	}
	if err := json.Unmarshal(b, &page); err != nil {
		return nil, err
	}
	if page.Total > int64(len(page.Items)) || len(page.Items) >= ServerPageCap {
		return nil, errors.New("主服季列表未完整返回")
	}
	seasons := make([]SeasonInfo, 0, len(page.Items))
	for _, item := range page.Items {
		seasons = append(seasons, SeasonInfo{ID: item.ID, IndexNo: item.Index})
	}
	return seasons, nil
}

func decodeProgress(b []byte) (*ProgressSnapshot, error) {
	var raw struct {
		MediaSourceID string `json:"MediaSourceId"`
		PositionTicks *int64 `json:"PositionTicks"`
		RunTimeTicks  *int64 `json:"RunTimeTicks"`
		Played        *bool  `json:"Played"`
		Revision      string `json:"Revision"`
	}
	if err := json.Unmarshal(b, &raw); err != nil {
		return nil, fmt.Errorf("解析主服进度失败: %w", err)
	}
	revision, err := strconv.ParseInt(raw.Revision, 10, 64)
	if err != nil || revision < 0 || raw.MediaSourceID == "" || raw.PositionTicks == nil || raw.RunTimeTicks == nil || raw.Played == nil || *raw.PositionTicks < 0 || *raw.RunTimeTicks < 0 {
		return nil, errors.New("主服没有返回完整进度和版本")
	}
	return &ProgressSnapshot{raw.MediaSourceID, *raw.PositionTicks, *raw.RunTimeTicks, *raw.Played, raw.Revision}, nil
}

func (c *Client) ProgressSnapshot(ctx context.Context, s *Session, itemID, mediaSourceID string) (*ProgressSnapshot, error) {
	u := progressURL(s, itemID)
	if mediaSourceID != "" {
		u += "?MediaSourceId=" + url.QueryEscape(mediaSourceID)
	}
	b, err := c.getBytes(ctx, s, u)
	if err != nil {
		return nil, err
	}
	return decodeProgress(b)
}

// SyncProgressSnapshot 使用主服版本进行条件写入，并核验服务端确实回了完整提交状态。
func (c *Client) SyncProgressSnapshot(ctx context.Context, s *Session, itemID string, state ProgressSnapshot) (*ProgressSnapshot, error) {
	body, err := json.Marshal(map[string]any{"MediaSourceId": state.MediaSourceID, "ExpectedRevision": state.Revision,
		"PositionTicks": state.PositionTicks, "RunTimeTicks": state.RunTimeTicks, "Played": state.Played})
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, progressURL(s, itemID), bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-Emby-Token", s.Token)
	req.Header.Set("X-Emby-Authorization", c.authHeader(s.DeviceID))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", c.UA)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, errors.New("主服同步网络请求失败")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, &StatusError{Status: resp.StatusCode, What: "主服进度同步"}
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		return nil, err
	}
	return decodeProgress(b)
}
