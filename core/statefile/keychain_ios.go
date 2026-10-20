//go:build ios && tvos

package statefile

/*
#cgo LDFLAGS: -framework Security -framework CoreFoundation
#include "keychain.h"
*/
import "C"

import (
	"fmt"
	"os"
	"unsafe"
)

type Keychain struct{}

func (Keychain) Read(name string) ([]byte, error) {
	key := C.CString(name)
	defer C.free(unsafe.Pointer(key))
	var p unsafe.Pointer
	var n C.int
	status := C.lp_state_read(key, &p, &n)
	if status == C.errSecItemNotFound {
		return nil, os.ErrNotExist
	}
	if status != C.errSecSuccess {
		return nil, fmt.Errorf("安全存储读取失败 (%d)", status)
	}
	defer C.free(p)
	return C.GoBytes(p, n), nil
}

func (Keychain) Write(name string, data []byte) error {
	key := C.CString(name)
	defer C.free(unsafe.Pointer(key))
	p := C.CBytes(data)
	defer C.free(p)
	if status := C.lp_state_write(key, p, C.int(len(data))); status != C.errSecSuccess {
		return fmt.Errorf("安全存储保存失败 (%d)", status)
	}
	return nil
}
