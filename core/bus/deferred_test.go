package bus

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"testing"
	"time"
)

func TestDeferredKeepsWorkersAvailableAndBoundsInflight(t *testing.T) {
	if os.Getenv("LP_DEFERRED_TEST") == "" {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestDeferredKeepsWorkersAvailableAndBoundsInflight$")
		cmd.Env = append(os.Environ(), "LP_DEFERRED_TEST=1")
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("%v\n%s", err, out)
		}
		return
	}
	Init()
	defer Shutdown()
	entered := make(chan int64, 264)
	Register("test.deferred", func(ctx context.Context, seq int64, _ map[string]any) (any, error) {
		return Deferred(func() (any, error) {
			Partial(seq, map[string]bool{"local_stopped": true})
			entered <- seq
			<-ctx.Done()
			if seq == 1 {
				panic("test deferred panic")
			}
			return nil, ctx.Err()
		}), nil
	})
	Register("test.quick", func(context.Context, int64, map[string]any) (any, error) { return true, nil })
	for seq := int64(1); seq <= WorkerCount; seq++ {
		if err := Call(seq, "test.deferred", "{}"); err != nil {
			t.Fatal(err)
		}
		select {
		case <-entered:
		case <-time.After(time.Second):
			t.Fatal("deferred task blocked workers")
		}
	}
	if err := Call(1000, "test.quick", "{}"); err != nil {
		t.Fatal(err)
	}
	for {
		var e Event
		if err := json.Unmarshal(NextEvent(1000), &e); err != nil {
			t.Fatal("quick command blocked", err)
		}
		if e.T == "result" {
			if e.Seq != 1000 || e.OK == nil || !*e.OK {
				t.Fatalf("premature final result: %+v", e)
			}
			break
		}
	}
	last := int64(WorkerCount)
	for seq := last + 1; seq <= 266; seq++ {
		if err := Call(seq, "test.deferred", "{}"); err != nil {
			if e, ok := err.(*Err); !ok || e.Code != EBusy {
				t.Fatal(err)
			}
			break
		}
		last = seq
		select {
		case <-entered:
		case <-time.After(time.Second):
			t.Fatal("deferred admission stalled")
		}
	}
	if last > 264 || last < 263 {
		t.Fatalf("unexpected inflight bound: %d", last)
	}
	if err := Call(999, "test.quick", "{}"); err == nil {
		t.Fatal("full inflight set admitted another task")
	}
	for seq := int64(1); seq <= last; seq++ {
		Cancel(seq)
	}
	seen := map[int64]bool{}
	for int64(len(seen)) < last {
		var e Event
		if err := json.Unmarshal(NextEvent(1000), &e); err != nil {
			t.Fatal("missing final result", err)
		}
		if e.T != "result" {
			continue
		}
		if seen[e.Seq] || e.OK == nil || *e.OK {
			t.Fatalf("invalid final result: %+v", e)
		}
		seen[e.Seq] = true
	}
	if panicCount.Load() != 1 {
		t.Fatal("deferred panic escaped boundary")
	}
	out, err := Invoke(context.Background(), "test.quick", nil)
	if err != nil || out != true {
		t.Fatalf("sync invoke: %v %v", out, err)
	}
	Register("test.syncDeferred", func(context.Context, int64, map[string]any) (any, error) {
		return Deferred(func() (any, error) { return "finished", nil }), nil
	})
	out, err = Invoke(context.Background(), "test.syncDeferred", nil)
	if err != nil || out != "finished" {
		t.Fatalf("deferred sync invoke: %v %v", out, err)
	}
	closing := make(chan struct{})
	Register("test.shutdownDeferred", func(ctx context.Context, seq int64, _ map[string]any) (any, error) {
		return Deferred(func() (any, error) {
			close(closing)
			<-ctx.Done()
			return "settled", nil
		}), nil
	})
	if err := Call(2000, "test.shutdownDeferred", "{}"); err != nil {
		t.Fatal(err)
	}
	<-closing
	BeginShutdown()
	var final Event
	if err := json.Unmarshal(NextEvent(1000), &final); err != nil || final.Seq != 2000 || final.OK == nil || !*final.OK {
		t.Fatalf("shutdown lost committed result: %+v %v", final, err)
	}
}
