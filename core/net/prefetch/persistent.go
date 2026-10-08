package prefetch

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"linplayer/core/paths"
)

const (
	mediaLimit  int64 = 128 << 20
	mediaTTL          = 7 * 24 * time.Hour
	blockHeader       = 64
)

// 磁盘读写、淘汰和清理共用锁，读取完成后才允许删除文件。
// 当前单播放器的块级串行磁盘操作有界；多路同时播放时再评估锁粒度。
var mediaStore = struct {
	sync.Mutex
	budget int64
	disks  map[*diskCache]bool
}{budget: 1 << 30, disks: make(map[*diskCache]bool)}

type mediaIndex struct {
	Version   int   `json:"version"`
	Total     int64 `json:"total"`
	Ring      int64 `json:"ring"`
	ChunkSize int64 `json:"chunk_size"`
}

// ConfigureMediaCache 立即收紧全局预算；关闭后已有代理继续供给，但不再写持久缓存。
func ConfigureMediaCache(budget int64) error {
	mediaStore.Lock()
	defer mediaStore.Unlock()
	mediaStore.budget = max64(budget, 0)
	if budget <= 0 {
		for d := range mediaStore.disks {
			if d.persistent {
				d.storeDisabled = true
				d.slots = map[int64]int64{}
			}
		}
	}
	return trimMediaLocked(0, "")
}

// ClearCache 在媒体写入屏障内调用统一清理，旧代理随后只做内存供给，不重新填盘。
func ClearCache(clear func() error) error {
	mediaStore.Lock()
	defer mediaStore.Unlock()
	for d := range mediaStore.disks {
		d.storeDisabled = true
		d.slots = map[int64]int64{}
		if d.f != nil {
			_ = d.f.Truncate(0)
			// Windows不允许删除仍打开的会话文件；后续供给已改走ready/live。
			_ = d.f.Close()
		}
	}
	return clear()
}

func mediaDirKey(identity, validator string, total int64) string {
	b, _ := json.Marshal([]any{1, identity, validator, total})
	return fmt.Sprintf("%x", sha256.Sum256(b))
}

func newPersistentCache(total int64, identity, validator string) (*diskCache, error) {
	mediaStore.Lock()
	defer mediaStore.Unlock()
	// 一个槽最多含4MiB数据和64字节校验头；索引与临时写入也计入上限。
	ring := (mediaLimit - 4096) / (ChunkSize + blockHeader)
	if n := (total + ChunkSize - 1) / ChunkSize; ring > n {
		ring = n
	}
	d := &diskCache{mu: &mediaStore.Mutex, total: total, ring: ring, slots: map[int64]int64{}, persistent: true,
		path: filepath.Join(paths.MediaCache(), mediaDirKey(identity, validator, total))}
	if err := trimMediaLocked(0, ""); err != nil {
		return nil, err
	}
	want := mediaIndex{1, total, ring, ChunkSize}
	var b []byte
	f, err := os.Open(filepath.Join(d.path, "index.json"))
	if err == nil {
		b, err = io.ReadAll(io.LimitReader(f, 1024))
		_ = f.Close()
	}
	if err == nil {
		var got mediaIndex
		if json.Unmarshal(b, &got) != nil || got != want {
			if err = os.RemoveAll(d.path); err != nil {
				return nil, err
			}
		}
	}
	if err := d.ensureIndexLocked(); err != nil {
		return nil, err
	}
	entries, err := os.ReadDir(d.path)
	if err != nil {
		return nil, err
	}
	for _, entry := range entries {
		name := entry.Name()
		if name == "index.json" {
			continue
		}
		slot, e := strconv.ParseInt(strings.TrimSuffix(name, ".block"), 10, 64)
		if e != nil || !strings.HasSuffix(name, ".block") || slot < 0 || slot >= ring {
			_ = os.Remove(filepath.Join(d.path, name))
			continue
		}
		c, data := d.readBlockLocked(slot)
		if data == nil || c < 0 || c*ChunkSize >= total || d.slotOf(c) != slot {
			_ = os.Remove(filepath.Join(d.path, name))
			continue
		}
		d.slots[slot] = c
	}
	now := time.Now()
	_ = os.Chtimes(d.path, now, now)
	mediaStore.disks[d] = true
	return d, nil
}

func (d *diskCache) ensureIndexLocked() error {
	if err := os.MkdirAll(d.path, 0700); err != nil {
		return err
	}
	name := filepath.Join(d.path, "index.json")
	if _, err := os.Stat(name); err == nil {
		return nil
	}
	b, _ := json.Marshal(mediaIndex{1, d.total, d.ring, ChunkSize})
	if err := trimMediaLocked(int64(len(b)), d.path); err != nil {
		return err
	}
	return atomicCacheWrite(name, b)
}

func atomicCacheWrite(name string, b []byte) error {
	f, err := os.CreateTemp(filepath.Dir(name), ".pending-")
	if err != nil {
		return err
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	_, err = f.Write(b)
	if err == nil {
		err = f.Sync()
	}
	if e := f.Close(); err == nil {
		err = e
	}
	if err != nil {
		return err
	}
	return os.Rename(tmp, name)
}

func (d *diskCache) blockPath(slot int64) string {
	return filepath.Join(d.path, strconv.FormatInt(slot, 10)+".block")
}

func (d *diskCache) readBlockLocked(slot int64) (int64, []byte) {
	f, err := os.Open(d.blockPath(slot))
	if err != nil {
		return -1, nil
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || info.Size() > ChunkSize+blockHeader {
		return -1, nil
	}
	b, err := io.ReadAll(io.LimitReader(f, ChunkSize+blockHeader))
	if err != nil || len(b) < blockHeader || !bytes.Equal(b[:8], []byte("LPCACHE1")) {
		return -1, nil
	}
	c := int64(binary.LittleEndian.Uint64(b[8:16]))
	if c < 0 || c >= (d.total+ChunkSize-1)/ChunkSize {
		return -1, nil
	}
	length := d.total - c*ChunkSize
	if length > ChunkSize {
		length = ChunkSize
	}
	if int64(len(b)) != length+blockHeader {
		return -1, nil
	}
	sum := sha256.Sum256(b[blockHeader:])
	if !bytes.Equal(sum[:], b[16:48]) {
		return -1, nil
	}
	return c, b[blockHeader:]
}

func (d *diskCache) putPersistentLocked(c int64, data []byte) bool {
	if d.storeDisabled || mediaStore.budget <= 0 || c < 0 || c >= (d.total+ChunkSize-1)/ChunkSize {
		return false
	}
	want := d.total - c*ChunkSize
	if want > ChunkSize {
		want = ChunkSize
	}
	if int64(len(data)) != want {
		return false
	}
	slot := d.slotOf(c)
	delete(d.slots, slot)
	if err := os.Remove(d.blockPath(slot)); err != nil && !os.IsNotExist(err) {
		return false
	}
	if err := d.ensureIndexLocked(); err != nil {
		return false
	}
	if err := trimMediaLocked(int64(len(data))+blockHeader, d.path); err != nil {
		return false
	}
	b := make([]byte, blockHeader+len(data))
	copy(b, []byte("LPCACHE1"))
	binary.LittleEndian.PutUint64(b[8:16], uint64(c))
	sum := sha256.Sum256(data)
	copy(b[16:48], sum[:])
	copy(b[blockHeader:], data)
	if atomicCacheWrite(d.blockPath(slot), b) != nil {
		return false
	}
	d.slots[slot] = c
	now := time.Now()
	_ = os.Chtimes(d.path, now, now)
	return true
}

type mediaUsage struct {
	path string
	size int64
	used time.Time
}

// trimMediaLocked 包括在播条目也可淘汰；读写持锁，失效槽会重取而不会读到半旧数据。
// 不以无限保护活动条目突破预算。单片保留头尾槽，空间紧张时全局规则优先。
func trimMediaLocked(extra int64, keep string) error {
	entries, err := os.ReadDir(paths.MediaCache())
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	var all []mediaUsage
	var used int64
	for _, e := range entries {
		p := filepath.Join(paths.MediaCache(), e.Name())
		info, err := e.Info()
		if err != nil {
			return err
		}
		var size int64
		err = filepath.WalkDir(p, func(_ string, e fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if !e.IsDir() {
				i, err := e.Info()
				if err != nil {
					return err
				}
				size += i.Size()
			}
			return nil
		})
		if err != nil {
			return err
		}
		if p != keep && (mediaStore.budget == 0 || time.Since(info.ModTime()) >= mediaTTL) {
			if err := removeMediaLocked(p); err != nil {
				return err
			}
			continue
		}
		used += size
		all = append(all, mediaUsage{p, size, info.ModTime()})
	}
	// 弱校验链路的会话ring也纳入总预算，关闭磁盘缓存时改为有界内存供给。
	transient, err := os.ReadDir(paths.PrefetchCache())
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	for _, e := range transient {
		if !strings.HasSuffix(e.Name(), ".part") {
			continue
		}
		i, err := e.Info()
		if err != nil {
			return err
		}
		p := filepath.Join(paths.PrefetchCache(), e.Name())
		used += i.Size()
		all = append(all, mediaUsage{p, i.Size(), i.ModTime()})
	}
	sort.Slice(all, func(i, j int) bool { return all[i].used.Before(all[j].used) })
	for _, e := range all {
		if used+extra <= mediaStore.budget {
			return nil
		}
		if e.path == keep {
			continue
		}
		if err := removeMediaLocked(e.path); err != nil {
			return err
		}
		used -= e.size
	}
	if used+extra > mediaStore.budget {
		// 同片已占满全局预算时先淘汰该片非固定槽，仍不足则不落盘。
		for d := range mediaStore.disks {
			if !d.persistent || d.path != keep {
				continue
			}
			for slot, c := range d.slots {
				if d.pinned(c) {
					continue
				}
				info, err := os.Stat(d.blockPath(slot))
				if err != nil {
					delete(d.slots, slot)
					continue
				}
				if err := os.Remove(d.blockPath(slot)); err != nil {
					return err
				}
				delete(d.slots, slot)
				used -= info.Size()
				if used+extra <= mediaStore.budget {
					return nil
				}
			}
		}
		return fmt.Errorf("媒体缓存空间不足")
	}
	return nil
}

func removeMediaLocked(p string) error {
	for d := range mediaStore.disks {
		if !d.persistent && d.path == p {
			if err := d.f.Truncate(0); err != nil {
				return err
			}
			d.slots = map[int64]int64{}
			return nil
		}
	}
	if err := os.RemoveAll(p); err != nil {
		return err
	}
	for d := range mediaStore.disks {
		if d.persistent && d.path == p {
			d.slots = map[int64]int64{}
		}
	}
	return nil
}
