package localserve

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"io"
	"net/http"
	"net/url"
	"sync/atomic"
	"testing"

	"linplayer/core/imgcache"
)

func encodedPNG(t *testing.T, width, height int) []byte {
	t.Helper()
	im := image.NewRGBA(image.Rect(0, 0, width, height))
	for y := 0; y < height; y++ {
		for x := 0; x < width; x++ {
			im.SetRGBA(x, y, color.RGBA{R: 24, G: 28, B: 34, A: 255})
		}
	}
	var b bytes.Buffer
	if err := png.Encode(&b, im); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

func TestEmbyPlaceholderFailsAndRecovers(t *testing.T) {
	placeholder := encodedPNG(t, 1, 1)
	artwork := encodedPNG(t, 30, 20)
	var ready atomic.Bool
	var hits atomic.Int32
	s, up := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		if !ready.Load() {
			w.Header().Set("Cache-Control", "no-store")
			_, _ = w.Write(placeholder)
			return
		}
		_, _ = w.Write(artwork)
	})
	s.AllowEmby(up.URL, nil)
	src := up.URL + "/Items/show/Images/Backdrop/0?quality=90"
	route := "?src=" + url.QueryEscape(src) + "&h=720"
	key, _ := withSize(src, "", "720")
	if got := get(t, s, "/img"+route, true).StatusCode; got != http.StatusBadGateway {
		t.Fatalf("缺图的HTTP 200占位应触发图片失败回退，实得%d", got)
	}
	if imgcache.Get2L(key) != nil {
		t.Fatal("占位不能进入图片缓存")
	}
	if got := get(t, s, "/img-cache"+route, true).StatusCode; got != http.StatusNotFound || hits.Load() != 1 {
		t.Fatal("缓存专用路由应报未命中，且不回源")
	}
	ready.Store(true)
	resp := get(t, s, "/img"+route, true)
	got, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK || !bytes.Equal(got, artwork) {
		t.Fatal("服务器恢复图片后，应立即取到真实图片")
	}
	if got := get(t, s, "/img"+route, true).Header.Get("X-LP-Cache"); got != "hit" || hits.Load() != 2 {
		t.Fatal("真实图片仍应复用缓存")
	}
}

func TestOldEmbyPlaceholderCacheEvicts(t *testing.T) {
	for _, tier := range []string{"memory", "disk"} {
		t.Run(tier, func(t *testing.T) {
			artwork := encodedPNG(t, 20, 30)
			var hits atomic.Int32
			s, up := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
				hits.Add(1)
				_, _ = w.Write(artwork)
			})
			s.AllowEmby(up.URL, nil)
			src := up.URL + "/emby/Items/show/Images/Primary?quality=90"
			route := "?src=" + url.QueryEscape(src) + "&h=720"
			key, _ := withSize(src, "", "720")
			imgcache.Put2L(key, encodedPNG(t, 1, 1))
			imgcache.Put2L("unrelated", artwork)
			if tier == "disk" {
				imgcache.MemClear()
			}
			if got := get(t, s, "/img-cache"+route, true).StatusCode; got != http.StatusNotFound || hits.Load() != 0 {
				t.Fatal("旧占位应成为缓存未命中，缓存轮播不得回源")
			}
			if imgcache.Get2L(key) != nil || imgcache.Get(key) != nil {
				t.Fatal("旧占位必须从内存和磁盘同时移除")
			}
			if !bytes.Equal(imgcache.Get2L("unrelated"), artwork) {
				t.Fatal("不能清除其他正常缓存")
			}
			resp := get(t, s, "/img"+route, true)
			got, _ := io.ReadAll(resp.Body)
			if resp.StatusCode != http.StatusOK || !bytes.Equal(got, artwork) || hits.Load() != 1 {
				t.Fatal("普通图片路径应回源恢复真实图片")
			}
		})
	}
}

func TestOtherOnePixelImageKeepsCaching(t *testing.T) {
	artwork := encodedPNG(t, 1, 1)
	var hits atomic.Int32
	s, up := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		_, _ = w.Write(artwork)
	})
	s.Allow(up.URL, nil)
	route := "/img?src=" + url.QueryEscape(up.URL+"/pixel.png")
	if got := get(t, s, route, true).StatusCode; got != http.StatusOK {
		t.Fatal("其他图床的一像素图片保留原行为")
	}
	if got := get(t, s, route, true).Header.Get("X-LP-Cache"); got != "hit" || hits.Load() != 1 {
		t.Fatal("其他图床的一像素图片仍可缓存")
	}
}
