package prefetch

import (
	"bytes"
	"context"
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
	// 单片上限须大于 mpv 默认向前缓冲(demuxer-max-bytes 150MiB),否则退出时续播位置的块已被向前读取的新块挤掉。
	// shortcut: 自定义缓冲接近单片上限时续播块仍可能被挤掉;需要时按缓冲目标放大单片上限。
	mediaLimit  int64 = 512 << 20
	mediaTTL          = 7 * 24 * time.Hour
	blockHeader       = 64
)

// 磁盘读写、淘汰和清理共用锁，读取完成后才允许删除文件。
// 当前单播放器的块级串行磁盘操作有界；多路同时播放时再评估锁粒度。
var mediaStore = struct {
	sync.Mutex
	budget int64
	disks  map[*diskCache]bool
	epoch  uint64
}{budget: 2 << 30, disks: make(map[*diskCache]bool), epoch: 1}

// MediaCacheEpoch 由流影在解析前捕获，拒绝清理前请求迟到创建磁盘缓存。
func MediaCacheEpoch() uint64 {
	mediaStore.Lock()
	defer mediaStore.Unlock()
	return mediaStore.epoch
}

func cacheEpochMatches(epochs []uint64) bool {
	return len(epochs) == 0 || epochs[0] == 0 || epochs[0] == mediaStore.epoch
}

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
	mediaStore.epoch++
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

// versionFile 记录这片缓存属于哪个身份的哪一版;重进时据此不等上游先开缓存。
const versionFile = "version.json"

type mediaVersion struct {
	Identity    string `json:"identity"`
	Validator   string `json:"validator"`
	Total       int64  `json:"total"`
	ContentType string `json:"content_type"`
	// Reported 是确认这一版时 PlaybackInfo 报的大小;strm/网盘源报的未必等于流长,所以只和上次报的比。
	Reported int64 `json:"reported"`
}

// saveMediaVersion 同步探测确认版本后写下记录;内容没变就不重写。
func saveMediaVersion(d *diskCache, v mediaVersion) {
	mediaStore.Lock()
	defer mediaStore.Unlock()
	b, _ := json.Marshal(v)
	name := filepath.Join(d.path, versionFile)
	if old, err := os.ReadFile(name); d.storeDisabled || err == nil && bytes.Equal(old, b) {
		return
	}
	_ = atomicCacheWrite(name, b)
}

// lastMediaVersion 找这个身份最近确认过的版本;PlaybackInfo 报的大小和确认时不同就不认。
func lastMediaVersion(identity string, reported int64) (mediaVersion, bool) {
	mediaStore.Lock()
	defer mediaStore.Unlock()
	entries, _ := os.ReadDir(paths.MediaCache())
	var best mediaVersion
	var bestAt time.Time
	found := false
	for _, e := range entries {
		b, err := os.ReadFile(filepath.Join(paths.MediaCache(), e.Name(), versionFile))
		var v mediaVersion
		if err != nil || json.Unmarshal(b, &v) != nil || v.Identity != identity || v.Validator == "" || v.Total <= 0 ||
			v.Reported != reported || mediaDirKey(identity, v.Validator, v.Total) != e.Name() {
			continue
		}
		info, err := e.Info()
		if err == nil && (!found || info.ModTime().After(bestAt)) {
			best, bestAt, found = v, info.ModTime(), true
		}
	}
	return best, found
}

// dropVersion 上游已换版本:删掉这一版缓存,版本记录随目录一起消失。
func (d *diskCache) dropVersion() {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.storeDisabled = true
	_ = removeMediaLocked(d.path)
}

func newPersistentCache(total int64, identity, validator string, epochs ...uint64) (*diskCache, error) {
	mediaStore.Lock()
	defer mediaStore.Unlock()
	if !cacheEpochMatches(epochs) {
		return nil, context.Canceled
	}
	// 一个槽最多含4MiB数据和64字节校验头；索引与临时写入也计入上限。
	ring := (mediaLimit - 4096) / (ChunkSize + blockHeader)
	if n := (total + ChunkSize - 1) / ChunkSize; ring > n {
		ring = n
	}
	d := &diskCache{mu: &mediaStore.Mutex, total: total, ring: ring, slots: map[int64]int64{}, pendingVerify: map[int64]bool{}, persistent: true,
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
		if name == "index.json" || name == versionFile {
			continue
		}
		slot, e := strconv.ParseInt(strings.TrimSuffix(name, ".block"), 10, 64)
		if e != nil || !strings.HasSuffix(name, ".block") || slot < 0 || slot >= ring {
			_ = os.Remove(filepath.Join(d.path, name))
			continue
		}
		c, data := d.readBlockLocked(slot, false)
		if data == nil || c < 0 || c*ChunkSize >= total || d.slotOf(c) != slot {
			_ = os.Remove(filepath.Join(d.path, name))
			continue
		}
		d.slots[slot] = c
		d.pendingVerify[slot] = true
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

// 恢复只读块头；实际供给必须验证完整正文。
func (d *diskCache) readBlockLocked(slot int64, verify bool) (int64, []byte) {
	f, err := os.Open(d.blockPath(slot))
	if err != nil {
		return -1, nil
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || info.Size() < blockHeader || info.Size() > ChunkSize+blockHeader {
		return -1, nil
	}
	var header [blockHeader]byte
	if _, err = io.ReadFull(f, header[:]); err != nil || !bytes.Equal(header[:8], []byte("LPCACHE1")) {
		return -1, nil
	}
	c := int64(binary.LittleEndian.Uint64(header[8:16]))
	if c < 0 || c >= (d.total+ChunkSize-1)/ChunkSize {
		return -1, nil
	}
	length := d.total - c*ChunkSize
	if length > ChunkSize {
		length = ChunkSize
	}
	if info.Size() != length+blockHeader {
		return -1, nil
	}
	if !verify {
		return c, header[:]
	}
	b := make([]byte, length)
	if _, err = io.ReadFull(f, b); err != nil {
		return -1, nil
	}
	sum := sha256.Sum256(b)
	if !bytes.Equal(sum[:], header[16:48]) {
		return -1, nil
	}
	return c, b
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
	delete(d.pendingVerify, slot)
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
