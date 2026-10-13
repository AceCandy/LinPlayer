package danmaku

import (
	"context"
	"encoding/xml"
	"fmt"
	"io"
	"math"
	"sort"
	"strconv"
	"strings"

	"linplayer/core/config"
	"linplayer/core/emby"
)

// resolve 并行读取服务器原生弹幕与用户选择的匹配源，失败只影响对应的一路。
func resolve(ctx context.Context, a map[string]any) (ResolveResult, error) {
	in, err := matchInputOf(a)
	if err != nil {
		return ResolveResult{Items: []Comment{}}, err
	}
	type serverResult struct {
		items []Comment
		err   error
	}
	done := make(chan serverResult, 1)
	go func() { items, err := serverComments(ctx, a, in.ItemID); done <- serverResult{items, err} }()
	result, sourceErr := resolveSource(ctx, a)
	server := <-done
	if err := ctx.Err(); err != nil {
		return ResolveResult{Items: []Comment{}}, err
	}
	if sourceErr != nil {
		result.Warnings = append(result.Warnings, "匹配源："+sourceErr.Error())
	}
	if server.err != nil {
		result.Warnings = append(result.Warnings, "服务器弹幕："+server.err.Error())
	}
	result.Items = append([]Comment{}, result.Items...)
	if len(server.items) > 0 {
		result.Items = append(result.Items, server.items...)
		if result.Match == nil {
			result.Match = &MatchCandidate{SourceID: "emby-raw", SourceName: "服务器弹幕", AnimeTitle: in.Title, EpisodeID: in.ItemID}
		} else {
			cp := *result.Match
			cp.SourceName += " + 服务器弹幕"
			result.Match = &cp
		}
	}
	if len(result.Items) == 0 && len(result.Warnings) > 0 {
		return result, upstream(fmt.Errorf("%s", strings.Join(result.Warnings, "；")))
	}
	result.Items = ApplyFilterAndDedup(result.Items, filterOptionsOf(a))
	sort.SliceStable(result.Items, func(i, j int) bool { return result.Items[i].Time < result.Items[j].Time })
	return result, nil
}

func serverComments(ctx context.Context, a map[string]any, itemID string) ([]Comment, error) {
	if itemID == "" {
		return nil, nil
	}
	s := emby.Session{Server: str(a, "server"), Token: str(a, "token"), UserID: str(a, "user_id")}
	if id := str(a, "server_id"); id != "" {
		var ok bool
		s.Server, s.Token, s.UserID, s.DeviceID, ok = config.Current().SessionOf(id)
		if !ok {
			return nil, fmt.Errorf("播放账号不可用")
		}
	}
	if s.Server == "" || s.Token == "" || s.UserID == "" {
		return nil, nil
	}
	timed, cancel := context.WithTimeout(ctx, sourceTimeout)
	defer cancel()
	client := emby.Shared()
	if client == nil {
		client = emby.NewClient("dev")
	}
	raw, err := client.RawDanmaku(timed, &s, itemID)
	if err != nil {
		return nil, err
	}
	return parseServerXML(string(raw))
}

// parseServerXML 区分合法空弹幕与错误页面；颜色在p的第4项，ID保持字符串精度。
func parseServerXML(raw string) ([]Comment, error) {
	if strings.TrimSpace(raw) == "" {
		return nil, nil
	}
	var doc struct {
		XMLName xml.Name `xml:"i"`
		Entries []struct {
			P    string `xml:"p,attr"`
			Text string `xml:",chardata"`
		} `xml:"d"`
	}
	decoder := xml.NewDecoder(strings.NewReader(raw))
	if err := decoder.Decode(&doc); err != nil {
		return nil, fmt.Errorf("服务器弹幕 XML 无效")
	}
	for {
		token, err := decoder.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("服务器弹幕 XML 无效")
		}
		if text, ok := token.(xml.CharData); !ok || strings.TrimSpace(string(text)) != "" {
			return nil, fmt.Errorf("服务器弹幕 XML 存在多余内容")
		}
	}
	items := make([]Comment, 0, len(doc.Entries))
	for _, entry := range doc.Entries {
		p := strings.Split(entry.P, ",")
		if len(p) < 4 || strings.TrimSpace(entry.Text) == "" {
			continue
		}
		t, err := strconv.ParseFloat(p[0], 64)
		if err != nil || math.IsNaN(t) || math.IsInf(t, 0) || t < 0 {
			continue
		}
		mode, _ := strconv.Atoi(p[1])
		color, err := strconv.Atoi(p[3])
		if err != nil || color < 0 || color > white {
			color = white
		}
		c := Comment{Time: t, Text: entry.Text, Mode: normalizeMode(mode), Color: color, Source: "emby-raw", Count: 1}
		if len(p) > 7 && p[7] != "" {
			id := p[7]
			c.CID = &id
		}
		items = append(items, c)
	}
	return items, nil
}
