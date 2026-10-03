package emby

// 媒体库详情的筛选分面(类型 / 标签 / 年份 / 出品方 / 分级)。
//

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"sort"
	"strings"
	"sync"
)

// Filters 一个库能筛什么。
type Filters struct {
	Genres          []string `json:"genres"`
	Tags            []string `json:"tags"`
	Years           []int64  `json:"years"`
	Studios         []string `json:"studios"`
	OfficialRatings []string `json:"official_ratings"`
	Unavailable     []string `json:"unavailable,omitempty"` // 暂时失败的分面;404 不列入重试。
}

// FiltersOf 取某库的筛选分面。
//
// ★ 端点可用性是**实测**出来的,不是照文档抄的(某 fork,Emby 4.9.3):
//
//	/Items/Filters、/Users/{u}/Items/Filters2 → 404(旧栈注释里记的坑,复现了)
//	/Genres、/Studios                          → 200 ✅
//	/Years、/Tags、/OfficialRatings            → 404 ❌(旧栈也在拉这三个并**吞错**,
//	                                              所以旧版的年份/标签分面一直是空的)
//
// 故:genres/studios/tags/official_ratings 走各自分面端点(404 降级为空,其它失败显式记录,保留成功分面);
// years 因为没有可用端点,改用两次 Limit=1 探针取最早/最晚年份再铺成区间。
func (c *Client) FiltersOf(ctx context.Context, s *Session, parentID string) (*Filters, error) {
	var out Filters
	var wg sync.WaitGroup
	var mu sync.Mutex
	var authErr error
	record := func(name string, err error) {
		if err == nil || StatusOf(err) == 404 {
			return
		}
		mu.Lock()
		defer mu.Unlock()
		if StatusOf(err) == 401 || StatusOf(err) == 403 {
			authErr = err
		}
		out.Unavailable = append(out.Unavailable, name)
	}
	// 五路并行,保留成功分面;认证失败必须明确返回。
	for _, f := range []struct {
		endpoint string
		dst      *[]string
	}{
		{"Genres", &out.Genres},
		{"Tags", &out.Tags},
		{"Studios", &out.Studios},
		{"OfficialRatings", &out.OfficialRatings},
	} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			var err error
			*f.dst, err = c.facet(ctx, s, f.endpoint, parentID)
			record(strings.ToLower(f.endpoint), err)
		}()
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		var err error
		out.Years, err = c.yearRange(ctx, s, parentID)
		record("years", err)
	}()
	wg.Wait()
	sort.Strings(out.Unavailable)
	return &out, authErr
}

// facet 某分面端点的库内取值(Items[].Name)。
//
// ★ 失败保留空列表,由调用方记录失败状态,不抹掉其它成功分面。
// ★ 返回的是**空切片不是 nil** —— nil 序列化成 JSON `null`,而黄金实现给的是 `[]`。
//
//	前端拿到 null 直接 `.map()` 会抛错,在透明窗口下就是**一片黑且不报错**。
//	这条是差分对账当场抓出来的(2026-08-31):Go 的零值切片和 Rust 的 Vec::new() 不等价。
func (c *Client) facet(ctx context.Context, s *Session, endpoint, parentID string) ([]string, error) {
	u := fmt.Sprintf("%s/%s?UserId=%s&ParentId=%s&Recursive=true",
		s.Server, endpoint, url.QueryEscape(s.UserID), url.QueryEscape(parentID))
	out := []string{}
	b, err := c.getBytes(ctx, s, u)
	if err != nil {
		return out, err
	}
	var j struct {
		Items []struct {
			Name string `json:"Name"`
		} `json:"Items"`
	}
	if err := json.Unmarshal(b, &j); err != nil {
		return out, err
	}
	for _, i := range j.Items {
		if i.Name != "" {
			out = append(out, i.Name)
		}
	}
	return out, nil
}

// yearRange 年份分面。
//
// ★ Emby **没有 /Years 端点**(实测 404),而全量扫出所有年份要翻 17 页(200/页)。
// 折中:按 ProductionYear 正/倒排各取 1 条拿到最早/最晚年,铺成倒序区间。
//
// ponytail: 区间里可能混入该库没有的年份(选了就是空结果),换取 2 次请求而非 17 次;
// 要精确年份列表得等服务端支持分面,或改成全量扫描。
func (c *Client) yearRange(ctx context.Context, s *Session, parentID string) ([]int64, error) {
	probe := func(order string) (*int64, error) {
		u := fmt.Sprintf("%s/Users/%s/Items?ParentId=%s&Recursive=true&IncludeItemTypes=Movie,Series"+
			"&SortBy=ProductionYear&SortOrder=%s&Limit=1&Fields=ProductionYear",
			s.Server, url.PathEscape(s.UserID), url.QueryEscape(parentID), order)
		items, err := c.fetchItems(ctx, s, u)
		if err != nil || len(items) == 0 {
			return nil, err
		}
		return items[0].Year, nil
	}
	var newest, oldest *int64
	var newestErr, oldestErr error
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); newest, newestErr = probe("Descending") }()
	go func() { defer wg.Done(); oldest, oldestErr = probe("Ascending") }()
	wg.Wait()
	for _, err := range []error{newestErr, oldestErr} {
		if StatusOf(err) == 401 || StatusOf(err) == 403 {
			return []int64{}, err
		}
	}
	if StatusOf(newestErr) == 404 && oldestErr != nil {
		return []int64{}, oldestErr
	}
	if newestErr != nil {
		return []int64{}, newestErr
	}
	if oldestErr != nil {
		return []int64{}, oldestErr
	}
	// 同上:空区间要给 `[]` 不是 nil
	if newest == nil || oldest == nil || *newest < *oldest {
		return []int64{}, nil
	}
	out := []int64{}
	for y := *newest; y >= *oldest; y-- {
		out = append(out, y)
	}
	return out, nil
}
