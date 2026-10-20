package blocklist

import (
	"bytes"
	"errors"
	"testing"

	"linplayer/core/statefile"
)

type secureFixture struct {
	b    []byte
	fail bool
}

func (s *secureFixture) Read(string) ([]byte, error) {
	if s.fail {
		return nil, errors.New("locked")
	}
	return bytes.Clone(s.b), nil
}
func (s *secureFixture) Write(_ string, b []byte) error {
	if s.fail {
		return errors.New("full")
	}
	s.b = bytes.Clone(b)
	return nil
}

func TestSecureBlocklistFailsClosed(t *testing.T) {
	s := &secureFixture{b: []byte(`[{"id":"blocked","name":"隐私条目"}]`)}
	statefile.Install(s)
	t.Cleanup(func() { statefile.Install(nil); Replace(nil) })
	if err := LoadStrict(); err != nil {
		t.Fatal(err)
	}
	s.fail = true
	if err := Set("blocked", "", false); err == nil || !IsBlockedID("blocked") {
		t.Fatal("保存失败仍解除了屏蔽")
	}
	if LoadStrict() == nil || !IsBlockedID("blocked") {
		t.Fatal("读取失败仍放开隐私过滤")
	}
	s.fail = false
	for _, invalid := range []string{"{", "null", "", "{}"} {
		s.b = []byte(invalid)
		if LoadStrict() == nil || !IsBlockedID("blocked") {
			t.Fatalf("损坏仍放开隐私过滤: %q", invalid)
		}
	}
}
