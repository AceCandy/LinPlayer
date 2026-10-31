package player

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"reflect"
	"strings"
	"testing"
	"time"

	"linplayer/core/bus"
	"linplayer/core/config"
	"linplayer/core/paths"
)

func TestLiuYingSpeedPersistsWithoutChangingDefault(t *testing.T) {
	setup(t)
	for i, settings := range []map[string]any{
		{"default_speed": 1.5},
		{"liuying_speed": 2.0},
		{"default_speed": 1.25},
	} {
		if r := call(t, int64(920+i), "player.setPlaybackPrefs", settings); !r.OK {
			t.Fatal(r.Msg)
		}
	}
	if _, err := config.Load(); err != nil {
		t.Fatal(err)
	}
	r := call(t, 924, "player.getPlaybackPrefs", nil)
	var values map[string]any
	if err := json.Unmarshal(r.Data, &values); err != nil {
		t.Fatal(err)
	}
	if values["liuying_speed"] != 2.0 || values["default_speed"] != 1.25 {
		t.Fatalf("流影与普通倍速必须独立落盘: %v", values)
	}
	for i, value := range []any{0.0, 5.0, "2", nil} {
		r := call(t, int64(930+i), "player.setPlaybackPrefs", map[string]any{"liuying_speed": value})
		if r.OK || r.Code != bus.EInvalid {
			t.Fatalf("非法流影倍速未拒绝: %v, %+v", value, r)
		}
	}
}

func TestLiuYingSpeedSaveFailureRollsBack(t *testing.T) {
	setup(t)
	before := config.Current().PrefsOf()
	if err := os.Mkdir(paths.ConfigFile(), 0o755); err != nil {
		t.Fatal(err)
	}
	r := call(t, 940, "player.setPlaybackPrefs", map[string]any{"liuying_speed": 2.0})
	if r.OK {
		t.Fatal("保存失败不能返回成功")
	}
	if !reflect.DeepEqual(config.Current().PrefsOf(), before) {
		t.Fatal("失败后内存偏好未回滚")
	}
}

func TestLiuYingSpeedDefaultsAndStartupScope(t *testing.T) {
	for _, raw := range []string{`{}`, `{"liuying_speed":0}`, `{"liuying_speed":99}`} {
		if p := config.ParsePrefs([]byte(raw)); p.LiuYingSpeed != 1.0 {
			t.Fatalf("旧配置和越界值必须回1倍: %+v", p)
		}
	}
	p := config.ParsePrefs([]byte(`{"liuying_speed":2,"default_speed":1.5}`))
	if playbackSpeed(p, "liuying") != 2 || playbackSpeed(p, "") != 1.5 || p.DefaultSpeed != 1.5 {
		t.Fatal("起播作用域不应修改普通偏好")
	}
	regTransportOnce.Do(registerTransport)
	for i, value := range []any{"unknown", true, nil} {
		r := call(t, int64(950+i), "player.play", map[string]any{"speed_scope": value})
		if r.OK || r.Code != bus.EInvalid || r.Msg != "未知的倍速作用域" {
			t.Fatalf("无效作用域未拒绝: %+v", r)
		}
	}
}

// 隔离原生事件线程；只核验真实命令起播的倍率属性，不把占位流当作解码验证。
func TestLiuYingSpeedPlayCommand(t *testing.T) {
	if os.Getenv("LP_LIUYING_SPEED_TEST") != "1" {
		cmd := exec.Command(os.Args[0], "-test.run=^TestLiuYingSpeedPlayCommand$", "-test.count=1")
		cmd.Env = append(os.Environ(), "LP_LIUYING_SPEED_TEST=1")
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("流影起播倍率回归: %v\n%s", err, out)
		}
		return
	}
	setup(t)
	regTransportOnce.Do(registerTransport)
	if _, err := bus.Invoke(context.Background(), "player.setSpeed", map[string]any{"speed": 2.0}); err == nil {
		t.Fatal("内核未初始化时不能返回倍率设置成功")
	}
	// 此用例只读原生属性，宿主图形通道用就绪标志代替，不执行渲染。
	rctxSet.Store(true)
	defer rctxSet.Store(false)
	data := make([]byte, 4096)
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.Contains(r.URL.Path, "PlaybackInfo"):
			fmt.Fprint(w, `{"MediaSources":[{"Id":"ms","Container":"mkv","DirectStreamUrl":"/video.mkv"}]}`)
		case strings.Contains(r.URL.Path, "video.mkv"):
			http.ServeContent(w, r, "video.mkv", time.Time{}, bytes.NewReader(data))
		case strings.Contains(r.URL.Path, "/Sessions/Playing"):
			w.WriteHeader(http.StatusNoContent)
		default:
			fmt.Fprint(w, `{"Id":"ly-work-1","Type":"Movie","RunTimeTicks":520000000}`)
		}
	}))
	defer up.Close()
	r := call(t, 960, "player.setPlaybackPrefs", map[string]any{"default_speed": 1.5, "liuying_speed": 2.0})
	if !r.OK {
		t.Fatal(r.Msg)
	}
	for i, scope := range []string{"liuying", ""} {
		a := map[string]any{"server": up.URL, "user_id": "u", "token": "test", "item_id": "ly-work-1", "from_start": true}
		if scope != "" {
			a["speed_scope"] = scope
		}
		if r := call(t, int64(961+i*2), "player.play", a); !r.OK {
			t.Fatal(r.Msg)
		}
		want := 1.5
		if scope == "liuying" {
			want = 2
		}
		if got := propF("speed"); got != want {
			t.Fatalf("作用域 %q 起播倍率=%v，期望%v", scope, got, want)
		}
		if _, err := bus.Invoke(context.Background(), "player.setSpeed", map[string]any{"speed": 1.75}); err != nil {
			t.Fatal(err)
		}
		if got := propF("speed"); got != 1.75 {
			t.Fatalf("手动倍率未进入真实内核: %v", got)
		}
		a["pos"] = 0.0
		if r := call(t, int64(962+i*2), "player.stopPlayback", a); !r.OK {
			t.Fatal(r.Msg)
		}
	}
	if p := config.Current().PrefsOf(); p.DefaultSpeed != 1.5 || p.LiuYingSpeed != 2 {
		t.Fatal("起播改变了持久倍率", p.DefaultSpeed, p.LiuYingSpeed)
	}
}
