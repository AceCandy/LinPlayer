package history

import (
	"context"
	"errors"

	"linplayer/core/emby"
)

// FindPrimaryCandidate 仅接受唯一的外部标识匹配；同名只能用来找候选，不能决定写入目标。
func FindPrimaryCandidate(ctx context.Context, client *emby.Client, session *emby.Session, self Candidate, seriesTmdb *string) (*Candidate, error) {
	typ, name, provider := "Movie", self.Name, self.TmdbID
	if self.Type == "Episode" {
		if self.SeriesName == nil || self.SeasonNo == nil || self.EpisodeNo == nil {
			return nil, nil
		}
		typ, name, provider = "Series", *self.SeriesName, seriesTmdb
	} else if self.Type != "Movie" {
		return nil, nil
	}
	if provider == nil || *provider == "" {
		return nil, nil
	}
	items, err := client.ProgressMatchItems(ctx, session, typ, *provider, name)
	if err != nil {
		return nil, err
	}
	if len(items) >= emby.ServerPageCap {
		return nil, errors.New("主服匹配候选未完整返回")
	}
	var matches []Candidate
	for _, item := range items {
		full, err := client.ItemForHistory(ctx, session, item.ID)
		if err != nil {
			return nil, err // 候选核验失败时不能把剩余一条当唯一。
		}
		if emby.ProviderOf(full.ProviderIDs, "Tmdb") != *provider || full.Type != typ {
			continue
		}
		if typ == "Movie" {
			matches = append(matches, CandidateFromItem(*full))
			continue
		}
		parent := full.ID
		seasons, err := client.ProgressMatchSeasons(ctx, session, full.ID)
		if err != nil {
			return nil, err
		}
		for _, season := range seasons {
			if season.IndexNo != nil && *season.IndexNo == *self.SeasonNo {
				if parent != full.ID {
					return nil, nil // 重复季号也不能猜。
				}
				parent = season.ID
			}
		}
		finished := false
		seen := map[string]bool{}
		for start := 0; start < 5000; {
			page, err := client.ProgressMatchEpisodes(ctx, session, parent, start)
			if err != nil || page == nil {
				return nil, err
			}
			for _, episode := range page.Items {
				if seen[episode.ID] {
					return nil, errors.New("主服分集分页没有推进")
				}
				seen[episode.ID] = true
				if episode.SeasonNo == nil || episode.EpisodeNo == nil || *episode.SeasonNo != *self.SeasonNo || *episode.EpisodeNo != *self.EpisodeNo {
					continue
				}
				fullEpisode, err := client.ItemForHistory(ctx, session, episode.ID)
				if err != nil {
					return nil, err
				}
				if fullEpisode.Type != "Episode" || fullEpisode.SeriesID == nil || *fullEpisode.SeriesID != full.ID || fullEpisode.SeasonNo == nil || fullEpisode.EpisodeNo == nil || *fullEpisode.SeasonNo != *self.SeasonNo || *fullEpisode.EpisodeNo != *self.EpisodeNo {
					return nil, nil
				}
				matches = append(matches, CandidateFromItem(*fullEpisode))
			}
			if len(page.Items) == 0 || (page.Total > 0 && int64(start+len(page.Items)) >= page.Total) {
				finished = true
				break
			}
			start += len(page.Items)
		}
		if !finished {
			return nil, errors.New("主服分集列表未完整返回")
		}
	}
	if len(matches) != 1 {
		return nil, nil
	}
	return &matches[0], nil
}
