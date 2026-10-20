package emby

// HeroCandidates 排除已看、续播及其所属剧，优先整剧并按作品去重；不访问网络。
func HeroCandidates(items []Item) []string {
	unique := []Item{}
	seen, excluded, series := map[string]bool{}, map[string]bool{}, map[string]bool{}
	for _, item := range items {
		if item.ID == "" || seen[item.ID] {
			continue
		}
		seen[item.ID] = true
		unique = append(unique, item)
		if item.Played || item.ResumeSecs > 0 {
			excluded[item.ID] = true
			if id := deref(item.SeriesID); id != "" {
				excluded[id] = true
			}
		}
		if item.Type == "Series" {
			series[item.ID] = true
		}
	}
	result := []string{}
	groups := map[string]bool{}
	for _, item := range unique {
		parent := deref(item.SeriesID)
		if excluded[item.ID] || excluded[parent] || series[parent] {
			continue
		}
		switch item.Type {
		case "Movie", "Series", "Episode", "Season":
		default:
			continue
		}
		group := parent
		if group == "" {
			group = item.ID
		}
		if !groups[group] {
			result = append(result, item.ID)
			groups[group] = true
		}
	}
	return result
}
