package statefile

import (
	"errors"
	"os"
	"testing"
)

type fake struct {
	data map[string][]byte
	fail error
}

func (f *fake) Read(k string) ([]byte, error) {
	if f.fail != nil {
		return nil, f.fail
	}
	b, ok := f.data[k]
	if !ok {
		return nil, os.ErrNotExist
	}
	return b, nil
}
func (f *fake) Write(k string, b []byte) error {
	if f.fail != nil {
		return f.fail
	}
	f.data[k] = b
	return nil
}

func TestPrivacyBootstrapAndMissingState(t *testing.T) {
	f := &fake{data: map[string][]byte{}}
	if err := EnsurePrivacy(f); err != nil || string(f.data["blocklist.json"]) != "[]" {
		t.Fatal("新装未初始化名单", err)
	}
	delete(f.data, "blocklist.json")
	f.data["config.json"] = []byte("{}")
	if EnsurePrivacy(f) == nil || f.data["blocklist.json"] != nil {
		t.Fatal("已有配置缺隐私状态时放开了过滤")
	}
	f.fail = errors.New("unavailable")
	if EnsurePrivacy(f) == nil {
		t.Fatal("安全存储失败被吞")
	}
}
