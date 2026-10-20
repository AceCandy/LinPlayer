package config

import (
	"bytes"
	"errors"
	"os"
	"testing"

	"linplayer/core/statefile"
)

type memoryState struct {
	data              map[string][]byte
	readErr, writeErr error
}

func (s *memoryState) Read(k string) ([]byte, error) {
	if s.readErr != nil {
		return nil, s.readErr
	}
	b, ok := s.data[k]
	if !ok {
		return nil, os.ErrNotExist
	}
	return bytes.Clone(b), nil
}
func (s *memoryState) Write(k string, b []byte) error {
	if s.writeErr != nil {
		return s.writeErr
	}
	s.data[k] = bytes.Clone(b)
	return nil
}

func TestSecureRestoreSurvivesCacheRemovalAndLogout(t *testing.T) {
	root := withTempRoot(t)
	s := &memoryState{data: map[string][]byte{"config.json": []byte(realShapeFixture)}}
	statefile.Install(s)
	t.Cleanup(func() { statefile.Install(nil) })
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if err := c.Save(); err != nil {
		t.Fatal(err)
	}
	if err := os.RemoveAll(root); err != nil {
		t.Fatal(err)
	}
	c, err = Load()
	if err != nil {
		t.Fatal(err)
	}
	if c.DeviceID != "PLACEHOLDER-DEVICE-ID" || len(c.AccountList) != 1 {
		t.Fatal("安全配置未恢复")
	}
	if !bytes.Contains(s.data["config.json"], []byte("plugin_official_enabled")) {
		t.Fatal("未知字段丢失")
	}
	c.AccountList = nil
	c.Active = nil
	if err := c.Save(); err != nil {
		t.Fatal(err)
	}
	c, err = Load()
	if err != nil || len(c.AccountList) != 0 {
		t.Fatal("退出账号后复活")
	}
}

func TestSecureFailureNeverDefaultsOrOverwrites(t *testing.T) {
	withTempRoot(t)
	s := &memoryState{data: map[string][]byte{"config.json": []byte(realShapeFixture)}}
	statefile.Install(s)
	t.Cleanup(func() { statefile.Install(nil) })
	for _, b := range []string{"{", "null", "[]", ""} {
		s.data["config.json"] = []byte(b)
		if c, err := Load(); err == nil || c != nil {
			t.Fatal("损坏被当成新装")
		}
		if string(s.data["config.json"]) != b {
			t.Fatal("损坏原件被覆盖")
		}
	}
	s.readErr = errors.New("locked")
	if c, err := Load(); err == nil || c != nil {
		t.Fatal("不可访问被当成新装")
	}
	s.readErr = nil
	s.data["config.json"] = []byte(realShapeFixture)
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	before := bytes.Clone(s.data["config.json"])
	s.writeErr = errors.New("full")
	if c.Save() == nil || !bytes.Equal(before, s.data["config.json"]) {
		t.Fatal("保存失败未保留原件")
	}
}
