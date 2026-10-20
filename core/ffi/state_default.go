//go:build !tvos

package main

const platformStateRequired = false

func restorePlatformState() error { return nil }
