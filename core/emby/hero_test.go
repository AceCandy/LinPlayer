package emby

import (
	"reflect"
	"testing"
)

func TestHeroCandidatesExcludeWatchedSeries(t *testing.T) {
	series := "series"
	got := HeroCandidates([]Item{{ID: "movie", Type: "Movie"}, {ID: "series", Type: "Series"}, {ID: "episode", Type: "Episode", SeriesID: &series, ResumeSecs: 20}, {ID: "movie", Type: "Movie"}, {ID: "person", Type: "Person"}, {ID: "watched", Type: "Movie", Played: true}})
	if !reflect.DeepEqual(got, []string{"movie"}) {
		t.Fatal(got)
	}
	got = HeroCandidates([]Item{{ID: "episode", Type: "Episode", SeriesID: &series}, {ID: "series", Type: "Series"}})
	if !reflect.DeepEqual(got, []string{"series"}) {
		t.Fatal(got)
	}
	got = HeroCandidates([]Item{{ID: "episode", Type: "Episode", SeriesID: &series}, {ID: "second", Type: "Episode", SeriesID: &series}})
	if !reflect.DeepEqual(got, []string{"episode"}) {
		t.Fatal(got)
	}
}
