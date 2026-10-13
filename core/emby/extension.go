package emby

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"linplayer/core/bus"
	"linplayer/core/net/localserve"
)

// extensionBase 保留反代前缀，只移除普通 Emby API 的尾部路径。
func extensionBase(server string) (string, error) {
	u, err := url.Parse(server)
	if err != nil || u.Host == "" || u.User != nil || (u.Scheme != "http" && u.Scheme != "https") {
		return "", bus.NewErr(bus.EInvalid, "服务器地址无效")
	}
	u.Path = strings.TrimSuffix(strings.TrimRight(u.Path, "/"), "/emby") + "/emby-ext/v1/"
	u.RawPath, u.RawQuery, u.Fragment = "", "", ""
	return u.String(), nil
}

// extensionJSON 不跟随跳转，避免将 Emby 凭据带到第三方或登录页。
func (c *Client) extensionJSON(ctx context.Context, s *Session, path string, body any, out any) error {
	base, err := extensionBase(s.Server)
	if err != nil {
		return err
	}
	method := http.MethodGet
	var payload io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		method, payload = http.MethodPost, bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, base+path, payload)
	if err != nil {
		return bus.NewErr(bus.EInvalid, "扩展接口地址无效")
	}
	req.Header.Set("X-Emby-Token", s.Token)
	req.Header.Set("User-Agent", c.UA)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	client := *c.HTTP
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	resp, err := client.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return &bus.Err{Code: bus.ENetwork, Msg: "无法连接服务器扩展接口", Retryable: true}
	}
	defer resp.Body.Close()
	switch resp.StatusCode {
	case 200:
	case 401:
		return bus.NewErr(bus.EAuth, "登录已失效，请重新登录")
	case 403:
		return bus.NewErr(bus.EPermission, "当前账号无权使用榜单与日历")
	case 404:
		if path == "capabilities" {
			return bus.NewErr(bus.EUnsupported, "当前服务器不支持 MSG 榜单与日历扩展")
		}
		return bus.NewErr(bus.ENotFound, "服务器未提供这项扩展内容")
	case 400:
		return bus.NewErr(bus.EInvalid, "服务器拒绝了扩展查询参数")
	default:
		return &bus.Err{Code: bus.EInternal, Msg: fmt.Sprintf("扩展接口返回 HTTP %d", resp.StatusCode), Retryable: resp.StatusCode >= 500}
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 4*1024*1024+1))
	if err != nil {
		return &bus.Err{Code: bus.ENetwork, Msg: "扩展数据读取失败", Retryable: true}
	}
	if len(b) > 4*1024*1024 || json.Unmarshal(b, out) != nil {
		if path == "capabilities" && !json.Valid(b) {
			return bus.NewErr(bus.EUnsupported, "当前服务器未提供有效的 MSG 扩展协议")
		}
		return bus.NewErr(bus.EInvalid, "服务器扩展数据格式无效")
	}
	return nil
}

type extensionCapabilities struct {
	Danmaku struct {
		Enabled bool   `json:"enabled"`
		Format  string `json:"format"`
	} `json:"danmaku"`
	Version  string `json:"version"`
	Rankings bool   `json:"rankings"`
	Calendar struct {
		Enabled      bool   `json:"enabled"`
		Source       string `json:"source"`
		Mode         string `json:"mode"`
		Timezone     string `json:"timezone"`
		ExactAirtime bool   `json:"exact_airtime"`
	} `json:"calendar"`
	LibraryMatching struct {
		Sources  []string `json:"sources"`
		MaxItems int      `json:"max_items"`
		Identity string   `json:"identity"`
		Scope    string   `json:"scope"`
	} `json:"library_matching"`
}

func (c *Client) extensionCapability(ctx context.Context, s *Session, feature string) (*extensionCapabilities, error) {
	var caps extensionCapabilities
	if err := c.extensionJSON(ctx, s, "capabilities", nil, &caps); err != nil {
		return nil, err
	}
	if caps.Version != "1" {
		return nil, bus.NewErr(bus.EUnsupported, "当前服务器不支持 MSG v1 扩展协议")
	}
	if feature == "rankings" && !caps.Rankings || feature == "calendar" && !caps.Calendar.Enabled || feature == "matching" && len(caps.LibraryMatching.Sources) == 0 {
		return nil, bus.NewErr(bus.EUnsupported, "服务器尚未开启这项扩展能力")
	}
	return &caps, nil
}

// extensionEntry 保留来源身份，展示快照不能携带媒体库匹配或播放授权。
type extensionEntry struct {
	Source       string   `json:"source"`
	MediaType    string   `json:"media_type"`
	Title        string   `json:"title"`
	OriginalName string   `json:"original_name"`
	PosterURL    string   `json:"poster_url"`
	Year         int      `json:"year"`
	Rating       *float64 `json:"rating"`
	TMDBID       int64    `json:"tmdb_id"`
	DoubanID     string   `json:"douban_id"`
	BangumiID    int64    `json:"bangumi_id"`
	Weekday      int      `json:"weekday"`
}

func (e extensionEntry) view(s *Session, rank int) map[string]any {
	id := ""
	switch e.Source {
	case "tmdb":
		if e.TMDBID > 0 {
			id = strconv.FormatInt(e.TMDBID, 10)
		}
	case "douban":
		id = e.DoubanID
	case "bangumi":
		if e.BangumiID > 0 {
			id = strconv.FormatInt(e.BangumiID, 10)
		}
	}
	picture := ""
	if base, err := extensionBase(s.Server); err == nil && e.PosterURL != "" {
		origin, _ := url.Parse(base)
		ref, err := url.Parse(e.PosterURL)
		if err == nil {
			// MSG 的根相对海报路径需带上部署的反代前缀。
			if strings.HasPrefix(ref.Path, "/emby-ext/v1/") && ref.Host == "" {
				ref.Path = strings.TrimSuffix(origin.Path, "/emby-ext/v1/") + ref.Path
			}
			target := origin.ResolveReference(ref)
			if target.User == nil && (target.Scheme == "http" || target.Scheme == "https") {
				picture = target.String()
				if target.Scheme != origin.Scheme || target.Host != origin.Host {
					localserve.AllowDefault(target.Scheme+"://"+target.Host, nil)
				}
			}
		}
	}
	subtitle := e.OriginalName
	if e.Year > 0 {
		subtitle = strconv.Itoa(e.Year)
	}
	return map[string]any{"id": e.Source + ":" + e.MediaType + ":" + id, "source": e.Source, "external_id": id, "media_type": e.MediaType,
		"title": e.Title, "subtitle": subtitle, "image_url": picture, "rating": e.Rating, "rank": rank, "weekday": e.Weekday}
}

func registerExtensionCommands(list func(string, func(context.Context, *Session, map[string]any) (any, error))) {
	list("emby.extCapabilities", func(ctx context.Context, s *Session, a map[string]any) (any, error) {
		return defaultClient.extensionCapability(ctx, s, "")
	})
	list("emby.extRankings", func(ctx context.Context, s *Session, a map[string]any) (any, error) {
		if _, err := defaultClient.extensionCapability(ctx, s, "rankings"); err != nil {
			return nil, err
		}
		var data struct {
			Items []struct {
				ID      string `json:"id"`
				Name    string `json:"name"`
				Source  string `json:"source"`
				Enabled bool   `json:"enabled"`
			} `json:"items"`
		}
		if err := defaultClient.extensionJSON(ctx, s, "rankings", nil, &data); err != nil {
			return nil, err
		}
		if data.Items == nil {
			return nil, bus.NewErr(bus.EInvalid, "服务器扩展列表格式无效")
		}
		out := []map[string]any{}
		for _, v := range data.Items {
			if v.Enabled {
				if v.ID == "" || v.Name == "" || v.Source == "" {
					return nil, bus.NewErr(bus.EInvalid, "服务器榜单目录格式无效")
				}
				out = append(out, map[string]any{"id": v.ID, "label": v.Name, "source": v.Source, "group": v.Source})
			}
		}
		return out, nil
	})
	list("emby.extRankingItems", func(ctx context.Context, s *Session, a map[string]any) (any, error) {
		id, page := str(a, "category_id"), intArg(a, "page", 1)
		if id == "" || strings.ContainsAny(id, "/?#\\") || id == "." || id == ".." || page < 1 || page > 500 {
			return nil, bus.NewErr(bus.EInvalid, "榜单或页码无效")
		}
		if _, err := defaultClient.extensionCapability(ctx, s, "rankings"); err != nil {
			return nil, err
		}
		var data struct {
			Items    []extensionEntry `json:"items"`
			PageSize int              `json:"page_size"`
		}
		if err := defaultClient.extensionJSON(ctx, s, "rankings/"+url.PathEscape(id)+"/items?page="+strconv.Itoa(page), nil, &data); err != nil {
			return nil, err
		}
		if data.Items == nil || data.PageSize < 1 {
			return nil, bus.NewErr(bus.EInvalid, "服务器榜单分页格式无效")
		}
		out := []map[string]any{}
		for i, v := range data.Items {
			out = append(out, v.view(s, (page-1)*data.PageSize+i+1))
		}
		return out, nil
	})
	list("emby.extCalendar", func(ctx context.Context, s *Session, a map[string]any) (any, error) {
		if _, err := defaultClient.extensionCapability(ctx, s, "calendar"); err != nil {
			return nil, err
		}
		var data struct {
			Items []extensionEntry `json:"items"`
		}
		if err := defaultClient.extensionJSON(ctx, s, "calendar", nil, &data); err != nil {
			return nil, err
		}
		if data.Items == nil {
			return nil, bus.NewErr(bus.EInvalid, "服务器扩展列表格式无效")
		}
		out := []map[string]any{}
		for _, v := range data.Items {
			out = append(out, v.view(s, 0))
		}
		return out, nil
	})
	// 点击时实时匹配；多匹配不能任取第一条，必须让用户知道歧义。
	list("emby.extResolve", func(ctx context.Context, s *Session, a map[string]any) (any, error) {
		source, id, kind := str(a, "source"), str(a, "external_id"), str(a, "media_type")
		if id == "" || (source != "tmdb" && source != "douban" && source != "bangumi") || (kind != "movie" && kind != "tv" && kind != "series" && kind != "anime") {
			return nil, bus.NewErr(bus.EInvalid, "作品缺少有效的来源标识，无法精确匹配")
		}
		caps, err := defaultClient.extensionCapability(ctx, s, "matching")
		if err != nil {
			return nil, err
		}
		supported := false
		for _, v := range caps.LibraryMatching.Sources {
			if v == source {
				supported = true
			}
		}
		if !supported {
			return nil, bus.NewErr(bus.EUnsupported, "服务器不支持此来源的媒体匹配")
		}
		body := map[string]any{"items": []any{map[string]any{"key": "item", "source": source, "external_id": id, "media_type": kind}}}
		var data struct {
			Items []struct {
				Key     string `json:"key"`
				Matches []struct {
					ItemID   string `json:"item_id"`
					ItemType string `json:"item_type"`
				} `json:"matches"`
			} `json:"items"`
		}
		if err := defaultClient.extensionJSON(ctx, s, "library/matches", body, &data); err != nil {
			return nil, err
		}
		if len(data.Items) != 1 || data.Items[0].Key != "item" {
			return nil, bus.NewErr(bus.EInvalid, "服务器媒体匹配结果无效")
		}
		matches := data.Items[0].Matches
		if len(matches) == 0 {
			return nil, bus.NewErr(bus.ENotFound, "该作品尚未匹配到当前媒体库")
		}
		if len(matches) != 1 {
			return nil, bus.NewErr(bus.EInvalid, "该作品匹配到多个媒体，请在媒体库中选择")
		}
		if matches[0].ItemID == "" || (matches[0].ItemType != "Movie" && matches[0].ItemType != "Series" && matches[0].ItemType != "Episode") {
			return nil, bus.NewErr(bus.EInvalid, "服务器返回的媒体标识无效")
		}
		return matches[0], nil
	})
}
