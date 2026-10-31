package player

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/paths"
)

func TestQuickReopenKeepsFinalPositionAndOrderedReports(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	release := make(chan struct{})
	var once sync.Once
	stopped := make(chan struct{})
	var mu sync.Mutex
	var events []string
	data := make([]byte, 8<<20)
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.Contains(r.URL.Path, "PlaybackInfo"):
			fmt.Fprint(w, `{"PlaySessionId":"session","MediaSources":[{"Id":"version","RunTimeTicks":10000000000,"Container":"mkv","DirectStreamUrl":"/emby/video"}]}`)
		case r.URL.Path == "/emby/video":
			w.Header().Set("ETag", `"v1"`)
			http.ServeContent(w, r, "video.mkv", time.Time{}, bytes.NewReader(data))
		case strings.Contains(r.URL.Path, "/Sessions/Playing"):
			var body struct {
				Position int64 `json:"PositionTicks"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			name := "start"
			if strings.HasSuffix(r.URL.Path, "/Stopped") {
				name = "stop"
				if body.Position == 12*10000000 {
					close(stopped)
					<-release
				}
			}
			mu.Lock()
			events = append(events, fmt.Sprintf("%s:%d", name, body.Position/10000000))
			mu.Unlock()
			w.WriteHeader(204)
		default:
			fmt.Fprint(w, `{"Id":"film","Name":"测试片","Type":"Movie","RunTimeTicks":10000000000,"UserData":{"PlaybackPositionTicks":1800000000}}`)
		}
	}))
	defer up.Close()
	defer func() {
		once.Do(func() { close(release) })
		_ = awaitStopReport(context.Background())
		closeSharedProxy()
	}()
	s := &emby.Session{Server: up.URL, UserID: "u", Token: "test"}
	if _, err := PlayResolve(context.Background(), s, "film", 0, ""); err != nil {
		t.Fatal(err)
	}
	localFirst := make(chan struct{})
	firstResult := make(chan error, 1)
	stopCtx, cancelStop := context.WithCancel(context.Background())
	defer cancelStop()
	go func() {
		firstResult <- stopPlaybackOwned(stopCtx, s, 12, false, "film", nil, func() { close(localFirst) })
	}()
	select {
	case <-localFirst:
	case <-time.After(time.Second):
		t.Fatal("local acknowledgement waited for network")
	}
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("stop did not reach server")
	}
	cancelStop()
	next, err := PlayResolve(context.Background(), s, "film", 0, "")
	if err != nil {
		t.Fatal(err)
	}
	if next.ResumeSecs != 12 {
		t.Fatalf("rewound final position overwritten by stale remote: %v", next.ResumeSecs)
	}
	local := make(chan struct{})
	secondResult := make(chan error, 1)
	go func() {
		secondResult <- stopPlaybackOwned(context.Background(), s, 18, false, "film", nil, func() { close(local) })
	}()
	select {
	case <-local:
	case <-time.After(time.Second):
		t.Fatal("second local stop waited for previous network report")
	}
	mu.Lock()
	count := len(events)
	mu.Unlock()
	if count != 1 {
		t.Fatal("new report overtook stopped request")
	}
	once.Do(func() { close(release) })
	if err := awaitStopReport(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := <-firstResult; err != nil {
		t.Fatal(err)
	}
	if err := <-secondResult; err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	if fmt.Sprint(events) != "[start:180 stop:12 start:12 stop:18]" {
		t.Fatalf("report order: %v", events)
	}
}

func TestPendingStopResumeIsolationAndExplicitPosition(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	s := emby.Session{Server: "server", UserID: "user", Token: "test", DeviceID: "device"}
	target := &emby.PlaybackTarget{ItemID: "film", MediaSourceID: "version", RunTimeTicks: 10000000000}
	old := rememberStopResume(s, target, nil, 70, false)
	latest := rememberStopResume(s, target, nil, 12, false)
	defer latest.finish()
	old.finish()
	ctx := context.Background()
	if pendingResumeFor(ctx, &s, "film") != latest {
		t.Fatal("old completion removed latest stop")
	}
	private, _ := emby.AdultFilterContext(ctx, map[string]any{"is_adult": true})
	if pendingResumeFor(private, &s, "film") != nil {
		t.Fatal("crossed private boundary")
	}
	public, _ := emby.AdultFilterContext(ctx, map[string]any{"is_adult": false})
	if pendingResumeFor(public, &s, "film") != nil {
		t.Fatal("crossed unclassified/public boundary")
	}
	for _, change := range []func(*emby.Session){func(s *emby.Session) { s.Server = "other" }, func(s *emby.Session) { s.UserID = "other" }, func(s *emby.Session) { s.Token = "other" }, func(s *emby.Session) { s.DeviceID = "other" }} {
		other := s
		change(&other)
		if pendingResumeFor(ctx, &other, "film") != nil {
			t.Fatal("crossed session identity")
		}
	}
	if pendingResumeFor(ctx, &s, "other") != nil {
		t.Fatal("crossed item")
	}
	for _, tc := range []struct {
		name           string
		resume         float64
		exact, watched bool
		source         string
		runtime        int64
		want           float64
	}{
		{"final", 180, false, false, "version", 10000000000, 12},
		{"from_start", -1, false, false, "version", 10000000000, 0},
		{"exact", 20, true, false, "version", 10000000000, 20},
		{"watched", 180, false, true, "version", 10000000000, 0},
		{"source", 180, false, false, "other", 10000000000, 180},
		{"runtime", 180, false, false, "version", 20000000000, 180},
	} {
		t.Run(tc.name, func(t *testing.T) {
			copy := *latest
			copy.watched = tc.watched
			media := *target
			media.MediaSourceID = tc.source
			media.RunTimeTicks = tc.runtime
			got, _, err := resolvePlaybackPosition(ctx, &s, &media, nil, config.Current().PrefsOf(), tc.resume, tc.exact, &copy)
			if err != nil || got != tc.want {
				t.Fatalf("resume=%v err=%v want=%v", got, err, tc.want)
			}
		})
	}
}

func TestPrimaryStopWaitsAndPrivateStopCanAcknowledgeLocally(t *testing.T) {
	for _, private := range []bool{false, true} {
		t.Run(fmt.Sprint(private), func(t *testing.T) {
			paths.SetRoot(t.TempDir())
			c, err := config.Load()
			if err != nil {
				t.Fatal(err)
			}
			p := c.PrefsOf()
			p.PrimaryProgressServer = &config.ProgressServer{Server: "primary", UserID: "user"}
			if err := c.SetPrefs(p); err != nil {
				t.Fatal(err)
			}
			entered, release := make(chan struct{}), make(chan struct{})
			var once sync.Once
			unblock := func() { once.Do(func() { close(release) }) }
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				close(entered)
				<-release
				w.WriteHeader(204)
			}))
			defer up.Close()
			defer unblock()
			s := emby.Session{Server: up.URL, UserID: "user", Token: "test"}
			target := &emby.PlaybackTarget{ItemID: "film", MediaSourceID: "version", RunTimeTicks: 10000000000}
			report := &playbackReport{started: make(chan struct{}), session: s, adult: &private}
			close(report.started)
			currentMu.Lock()
			current = target
			currentReport = report
			currentCtx = nil
			currentMu.Unlock()
			local, result := make(chan struct{}), make(chan error, 1)
			go func() {
				result <- stopPlaybackOwned(context.Background(), &s, 12, false, "film", nil, func() { close(local) })
			}()
			select {
			case <-entered:
			case <-time.After(time.Second):
				t.Fatal("report did not start")
			}
			select {
			case <-local:
				if !private {
					t.Fatal("primary mode acknowledged before final sync")
				}
			default:
				if private {
					t.Fatal("private stop incorrectly waits for primary")
				}
			}
			if !private {
				ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
				_, _, err := resolvePlaybackPosition(ctx, &s, target, &historyContext{}, p, 0, false)
				cancel()
				if err != context.DeadlineExceeded {
					t.Fatalf("primary prepare overtook stop: %v", err)
				}
			}
			unblock()
			if err := <-result; err != nil {
				t.Fatal(err)
			}
		})
	}
}
