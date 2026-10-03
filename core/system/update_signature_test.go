package system

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"linplayer/core/paths"
	"linplayer/core/updatesign"
)

func signingTestKey(t *testing.T) ed25519.PrivateKey {
	t.Helper()
	pub, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	old := updateSigningPublicKey
	updateSigningPublicKey = pub
	t.Cleanup(func() { updateSigningPublicKey = old })
	return key
}

func Test更新下载必须验证签名且绑定标签(t *testing.T) {
	paths.SetRoot(t.TempDir())
	key := signingTestKey(t)
	manifest := []byte(fmt.Sprintf("%x  package.zip\n", sha256.Sum256([]byte("good"))))
	sig := updatesign.Sign(key, "v2.0.0", manifest)
	assetRequests := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/sums":
			_, _ = w.Write(manifest)
		case "/sig":
			_, _ = w.Write(sig)
		default:
			assetRequests++
			_, _ = w.Write([]byte("good"))
		}
	}))
	defer srv.Close()
	info := UpdateInfo{Tag: "v2.0.0", AssetName: "package.zip", AssetURL: srv.URL + "/asset", ChecksumURL: srv.URL + "/sums", SignatureURL: srv.URL + "/sig"}
	if _, err := assetChecksum(context.Background(), &info); err != nil {
		t.Fatal(err)
	}
	for _, mode := range []string{"missing", "tampered", "replay", "wrong-key"} {
		t.Run(mode, func(t *testing.T) {
			copy := info
			saved := sig
			defer func() { sig = saved }()
			switch mode {
			case "missing":
				copy.SignatureURL = ""
			case "tampered":
				sig = []byte("invalid")
			case "replay":
				copy.Tag = "v2.0.1"
			case "wrong-key":
				_, other, _ := ed25519.GenerateKey(rand.Reader)
				sig = updatesign.Sign(other, info.Tag, manifest)
			}
			if _, err := fetchAsset(context.Background(), &copy, func(int64) {}); err == nil || !strings.Contains(err.Error(), "签名") {
				t.Fatalf("不可信更新未拒绝: %v", err)
			}
		})
	}
	if assetRequests != 0 {
		t.Fatal("验签失败后仍下载了可执行包")
	}
}
