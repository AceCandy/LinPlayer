package player

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"linplayer/core/config"
	"linplayer/core/emby"
	"linplayer/core/paths"
)

func TestExo起播不等待开始上报且停止不能先于开始(t *testing.T) {
	paths.SetRoot(t.TempDir())
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	entered, release, stopped := make(chan struct{}), make(chan struct{}), make(chan struct{}, 1)
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.Contains(r.URL.Path, "PlaybackInfo"):
			fmt.Fprint(w, `{"PlaySessionId":"ps","MediaSources":[{"Id":"ms","Container":"mkv","DirectStreamUrl":"/Videos/item/stream.mkv"}]}`)
		case strings.Contains(r.URL.Path, "stream.mkv"):
			w.Header().Set("Content-Range", "bytes 0-0/4096")
			w.WriteHeader(206)
			w.Write([]byte{0})
		case r.URL.Path == "/Sessions/Playing":
			close(entered)
			<-release
			w.WriteHeader(204)
		case strings.HasSuffix(r.URL.Path, "/Stopped"):
			stopped <- struct{}{}
			w.WriteHeader(204)
		default:
			fmt.Fprint(w, `{"Id":"item","Type":"Movie","RunTimeTicks":10000000000,"UserData":{"PlaybackPositionTicks":120000000}}`)
		}
	}))
	defer up.Close()
	defer closeSharedProxy()
	defer close(release)
	s := &emby.Session{Server: up.URL, UserID: "u"}
	done := make(chan error, 1)
	startedAt := time.Now()
	go func() {
		r, err := PlayResolve(context.Background(), s, "item", 0, "")
		if err == nil && r.ResumeSecs != 12 {
			err = fmt.Errorf("续播位置丢失: %v", r.ResumeSecs)
		}
		done <- err
	}()
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("未发开始上报")
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
		t.Logf("上报仍被闸门阻塞时,Exo 地址及正确续播位置已在 %v 返回", time.Since(startedAt))
	case <-time.After(300 * time.Millisecond):
		t.Fatal("慢上报阻塞 Exo 地址回执")
	}
	stopDone := make(chan error, 1)
	currentMu.Lock()
	target, report := current, currentReport
	currentMu.Unlock()
	go func() { stopDone <- Stop(context.Background(), s, 15) }()
	select {
	case <-stopped:
		t.Fatal("开始上报尚未完成便发出停止")
	case <-time.After(100 * time.Millisecond):
	}
	// 由服务端释放闸门,确认停止最终能够完成。
	release <- struct{}{}
	select {
	case err := <-stopDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("停止上报未完成")
	}
	if reported, err := report.progress(context.Background(), s, target, 16, false); reported || err != nil {
		t.Fatalf("迟到进度覆盖已停止会话: reported=%v err=%v", reported, err)
	}
}
