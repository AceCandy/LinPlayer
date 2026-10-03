package updatesign

import (
	"crypto/ed25519"
	"crypto/rand"
	"testing"
)

func Test签名绑定公钥标签和清单(t *testing.T) {
	pub, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	manifest := []byte("checksum  package.zip\n")
	sig := Sign(key, "v2.0.0-pre", manifest)
	if err := Verify(pub, "v2.0.0-pre", manifest, sig); err != nil {
		t.Fatal(err)
	}
	other, _, _ := ed25519.GenerateKey(rand.Reader)
	for _, c := range []struct {
		key       ed25519.PublicKey
		tag       string
		data, sig []byte
	}{
		{other, "v2.0.0-pre", manifest, sig},
		{pub, "v2.0.0", manifest, sig},
		{pub, "v2.0.1-pre", manifest, sig},
		{pub, "", manifest, sig},
		{pub, "v2.0.0-pre", append([]byte("changed"), manifest...), sig},
		{pub, "v2.0.0-pre", manifest, nil},
		{pub, "v2.0.0-pre", manifest, []byte("invalid")},
	} {
		if Verify(c.key, c.tag, c.data, c.sig) == nil {
			t.Fatal("错误来源或内容被接受")
		}
	}
	if len(PublicKey()) != ed25519.PublicKeySize {
		t.Fatal("项目公钥无效")
	}
}
