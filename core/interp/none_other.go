//go:build !android

package interp

import "errors"

// Supported 只有 Android 提供内建补帧。
const Supported = false

var algos []algo

// Device 非 Android 平台不提供补帧设备。
func Device() (string, int, error) { return "", 0, errors.New("这个平台没有补帧") }

// DisplayHz 返回宿主报告的屏幕刷新率。
func DisplayHz() float64 { return reported() }

// FilterString 非 Android 平台没有补帧滤镜。
func FilterString(spec Spec) string { return "" }
