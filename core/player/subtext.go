package player

import (
	"strconv"
	"strings"
)

// subIDOf 挂完之后当前选中的字幕轨号。挂上就选中是常态,所以直接读 sid。
func subIDOf(string) int {
	n, _ := strconv.Atoi(strings.TrimSpace(Prop("sid")))
	return n
}
