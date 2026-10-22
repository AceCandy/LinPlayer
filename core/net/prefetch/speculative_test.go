package prefetch

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"linplayer/core/paths"
)

func TestSpeculativeClearRejectsLateProbeAndBothDiskKinds(t *testing.T) {
	for _, persistent := range []bool{true, false} {
		t.Run(map[bool]string{true: "persistent", false: "transient"}[persistent], func(t *testing.T) {
			paths.SetRoot(t.TempDir())
			started, release := make(chan struct{}), make(chan struct{})
			var once sync.Once
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				once.Do(func() { close(started); <-release })
				if persistent {
					w.Header().Set("ETag", `"v1"`)
				}
				w.Header().Set("Content-Range", "bytes 0-0/8388608")
				w.Header().Set("Content-Length", "1")
				w.WriteHeader(206)
				_, _ = w.Write([]byte{0})
			}))
			defer up.Close()
			epoch := MediaCacheEpoch()
			result := make(chan error, 1)
			go func() {
				h, err := StartCached(context.Background(), up.URL, 2, 32<<20, false, CacheOptions{Identity: "fixture", Budget: 1 << 30, Epoch: epoch})
				if h != nil {
					h.Close()
				}
				result <- err
			}()
			<-started
			if err := ClearCache(paths.ClearCache); err != nil {
				close(release)
				t.Fatal(err)
			}
			close(release)
			if err := <-result; !errors.Is(err, context.Canceled) {
				t.Fatalf("清理前probe迟到未取消: %v", err)
			}
			for _, dir := range []string{paths.MediaCache(), paths.PrefetchCache()} {
				entries, err := os.ReadDir(dir)
				if err != nil && !os.IsNotExist(err) || len(entries) != 0 {
					t.Fatal("旧请求在清理后创建缓存", filepath.Base(dir), err)
				}
			}
			h, err := StartCached(context.Background(), up.URL, 2, 32<<20, false, CacheOptions{Identity: "fixture", Budget: 1 << 30, Epoch: MediaCacheEpoch()})
			if err != nil {
				t.Fatal("新代不能建立缓存", err)
			}
			h.Close()
			if late, err := StartCached(context.Background(), up.URL, 2, 32<<20, false,
				CacheOptions{Identity: "fixture", Budget: 1 << 30, Epoch: epoch}); !errors.Is(err, context.Canceled) {
				if late != nil {
					late.Close()
				}
				t.Fatal("解析前捕获的旧代仍可创建代理", err)
			}
			if _, err := newDiskCache(8<<20, 32<<20, 2, epoch); !errors.Is(err, context.Canceled) {
				t.Fatal("临时创建未同锁拒绝旧代")
			}
			if _, err := newPersistentCache(8<<20, "fixture", `"v1"`, epoch); !errors.Is(err, context.Canceled) {
				t.Fatal("持久创建未同锁拒绝旧代")
			}
		})
	}
}

func TestSpeculativeClearRejectsInFlightBlockWrite(t *testing.T) {
	paths.SetRoot(t.TempDir())
	started, release := make(chan struct{}), make(chan struct{})
	var startedOnce, releaseOnce sync.Once
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		a, b, _ := parseRange(r.Header.Get("Range"))
		if b < 0 || b >= 2*ChunkSize {
			b = 2*ChunkSize - 1
		}
		w.Header().Set("ETag", `"v1"`)
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", a, b, 2*ChunkSize))
		w.Header().Set("Content-Length", fmt.Sprint(b-a+1))
		w.WriteHeader(http.StatusPartialContent)
		if b > a {
			w.(http.Flusher).Flush()
			startedOnce.Do(func() { close(started); <-release })
		}
		_, _ = w.Write(bodyAt(a, int(b-a+1)))
	}))
	defer func() { releaseOnce.Do(func() { close(release) }); up.Close() }()
	reads := NewReadControl(NewReadBudget())
	h, err := StartCached(context.Background(), up.URL, 2, 32<<20, false,
		CacheOptions{Identity: "in-flight", Budget: 1 << 30, Epoch: MediaCacheEpoch(), Reads: reads})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { releaseOnce.Do(func() { close(release) }); h.Close() }()
	reads.SetEnabled(true)
	done := make(chan error, 1)
	go func() {
		req, _ := http.NewRequest(http.MethodGet, h.URL, nil)
		req.Header.Set("Range", fmt.Sprintf("bytes=0-%d", ChunkSize-1))
		resp, err := (&http.Client{Timeout: 5 * time.Second}).Do(req)
		if err == nil {
			var data []byte
			data, err = io.ReadAll(resp.Body)
			resp.Body.Close()
			if err == nil && len(data) != int(ChunkSize) {
				err = fmt.Errorf("有效在途载体供给不完整: %d", len(data))
			}
		}
		done <- err
	}()
	select {
	case <-started:
	case <-time.After(5 * time.Second):
		t.Fatal("未开始读取测试块")
	}
	if err := ClearCache(paths.ClearCache); err != nil {
		t.Fatal(err)
	}
	releaseOnce.Do(func() { close(release) })
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	h.Close()
	if n, err := paths.CacheSize(); err != nil || n != 0 {
		t.Fatal("清理前在途块在返回后重新落盘", n, err)
	}
	fresh, err := StartCached(context.Background(), up.URL, 2, 32<<20, false,
		CacheOptions{Identity: "in-flight", Budget: 1 << 30, Epoch: MediaCacheEpoch()})
	if err != nil {
		t.Fatal(err)
	}
	defer fresh.Close()
	_, _, data := getRange(t, fresh, 0, ChunkSize-1)
	if len(data) != int(ChunkSize) {
		t.Fatal("新代供给失败")
	}
	fresh.Close()
	if n, err := paths.CacheSize(); err != nil || n == 0 {
		t.Fatal("新代不能正常落盘", n, err)
	}
}

func TestSpeculativeReadBudgetPauseAndPromotion(t *testing.T) {
	budget := NewReadBudget()
	a, b := NewReadControl(budget), NewReadControl(budget)
	a.SetEnabled(true)
	b.SetEnabled(true)
	var release []func()
	var contexts []context.Context
	for i := 0; i < 4; i++ {
		ctx, done, err := a.acquire(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		contexts, release = append(contexts, ctx), append(release, done)
	}
	waitCtx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel()
	if _, _, err := b.acquire(waitCtx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal("不同代理未共享4块额度", err)
	}
	a.SetEnabled(false)
	for _, ctx := range contexts {
		select {
		case <-ctx.Done():
		case <-time.After(time.Second):
			t.Fatal("暂停未取消已认领读取")
		}
	}
	if len(budget.slots) != 4 {
		t.Fatal("暂停提前归还仍未收尾的块")
	}
	waiting := make(chan error, 1)
	go func() {
		ctx, done, err := b.acquire(context.Background())
		if err == nil {
			err = ctx.Err()
			done()
		}
		waiting <- err
	}()
	b.Promote()
	select {
	case err := <-waiting:
		if err != nil {
			t.Fatal("已等待额度的正式读取未提升", err)
		}
	case <-time.After(time.Second):
		t.Fatal("提升未唤醒满额度等待者")
	}
	ctx, done, err := b.acquire(context.Background())
	if err != nil || ctx.Err() != nil {
		t.Fatal("正式提升仍被投机额度阻塞", err)
	}
	done()
	for _, finish := range release {
		finish()
	}
	if len(budget.slots) != 0 {
		t.Fatal("收尾未释放额度")
	}
	a.SetEnabled(true)
	_, done, err = a.acquire(context.Background())
	if err != nil {
		t.Fatal("暂停恢复不能复用同一control", err)
	}
	done()
}

func TestSpeculativePausedConnectionEndsAndSameProxyResumes(t *testing.T) {
	paths.SetRoot(t.TempDir())
	up := persistentOrigin(t)
	reads := NewReadControl(NewReadBudget())
	h, err := StartCached(context.Background(), up.URL, 2, 128<<20, false,
		CacheOptions{Identity: "speculative", Budget: 1 << 30, Epoch: MediaCacheEpoch(), Reads: reads})
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	_, _, data := getRange(t, h, 0, ChunkSize-1)
	if len(data) != 0 || up.downloads.Load() != 0 {
		t.Fatal("未放行的投机连接下载媒体或没有结束")
	}
	reads.SetEnabled(true)
	expectVersion(t, h, 0, 1)
	reads.SetEnabled(false)
	before := up.downloads.Load()
	_, _, data = getRange(t, h, ChunkSize, 2*ChunkSize-1)
	if len(data) != 0 || up.downloads.Load() != before {
		t.Fatal("暂停后仍有新的上游读取")
	}
	reads.SetEnabled(true)
	expectVersion(t, h, 1, 1)
	reads.SetEnabled(false)
	reads.Promote()
	expectVersion(t, h, 2, 1)
}
