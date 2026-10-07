package player

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/history"
	"linplayer/core/paths"
	"linplayer/core/progress"
)

func Test主服零进度与回退覆盖本地最大值且从头优先(t *testing.T) {
	paths.SetRoot(t.TempDir())
	c, err := config.Load()
	if err != nil {
		t.Fatal(err)
	}
	position := int64(0)
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.Contains(r.URL.Path, "PlaybackInfo"):
			fmt.Fprint(w, `{"PlaySessionId":"session","MediaSources":[{"Id":"file","RunTimeTicks":1200000000,"DirectStreamUrl":"/stream"}]}`)
		case strings.HasSuffix(r.URL.Path, "PlaybackProgress"):
			fmt.Fprintf(w, `{"MediaSourceId":"file","PositionTicks":%d,"RunTimeTicks":1200000000,"Played":false,"Revision":"1"}`, position)
		case strings.HasPrefix(r.URL.Path, "/Sessions/") || strings.Contains(r.URL.Path, "PlayedItems"):
			w.WriteHeader(204)
		default:
			fmt.Fprint(w, `{"Id":"item","Type":"Movie","Name":"Movie","RunTimeTicks":1200000000,"UserData":{"PlaybackPositionTicks":800000000,"Played":true}}`)
		}
	}))
	defer up.Close()
	c.Upsert(config.Account{Server: up.URL, UserID: "user", Token: "test"})
	p := c.PrefsOf()
	p.PreloadEnabled = false
	p.PrimaryProgressServer = &config.ProgressServer{Server: up.URL, UserID: "user"}
	if err := c.SetPrefs(p); err != nil {
		t.Fatal(err)
	}
	s := &emby.Session{Server: up.URL, UserID: "user"}
	runtime := int64(1200000000)
	history.Shared().Capture(history.CaptureOpts{ScopeKey: history.ScopeKey(up.URL, "user"), Candidate: history.Candidate{ID: "item", Name: "Movie", Type: "Movie", RunTimeTicks: &runtime}, PositionTicks: 1000000000, Force: true})
	defer progress.Shared().Invalidate()
	for _, test := range []struct {
		primary   int64
		arg, want float64
	}{{0, 80, 0}, {20000000, 80, 2}, {30000000, -1, 0}} {
		position = test.primary
		r, err := PlayResolve(context.Background(), s, "item", test.arg, "")
		if err != nil {
			t.Fatal(err)
		}
		if r.ResumeSecs != test.want {
			t.Fatalf("primary=%d arg=%v resume=%v want=%v", position, test.arg, r.ResumeSecs, test.want)
		}
		if err := Stop(context.Background(), s, 0); err != nil {
			t.Fatal(err)
		}
	}
}
