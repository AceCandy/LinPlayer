package bus

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestContextEventCanCancelWhileQueueIsFull(t *testing.T) {
	queue := newQueue()
	defer queue.close()
	for i := 0; i < queueCap; i++ {
		queue.push(&Event{T: "result"})
	}
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Millisecond)
	defer cancel()
	if err := queue.pushContext(ctx, &Event{T: "event", Name: "companion.request"}); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal(err)
	}
	queue.mu.Lock()
	count := queue.l.Len()
	queue.mu.Unlock()
	if count != queueCap {
		t.Fatal("已取消的事件进入队列")
	}
}
