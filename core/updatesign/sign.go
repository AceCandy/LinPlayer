// Package updatesign 为更新校验清单提供项目专用 Ed25519 来源认证。
package updatesign

import (
	"crypto/ed25519"
	_ "embed"
	"encoding/base64"
	"fmt"
	"regexp"
	"strings"
)

//go:embed public-key.txt
var publicKeyText string

var releaseTag = regexp.MustCompile(`^v\d+\.\d+\.\d+(?:-build\d+)?(?:-pre|-dev)?$`)

// PublicKey 返回随客户端固定的公开验证密钥;私钥从不编进产物。
func PublicKey() ed25519.PublicKey {
	key, _ := base64.StdEncoding.DecodeString(strings.TrimSpace(publicKeyText))
	return key
}

func payload(tag string, manifest []byte) []byte {
	return append([]byte("LinPlayer update v1\n"+tag+"\n"), manifest...)
}

// Sign 将清单绑定到指定发布标签,避免跨版本或 stable/pre 签名重放。
func Sign(key ed25519.PrivateKey, tag string, manifest []byte) []byte {
	return []byte(base64.StdEncoding.EncodeToString(ed25519.Sign(key, payload(tag, manifest))) + "\n")
}

// Verify 严格验证固定公钥、发布标签和清单原始字节。
func Verify(key ed25519.PublicKey, tag string, manifest, signature []byte) error {
	sig, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(signature)))
	if !releaseTag.MatchString(tag) || len(key) != ed25519.PublicKeySize || err != nil || len(sig) != ed25519.SignatureSize || !ed25519.Verify(key, payload(tag, manifest), sig) {
		return fmt.Errorf("更新签名无效或来源不可信,未安装")
	}
	return nil
}
