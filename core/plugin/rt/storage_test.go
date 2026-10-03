package rt

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func TestKV写盘失败仍保留待写状态(t *testing.T) {
	s, err := openKV(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	s.Set("k", json.RawMessage(`1`))
	// 让目标路径变成目录,原子替换必须失败。
	if err := os.Mkdir(s.path, 0700); err != nil {
		t.Fatal(err)
	}
	s.flush()
	s.mu.Lock()
	dirty := s.dirty
	s.mu.Unlock()
	if !dirty {
		t.Fatal("写盘失败却清掉 dirty,数据不会再次写入")
	}
	if err := os.Remove(s.path); err != nil {
		t.Fatal(err)
	}
	s.flush()
	reopened, err := openKV(filepath.Dir(s.path))
	if err != nil || string(reopened.Get("k")) != "1" {
		t.Fatalf("重试没有落盘: %v", err)
	}
}

func TestSecret并发修改重开不丢键(t *testing.T) {
	s, err := openSecrets(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for i := 0; i < 30; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			if err := s.Set(string(rune('a'+i)), "value"); err != nil {
				t.Error(err)
			}
		}(i)
	}
	wg.Wait()
	reopened, err := loadSecrets(s.path)
	if err != nil {
		t.Fatal(err)
	}
	if len(reopened.m) != 30 {
		t.Fatalf("并发写盘丢失键: %d/30", len(reopened.m))
	}
}
