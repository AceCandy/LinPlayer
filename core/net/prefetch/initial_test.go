package prefetch

import (
	"bytes"
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"linplayer/core/paths"
)

func TestInitialResponseOwnership(t *testing.T) {
	for _, mode := range []string{"cold", "offset", "pinned_offset", "short", "concurrent", "late_body", "nonzero", "unused", "prepare", "restricted", "bad_range", "no_store", "redirect", "short_retry", "version_change", "cancel_start", "clear_during_probe", "ignore_range"} {
		t.Run(mode, func(t *testing.T) {
			paths.SetRoot(t.TempDir())
			total := int64(2 * ChunkSize)
			if mode == "pinned_offset" {
				total = 8 * ChunkSize
			}
			if mode == "short" {
				total = 173 * 1024
			}
			var count atomic.Int64
			var redirectCount atomic.Int64
			firstRange := make(chan string, 1)
			release := make(chan struct{})
			cancelled := make(chan struct{})
			var once sync.Once
			unblock := func() { once.Do(func() { close(release) }) }
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/redirect" {
					redirectCount.Add(1)
					w.Header().Set("Cache-Control", "max-age=60")
					http.Redirect(w, r, "/video", 302)
					return
				}
				n := count.Add(1)
				if mode == "cancel_start" {
					<-r.Context().Done()
					close(cancelled)
					return
				}
				if mode == "clear_during_probe" {
					_ = ClearCache(func() error { return nil })
				}
				a, b, _ := parseRange(r.Header.Get("Range"))
				b = min(b, total-1)
				w.Header().Set("ETag", `"v1"`)
				if mode == "version_change" && n > 1 {
					w.Header().Set("ETag", `"v2"`)
				}
				if mode == "no_store" {
					w.Header().Set("Cache-Control", "no-store")
				}
				w.Header().Set("Content-Length", fmt.Sprint(b-a+1))
				start := a
				if mode == "bad_range" && n == 1 {
					start++
				}
				w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, b, total))
				if mode == "ignore_range" {
					w.Header().Del("Content-Range")
					w.WriteHeader(200)
				} else {
					w.WriteHeader(206)
				}
				w.(http.Flusher).Flush()
				if n == 1 {
					firstRange <- r.Header.Get("Range")
					select {
					case <-release:
					case <-r.Context().Done():
						close(cancelled)
						return
					}
				}
				size := int(b - a + 1)
				if n == 1 && (mode == "short_retry" || mode == "version_change") {
					size /= 2
				}
				_, _ = w.Write(bodyAt(a, size))
			}))
			defer up.Close()
			defer unblock()
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			options := CacheOptions{StartPlayback: mode != "prepare", Identity: mode, Budget: 1 << 30, Epoch: MediaCacheEpoch()}
			if mode == "restricted" {
				options.Reads = NewReadControl(NewReadBudget())
			}
			u := up.URL + "/video"
			if mode == "redirect" {
				u = up.URL + "/redirect"
			}
			h, err := StartCached(ctx, u, 2, 32<<20, false, options)
			if mode == "cancel_start" || mode == "clear_during_probe" || mode == "ignore_range" {
				if err == nil {
					h.Close()
					t.Fatal("invalid preparation accepted")
				}
				select {
				case <-cancelled:
				case <-time.After(time.Second):
					t.Fatal("failed preparation leaked response")
				}
				return
			}
			if err != nil {
				t.Fatalf("headers must suffice before body arrives: %v", err)
			}
			defer h.Close()
			cancel() // 返回后命令取消不再拥有媒体响应。
			switch mode {
			case "prepare", "restricted":
				if got := <-firstRange; got != "bytes=0-0" {
					t.Fatalf("preparation read media: %s", got)
				}
				h.Close()
			case "unused":
				h.Close()
			case "offset", "pinned_offset":
				unblock()
				_, _, data := getRange(t, h, 1, 127)
				h.Close()
				want := int64(2)
				if mode == "pinned_offset" {
					want = 1
				}
				if !bytes.Equal(data, bodyAt(1, 127)) || count.Load() != want {
					t.Fatalf("first-block offset: length=%d first=%v requests=%d", len(data), data[:min(len(data), 8)], count.Load())
				}
				return
			case "nonzero":
				_, _, data := getRange(t, h, ChunkSize, ChunkSize+127)
				if !bytes.Equal(data, bodyAt(ChunkSize, 128)) {
					t.Fatal("wrong offset")
				}
			default:
				if mode == "late_body" {
					time.Sleep(8100 * time.Millisecond)
				}
				unblock()
				end := min(ChunkSize, total) - 1
				if mode == "version_change" {
					_, _, data := getRange(t, h, 0, end)
					if len(data) != int(ChunkSize/2) || !bytes.Equal(data, bodyAt(0, len(data))) || h.origin.disk.has(0) {
						t.Fatal("mixed versions or committed incomplete head")
					}
					return
				}
				read := func() {
					_, _, data := getRange(t, h, 0, end)
					if !bytes.Equal(data, bodyAt(0, int(end+1))) {
						t.Errorf("incorrect first block: %d", len(data))
					}
				}
				if mode == "concurrent" {
					var wg sync.WaitGroup
					for i := 0; i < 3; i++ {
						wg.Add(1)
						go func() { defer wg.Done(); read() }()
					}
					wg.Wait()
				} else {
					read()
				}
				h.Close()
				want := int64(1)
				if mode == "bad_range" || mode == "short_retry" {
					want = 2
				}
				if mode == "concurrent" {
					want = 3
				}
				if got := count.Load(); (mode != "concurrent" && got != want) || got > want {
					t.Fatalf("first block requests=%d want=%d", got, want)
				}
				if mode == "no_store" && h.origin.disk.persistent {
					t.Fatal("no-store response persisted")
				}
				if mode == "redirect" && redirectCount.Load() != 1 {
					t.Fatal("redirect repeated")
				}
				return
			}
			select {
			case <-cancelled:
			case <-time.After(time.Second):
				t.Fatal("unused probe response leaked")
			}
		})
	}
}

func TestInitialProbeUsesRestoredHeadAndClosesBody(t *testing.T) {
	paths.SetRoot(t.TempDir())
	up := persistentOrigin(t)
	defer up.Close()
	options := CacheOptions{Identity: "initial-test", Budget: 1 << 30, StartPlayback: true}
	h, err := StartCached(context.Background(), up.URL, 2, 32<<20, false, options)
	if err != nil {
		t.Fatal(err)
	}
	_, _, first := getRange(t, h, 0, ChunkSize-1)
	h.Close()
	for _, corrupt := range []bool{false, true} {
		h, err = StartCached(context.Background(), up.URL, 2, 32<<20, false, options)
		if err != nil {
			t.Fatal(err)
		}
		if r := h.origin.takeInitial(); r != nil {
			r.close()
			h.Close()
			t.Fatal("restored head kept unused network body")
		}
		if corrupt {
			// 模拟恢复后文件损坏；实际供给仍须验证并重新取数。
			h.origin.disk.mu.Lock()
			slot := h.origin.disk.slotOf(0)
			name := h.origin.disk.blockPath(slot)
			h.origin.disk.mu.Unlock()
			f, err := os.OpenFile(name, os.O_WRONLY, 0600)
			if err != nil {
				h.Close()
				t.Fatal(err)
			}
			_, err = f.WriteAt([]byte{99}, blockHeader+200)
			_ = f.Close()
			if err != nil {
				h.Close()
				t.Fatal(err)
			}
		}
		before := up.downloads.Load()
		_, _, again := getRange(t, h, 0, ChunkSize-1)
		h.Close()
		if !bytes.Equal(first, again) {
			t.Fatal("restored/corrected bytes differ")
		}
		want := int64(0)
		if corrupt {
			want = 1
		}
		if up.downloads.Load()-before != want {
			t.Fatal("unexpected body refetch")
		}
	}
}
