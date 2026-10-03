package download

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"strings"

	"linplayer/core/config"
)

// migrateAuth 只认旧下载端点和原令牌对应的账号,不回退到当前活跃账号。
func migrateAuth(it *Item) {
	u, err := url.Parse(it.URL)
	if err == nil && u.Query().Get("api_key") != "" {
		for _, acc := range config.Current().AccountList {
			if acc.IsFileBrowse() || acc.Token != u.Query().Get("api_key") {
				continue
			}
			for _, base := range accountBases(acc) {
				if strings.Split(it.URL, "?")[0] == strings.TrimRight(base, "/")+"/Items/"+url.PathEscape(it.ItemID)+"/Download" {
					it.ServerID, it.UserID = acc.Server, acc.UserID
				}
			}
		}
		it.URL = ""
		it.Error = nil // 旧网络错误可能带有完整认证地址。
		if it.ServerID == "" && it.Status != StatusCompleted && it.Status != StatusCanceled {
			it.Status = StatusFailed
			msg := "无法确认原下载账号,请重新加入下载"
			it.Error = &msg
		}
	}
	if it.ServerID != "" {
		it.URL = ""
	}
	if it.PosterURL != nil {
		clean := stripCredentials(*it.PosterURL)
		it.PosterURL = &clean
	}
}

func accountBases(acc config.Account) []string {
	bases := []string{acc.Server, acc.ActiveLineURL()}
	for _, line := range acc.Lines {
		bases = append(bases, line.URL)
	}
	return bases
}

func stripCredentials(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	u.User = nil
	q := u.Query()
	for key := range q {
		switch strings.ToLower(key) {
		case "api_key", "token", "access_token", "authorization":
			q.Del(key)
		}
	}
	u.RawQuery = q.Encode()
	return u.String()
}

// 展示时补当前封面凭据,持久化仍保存无凭据地址。
func posterForDisplay(it *Item) *string {
	if it.PosterURL == nil || it.ServerID == "" {
		return it.PosterURL
	}
	acc := config.Current().Find(it.ServerID)
	if acc == nil || acc.IsFileBrowse() || acc.UserID != it.UserID || acc.Token == "" {
		return nil
	}
	base := ""
	for _, candidate := range accountBases(*acc) {
		candidate = strings.TrimRight(candidate, "/")
		if len(candidate) > len(base) && strings.HasPrefix(*it.PosterURL, candidate+"/") {
			base = candidate
		}
	}
	if base == "" {
		return it.PosterURL
	}
	u, err := url.Parse(strings.TrimRight(acc.ActiveLineURL(), "/") + strings.TrimPrefix(*it.PosterURL, base))
	if err != nil {
		return nil
	}
	q := u.Query()
	q.Set("api_key", acc.Token)
	u.RawQuery = q.Encode()
	poster := u.String()
	return &poster
}

// downloadRequest 每次重试读取原账号的当前凭据,同服换用户也必须重新入队。
func downloadRequest(ctx context.Context, it *Item) (*http.Request, error) {
	if it.ServerID == "" {
		if it.URL == "" {
			return nil, permanent(errors.New("原下载账号不可用,请重新加入下载"))
		}
		return http.NewRequestWithContext(ctx, http.MethodGet, it.URL, nil)
	}
	acc := config.Current().Find(it.ServerID)
	if acc == nil || acc.IsFileBrowse() || acc.UserID != it.UserID || strings.TrimSpace(acc.Token) == "" {
		return nil, permanent(errors.New("原下载账号已移除或身份已变更,请重新登录原账号或重新加入下载"))
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		strings.TrimRight(acc.ActiveLineURL(), "/")+"/Items/"+url.PathEscape(it.ItemID)+"/Download", nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-Emby-Token", acc.Token)
	return req, nil
}

// 下载端点可能重定向到外部存储;账号令牌不能随自定义头转发。
func downloadClient(c *http.Client) *http.Client {
	copy := *c
	copy.CheckRedirect = func(req *http.Request, via []*http.Request) error {
		if len(via) > 0 && (req.URL.Scheme != via[0].URL.Scheme || req.URL.Host != via[0].URL.Host) {
			req.Header.Del("X-Emby-Token")
		}
		if c.CheckRedirect != nil {
			return c.CheckRedirect(req, via)
		}
		if len(via) >= 10 {
			return errors.New("重定向次数过多")
		}
		return nil
	}
	return &copy
}
