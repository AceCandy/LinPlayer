//go:build ios && tvos

package main

import (
	"linplayer/core/blocklist"
	"linplayer/core/config"
	"linplayer/core/statefile"
)

const platformStateRequired = true

func restorePlatformState() error {
	s := statefile.Keychain{}
	statefile.Install(s)
	if err := statefile.EnsurePrivacy(s); err != nil {
		return err
	}
	if err := blocklist.LoadStrict(); err != nil {
		return err
	}
	c, err := config.Load()
	if err != nil {
		return err
	}
	if c.DeviceID == "" {
		c.DeviceID, err = statefile.NewDeviceID()
		if err != nil {
			return err
		}
		return c.Save()
	}
	return nil
}
