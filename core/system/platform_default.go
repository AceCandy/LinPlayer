//go:build !tvos

package system

import "runtime"

func platformName() string { return runtime.GOOS }
