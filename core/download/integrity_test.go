package download

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestRange响应错误不能写入分段(t *testing.T) {
	for _, tc := range []struct {
		name, cr, body string
		status         int
	}{
		{"忽略Range", "", "abcdefgh", 200},
		{"错误起点", "bytes 0-3/8", "abcd", 206},
		{"错误总长", "bytes 4-7/9", "efgh", 206},
		{"超长响应", "bytes 4-7/8", "efghx", 206},
	} {
		t.Run(tc.name, func(t *testing.T) {
			up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Range", tc.cr)
				w.WriteHeader(tc.status)
				_, _ = w.Write([]byte(tc.body))
			}))
			defer up.Close()
			m := mgr(t)
			it := &Item{URL: up.URL, TotalBytes: 8, SupportsRange: true, Segments: []Segment{{Start: 4, End: 7}}}
			part := filepath.Join(t.TempDir(), "part")
			_, err := m.fetchSegment("x", 0, it, it.Segments[0], part, 0, make(chan struct{}), make(chan struct{}))
			if err == nil {
				t.Fatal("错误的 Range 响应被当成成功")
			}
			if st, e := os.Stat(part); e == nil && st.Size() > 4 {
				t.Fatal("超长字节污染了分段")
			}
		})
	}
}

func Test合并缺段或长度错误保留原文件与分段(t *testing.T) {
	for _, size := range []int{-1, 2, 5} {
		t.Run(string(rune('a'+size+1)), func(t *testing.T) {
			it := &Item{FilePath: filepath.Join(t.TempDir(), "movie"), TotalBytes: 8,
				Segments: []Segment{{Start: 0, End: 3}, {Start: 4, End: 7}}}
			if err := os.WriteFile(it.FilePath, []byte("原文件"), 0600); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(it.partPath(0), []byte("abcd"), 0600); err != nil {
				t.Fatal(err)
			}
			if size >= 0 {
				if err := os.WriteFile(it.partPath(1), make([]byte, size), 0600); err != nil {
					t.Fatal(err)
				}
			}
			if err := assemble(it); err == nil {
				t.Fatal("缺段或长度错误也合并成功")
			}
			b, _ := os.ReadFile(it.FilePath)
			if string(b) != "原文件" {
				t.Fatal("失败合并覆盖了原文件")
			}
			if _, err := os.Stat(it.partPath(0)); err != nil {
				t.Fatal("失败后丢了续传分段")
			}
		})
	}
}
