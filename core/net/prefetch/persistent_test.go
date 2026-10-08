package prefetch

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"linplayer/core/paths"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestPersistentReopenUsesNewAuthorizationAndSameBytes(t *testing.T) {
	paths.SetRoot(t.TempDir())
	var downloads atomic.Int64
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		a, b, _ := parseRange(r.Header.Get("Range"))
		if b < 0 || b >= testTotal {
			b = testTotal - 1
		}
		if b-a > 0 {
			downloads.Add(1)
		}
		w.Header().Set("ETag", `"version-one"`)
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", a, b, testTotal))
		w.Header().Set("Content-Length", strconv.FormatInt(b-a+1, 10))
		w.WriteHeader(206)
		_, _ = w.Write(bodyAt(a, int(b-a+1)))
	}))
	defer up.Close()
	open := func(token string) *Handle {
		h, e := StartCached(context.Background(), up.URL+"/film?authorization="+token, 2, 128<<20, false, CacheOptions{Identity: "account-user-item-source", Budget: 1 << 30})
		if e != nil {
			t.Fatal(e)
		}
		return h
	}
	h := open("first")
	_, _, first := getRange(t, h, 0, ChunkSize-1)
	h.Close()
	before := downloads.Load()
	h = open("renewed")
	defer h.Close()
	_, _, again := getRange(t, h, 0, ChunkSize-1)
	if !bytes.Equal(first, bodyAt(0, int(ChunkSize))) || !bytes.Equal(first, again) {
		t.Fatal("incorrect bytes")
	}
	if downloads.Load() != before {
		t.Fatalf("reopened cache downloaded again: %d -> %d", before, downloads.Load())
	}
}

// originFixture 用真实Range HTTP同时验证授权更新、等长内容替换和异常响应。
type originFixture struct {
	*httptest.Server
	downloads atomic.Int64
	version   atomic.Int64
	mode      atomic.Int64
}

func persistentOrigin(t *testing.T) *originFixture {
	t.Helper()
	u := &originFixture{}
	u.version.Store(1)
	u.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		a, b, _ := parseRange(r.Header.Get("Range"))
		if b < 0 || b >= testTotal {
			b = testTotal - 1
		}
		if b > a {
			u.downloads.Add(1)
		}
		v := u.version.Load()
		tag := fmt.Sprintf(`"version-%d"`, v)
		if u.mode.Load() == 1 {
			tag = ""
		}
		if u.mode.Load() == 2 {
			tag = "W/" + tag
		}
		w.Header().Set("ETag", tag)
		cr := fmt.Sprintf("bytes %d-%d/%d", a, b, testTotal)
		if u.mode.Load() == 3 && b > a {
			cr = fmt.Sprintf("bytes %d-%d/%d", a+1, b+1, testTotal)
		}
		w.Header().Set("Content-Range", cr)
		w.Header().Set("Content-Length", strconv.FormatInt(b-a+1, 10))
		status := 206
		if u.mode.Load() == 4 && b > a {
			status = 200
		}
		if u.mode.Load() == 5 && b > a {
			w.Header().Set("ETag", `"changed-after-probe"`)
		}
		w.WriteHeader(status)
		data := bodyAt(a, int(b-a+1))
		for i := range data {
			data[i] ^= byte(v)
		}
		_, _ = w.Write(data)
	}))
	t.Cleanup(u.Close)
	return u
}
func persistentOpen(t *testing.T, u *originFixture, key string, budget int64) *Handle {
	t.Helper()
	h, e := StartCached(context.Background(), u.URL+"/file?token=new", 2, 128<<20, false, CacheOptions{Identity: key, Budget: budget})
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { h.Close(); ConfigureMediaCache(1 << 30) })
	return h
}
func expectVersion(t *testing.T, h *Handle, chunk int64, version byte) {
	t.Helper()
	_, _, b := getRange(t, h, chunk*ChunkSize, (chunk+1)*ChunkSize-1)
	want := bodyAt(chunk*ChunkSize, int(ChunkSize))
	for i := range want {
		want[i] ^= version
	}
	if !bytes.Equal(b, want) {
		t.Fatalf("wrong bytes/length: %d", len(b))
	}
}
func TestPersistentIsolationAndValidators(t *testing.T) {
	for _, mode := range []int64{0, 1, 2} {
		t.Run(fmt.Sprint(mode), func(t *testing.T) {
			paths.SetRoot(t.TempDir())
			u := persistentOrigin(t)
			u.mode.Store(mode)
			h := persistentOpen(t, u, "server-user-item-source", 1<<30)
			expectVersion(t, h, 0, 1)
			h.Close()
			before := u.downloads.Load()
			h = persistentOpen(t, u, "server-user-item-source", 1<<30)
			expectVersion(t, h, 0, 1)
			h.Close()
			if (u.downloads.Load() == before) != (mode == 0) {
				t.Fatal("weak/missing validator reused persistent data")
			}
			h = persistentOpen(t, u, "different-account-or-source", 1<<30)
			expectVersion(t, h, 0, 1)
			h.Close()
			if u.downloads.Load() <= before {
				t.Fatal("different identity shared bytes")
			}
			u.version.Store(2)
			h = persistentOpen(t, u, "server-user-item-source", 1<<30)
			expectVersion(t, h, 0, 2)
		})
	}
}
func TestPersistentRejectsWrongRangeOrVersionBeforeFeed(t *testing.T) {
	for _, mode := range []int64{3, 4, 5} {
		t.Run(fmt.Sprint(mode), func(t *testing.T) {
			paths.SetRoot(t.TempDir())
			u := persistentOrigin(t)
			u.mode.Store(mode)
			h := persistentOpen(t, u, "identity", 1<<30)
			_, _, b := getRange(t, h, 0, ChunkSize-1)
			if len(b) != 0 || h.origin.disk.has(0) {
				t.Fatal("invalid range/version reached player or disk")
			}
		})
	}
}
func TestPersistentDamagedAndUncommittedBlocksReload(t *testing.T) {
	for _, damage := range []string{"short", "hash", "index", "missing"} {
		t.Run(damage, func(t *testing.T) {
			paths.SetRoot(t.TempDir())
			u := persistentOrigin(t)
			h := persistentOpen(t, u, "identity", 1<<30)
			expectVersion(t, h, 0, 1)
			d := h.origin.disk
			h.Close()
			p := d.blockPath(d.slotOf(0))
			switch damage {
			case "short":
				if e := os.Truncate(p, 100); e != nil {
					t.Fatal(e)
				}
			case "hash":
				f, e := os.OpenFile(p, os.O_RDWR, 0600)
				if e != nil {
					t.Fatal(e)
				}
				_, e = f.WriteAt([]byte{99}, blockHeader+200)
				_ = f.Close()
				if e != nil {
					t.Fatal(e)
				}
			case "index":
				if e := os.WriteFile(filepath.Join(d.path, "index.json"), []byte("bad"), 0600); e != nil {
					t.Fatal(e)
				}
			case "missing":
				if e := os.Remove(p); e != nil {
					t.Fatal(e)
				}
			}
			if e := os.WriteFile(filepath.Join(d.path, ".pending-incomplete"), []byte("partial"), 0600); e != nil {
				t.Fatal(e)
			}
			before := u.downloads.Load()
			h = persistentOpen(t, u, "identity", 1<<30)
			expectVersion(t, h, 0, 1)
			if u.downloads.Load() <= before {
				t.Fatal("damaged block treated as committed")
			}
			if _, e := os.Stat(filepath.Join(d.path, ".pending-incomplete")); !os.IsNotExist(e) {
				t.Fatal("incomplete temporary file retained")
			}
		})
	}
}
func TestPersistentBudgetTTLAndActiveClear(t *testing.T) {
	paths.SetRoot(t.TempDir())
	u := persistentOrigin(t)
	h := persistentOpen(t, u, "old", 12<<20)
	expectVersion(t, h, 0, 1)
	h.Close()
	oldPath := h.origin.disk.path
	old := time.Now().Add(-mediaTTL - time.Hour)
	if e := os.Chtimes(oldPath, old, old); e != nil {
		t.Fatal(e)
	}
	h = persistentOpen(t, u, "new", 12<<20)
	expectVersion(t, h, 0, 1)
	if _, e := os.Stat(oldPath); !os.IsNotExist(e) {
		t.Fatal("expired entry retained")
	}
	second := persistentOpen(t, u, "other", 12<<20)
	expectVersion(t, second, 0, 1)
	third := persistentOpen(t, u, "third", 12<<20)
	expectVersion(t, third, 0, 1)
	n, _ := paths.CacheSize()
	if n > 12<<20 {
		t.Fatalf("global budget exceeded: %d", n)
	}
	// 容量进一步降低，活动句柄允许安全失效，在线供给仍继续。
	ConfigureMediaCache(5 << 20)
	n, _ = paths.CacheSize()
	if n > 5<<20 {
		t.Fatalf("shrink did not reclaim: %d", n)
	}
	expectVersion(t, third, 0, 1)
	if e := ClearCache(paths.ClearCache); e != nil {
		t.Fatal(e)
	}
	expectVersion(t, third, 1, 1)
	n, _ = paths.CacheSize()
	if n != 0 {
		t.Fatalf("old handle refilled cleared cache: %d", n)
	}
	ConfigureMediaCache(0)
	expectVersion(t, third, 2, 1)
}

func TestPersistentProcessRestart(t *testing.T) {
	if dir := os.Getenv("LP_CACHE_TEST_ROOT"); dir != "" {
		paths.SetRoot(dir)
		h, e := StartCached(context.Background(), os.Getenv("LP_CACHE_TEST_UPSTREAM"), 2, 128<<20, false, CacheOptions{Identity: "process-identity", Budget: 1 << 30})
		if e != nil {
			t.Fatal(e)
		}
		_, _, b := getRange(t, h, 0, ChunkSize-1)
		h.Close()
		if _, err := os.Stat(h.origin.disk.blockPath(h.origin.disk.slotOf(0))); err != nil {
			t.Fatalf("complete block missing after close (persistent=%v, validator=%q): %v", h.origin.disk.persistent, h.origin.validator, err)
		}
		if len(b) != int(ChunkSize) {
			t.Fatal("short child read")
		}
		return
	}
	dir := t.TempDir()
	u := persistentOrigin(t)
	for i := 0; i < 2; i++ {
		cmd := exec.Command(os.Args[0], "-test.run=^TestPersistentProcessRestart$")
		cmd.Env = append(os.Environ(), "LP_CACHE_TEST_ROOT="+dir, "LP_CACHE_TEST_UPSTREAM="+u.URL+fmt.Sprint("/file?authorization=", i))
		if out, e := cmd.CombinedOutput(); e != nil {
			t.Fatalf("child: %v %s", e, out)
		}
	}
	if u.downloads.Load() != 1 {
		t.Fatalf("process restart downloaded %d blocks", u.downloads.Load())
	}
}

func TestPersistentStopCancelsPinnedFetch(t *testing.T) {
	paths.SetRoot(t.TempDir())
	started := make(chan struct{})
	canceled := make(chan struct{})
	var count atomic.Int64
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		a, b, _ := parseRange(r.Header.Get("Range"))
		w.Header().Set("ETag", `"v1"`)
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", a, b, testTotal))
		w.Header().Set("Content-Length", strconv.FormatInt(b-a+1, 10))
		w.WriteHeader(206)
		if b == 0 {
			_, _ = w.Write([]byte{0})
			return
		}
		count.Add(1)
		close(started)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
		close(canceled)
	}))
	defer up.Close()
	h, e := StartCached(context.Background(), up.URL, 2, 128<<20, false, CacheOptions{Identity: "identity", Budget: 1 << 30})
	if e != nil {
		t.Fatal(e)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		resp, e := http.Get(h.URL)
		if e == nil {
			_, _ = io.Copy(io.Discard, resp.Body)
			_ = resp.Body.Close()
		}
	}()
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("fetch not started")
	}
	closed := make(chan struct{})
	go func() { h.Close(); close(closed) }()
	select {
	case <-closed:
	case <-time.After(3 * time.Second):
		t.Fatal("stop did not return")
	}
	select {
	case <-canceled:
	case <-time.After(3 * time.Second):
		t.Fatal("pinned request not canceled")
	}
	<-done
	time.Sleep(50 * time.Millisecond)
	if count.Load() != 1 || h.origin.disk.has(0) {
		t.Fatal("stop retried or committed partial block")
	}
}

func TestPersistentSingleMediaBoundAndPinnedIndexes(t *testing.T) {
	paths.SetRoot(t.TempDir())
	ConfigureMediaCache(1 << 30)
	d, e := newPersistentCache(100*ChunkSize, "large-item", `"version"`)
	if e != nil {
		t.Fatal(e)
	}
	defer d.close()
	for _, c := range []int64{0, 98, 99} {
		if !d.put(c, bodyAt(c*ChunkSize, int(ChunkSize))) {
			t.Fatal("index not stored")
		}
	}
	for c := int64(1); c < 98; c++ {
		if !d.put(c, bodyAt(c*ChunkSize, int(ChunkSize))) {
			t.Fatal("block not stored")
		}
	}
	if d.sizeOnDisk() > mediaLimit {
		t.Fatal("single media budget exceeded")
	}
	for _, c := range []int64{0, 98, 99} {
		if !bytes.Equal(d.get(c, int(ChunkSize)), bodyAt(c*ChunkSize, int(ChunkSize))) {
			t.Fatal("head/tail evicted by ring")
		}
	}
}
func TestPersistentDisabledAlsoBoundsTransientDisk(t *testing.T) {
	paths.SetRoot(t.TempDir())
	u := persistentOrigin(t)
	h := persistentOpen(t, u, "disabled", 0)
	expectVersion(t, h, 0, 1)
	if n, _ := paths.CacheSize(); n != 0 {
		t.Fatalf("disabled cache wrote %d bytes", n)
	}
	ConfigureMediaCache(1 << 30)
}

func TestPersistentGlobalBudgetWithConcurrentWeakStreams(t *testing.T) {
	paths.SetRoot(t.TempDir())
	u := persistentOrigin(t)
	u.mode.Store(1)
	handles := make([]*Handle, 4)
	for i := range handles {
		handles[i] = persistentOpen(t, u, fmt.Sprint("identity", i), 9<<20)
	}
	var wg sync.WaitGroup
	for i, h := range handles {
		wg.Add(1)
		go func(i int, h *Handle) {
			defer wg.Done()
			for j := 0; j < 4; j++ {
				c := int64((i + j) % 8)
				_, _, b := getRange(t, h, c*ChunkSize, (c+1)*ChunkSize-1)
				want := bodyAt(c*ChunkSize, int(ChunkSize))
				for k := range want {
					want[k] ^= 1
				}
				if !bytes.Equal(b, want) {
					t.Errorf("weak stream short/wrong data: %d", len(b))
					return
				}
				n, e := paths.CacheSize()
				if e != nil || n > 9<<20 {
					t.Errorf("budget exceeded: %d %v", n, e)
					return
				}
			}
		}(i, h)
	}
	wg.Wait()
}

func TestWorkerClaimIsVisibleBeforeCacheLookup(t *testing.T) {
	paths.SetRoot(t.TempDir())
	u := persistentOrigin(t)
	h := persistentOpen(t, u, "claim", 1<<30)
	s := &stream{o: h.origin, firstChunk: 0, lastChunk: 0, failed: map[int64]bool{}, inFlight: map[int64]bool{}, ready: map[int64]*live{}, done: make(chan struct{})}
	ctx, cancel := context.WithCancel(h.origin.ctx)
	finished := make(chan struct{})
	mediaStore.Lock()
	go func() { defer close(finished); s.worker(ctx) }()
	claimed := false
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		s.mu.Lock()
		cursor := s.fetchCursor
		claimed = s.inFlight[0]
		s.mu.Unlock()
		if cursor == 1 {
			break
		}
		time.Sleep(time.Millisecond)
	}
	cancel()
	s.finish()
	mediaStore.Unlock()
	<-finished
	if !claimed {
		t.Fatal("cursor advanced before in-flight claim; supply can schedule duplicate download")
	}
}

// BenchmarkPersistentRestoreFullWindow 量化满窗口重开时的读取与SHA校验成本。
func BenchmarkPersistentRestoreFullWindow(b *testing.B) {
	paths.SetRoot(b.TempDir())
	if err := ConfigureMediaCache(1 << 30); err != nil {
		b.Fatal(err)
	}
	total := 32 * ChunkSize
	d, err := newPersistentCache(total, "benchmark", "version")
	if err != nil {
		b.Fatal(err)
	}
	data := make([]byte, ChunkSize)
	for c := int64(0); c < d.ring; c++ {
		if !d.put(c, data) {
			b.Fatal("cache write failed")
		}
	}
	blocks := len(d.slots)
	d.close()
	b.SetBytes(int64(blocks) * ChunkSize)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		d, err = newPersistentCache(total, "benchmark", "version")
		if err != nil {
			b.Fatal(err)
		}
		if len(d.slots) != blocks {
			b.Fatal("restore lost blocks")
		}
		d.close()
	}
}
