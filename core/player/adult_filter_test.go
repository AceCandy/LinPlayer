package player

import (
	"context"
	"encoding/json"
	"fmt"
	"linplayer/core/emby"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"linplayer/core/config"
	"linplayer/core/history"
	"linplayer/core/paths"
)

func TestPrivatePlaybackUsesOnlyCurrentServerProgress(t *testing.T) {
	regTransportOnce.Do(registerTransport)
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	previous := history.Shared()
	store := history.New(t.TempDir() + "/history.json")
	history.SetShared(store)
	defer history.SetShared(previous)
	var otherRequests, stops atomic.Int32
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		otherRequests.Add(1)
		http.Error(w, "不得访问其它服务器", 500)
	}))
	defer other.Close()
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/Views"):
			if r.URL.Query().Get("IsAdult") != "true" {
				t.Error("库核验丢分类")
			}
			fmt.Fprint(w, `{"Items":[{"Id":"private","Type":"CollectionFolder","IsAdult":true}]}`)
		case strings.HasSuffix(r.URL.Path, "/PlaybackInfo"):
			if r.URL.Query().Get("IsAdult") != "true" {
				t.Error("实际取流丢分类")
			}
			fmt.Fprint(w, `{"PlaySessionId":"session","MediaSources":[{"Id":"version","LibraryIds":["private"],"Container":"mkv","SupportsDirectStream":true,"DirectStreamUrl":"/stream.mkv"}]}`)
		case strings.Contains(r.URL.Path, "stream.mkv"):
			w.Header().Set("Content-Range", "bytes 0-0/8388608")
			w.WriteHeader(206)
			w.Write([]byte{0})
		case strings.HasSuffix(r.URL.Path, "/Items/film"):
			if r.URL.Query().Get("IsAdult") != "true" {
				t.Error("历史判据丢分类")
			}
			fmt.Fprint(w, `{"Id":"film","Name":"作品","Type":"Movie","RunTimeTicks":14000000000,"UserData":{"PlaybackPositionTicks":70000000}}`)
		case strings.HasSuffix(r.URL.Path, "/Sessions/Playing/Stopped"):
			stops.Add(1)
			w.WriteHeader(204)
		default:
			fmt.Fprint(w, `{}`)
		}
	}))
	defer up.Close()
	defer closeSharedProxy()
	cfg := config.Current()
	cfg.AccountList = []config.Account{{Server: up.URL, UserID: "viewer", Token: "test"}, {Server: other.URL, UserID: "viewer", Token: "test"}}
	prefs := cfg.PrefsOf()
	prefs.PrimaryProgressServer = &config.ProgressServer{Server: other.URL, UserID: "viewer"}
	prefs.CrossServerResume = true
	if err := cfg.SetPrefs(prefs); err != nil {
		t.Fatal(err)
	}
	adult := true
	runtime := int64(14000000000)
	for _, server := range []string{up.URL, other.URL} {
		store.Capture(history.CaptureOpts{ScopeKey: history.ScopeKey(server, "viewer"), Candidate: history.Candidate{
			ID: "film", Name: "作品", Type: "Movie", RunTimeTicks: &runtime, IsAdult: &adult,
		}, PositionTicks: 200 * history.TicksPerSec, Force: true})
	}
	before, _ := json.Marshal(store.LoadAll())
	args := map[string]any{"server": up.URL, "token": "test", "user_id": "viewer", "device_id": "device", "item_id": "film", "engine": "exo", "is_adult": true}
	result := call(t, 9820, "player.play", args)
	if !result.OK {
		t.Fatalf("起播失败: %s %s", result.Code, result.Msg)
	}
	var played PlayResult
	if err := json.Unmarshal(result.Data, &played); err != nil {
		t.Fatal(err)
	}
	if played.ResumeSecs != 7 {
		t.Fatalf("应使用本服7秒进度，实际%v", played.ResumeSecs)
	}
	args["pos"] = float64(25)
	result = call(t, 9821, "player.stopPlayback", args)
	if !result.OK {
		t.Fatalf("停播失败: %s %s", result.Code, result.Msg)
	}
	after, _ := json.Marshal(store.LoadAll())
	if string(before) != string(after) {
		t.Fatal("隐私播放改写了本地历史")
	}
	if otherRequests.Load() != 0 {
		t.Fatal("隐私播放访问了主进度服")
	}
	if stops.Load() != 1 {
		t.Fatal("本服停止上报丢失")
	}
}

func TestPrivatePlaybackResumeRules(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	ctx, _ := emby.AdultFilterContext(context.Background(), map[string]any{"is_adult": true})
	runtime := int64(100 * history.TicksPerSec)
	h := &historyContext{candidate: history.Candidate{PositionTicks: 7 * history.TicksPerSec, RunTimeTicks: &runtime}}
	prefs := config.DefaultPrefs()
	for _, test := range []struct {
		arg, want float64
		exact     bool
	}{{80, 7, false}, {-1, 0, false}, {23, 23, true}, {0, 0, true}} {
		got, primary, err := resolvePlaybackPosition(ctx, &emby.Session{}, &emby.PlaybackTarget{}, h, prefs, test.arg, test.exact)
		if err != nil || primary != nil || got != test.want {
			t.Fatalf("arg=%v exact=%v got=%v primary=%v err=%v", test.arg, test.exact, got, primary, err)
		}
	}
}
