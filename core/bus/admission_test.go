package bus

import (
	"context"
	"os"
	"os/exec"
	"testing"
	"time"
)

// 生命周期是进程单例;每个场景在子进程中运行,不重置生产状态或污染其他测试。
func Test命令接纳边界(t *testing.T) {
	if scenario := os.Getenv("LP_ADMISSION_TEST"); scenario != "" {
		started.Store(true)
		q = newQueue()
		jobs = make(chan job, 1)
		switch scenario {
		case "full":
			jobs <- job{}
			done := make(chan error, 1)
			go func() { done <- Call(1, "test", "{}") }()
			select {
			case err := <-done:
				if err == nil {
					t.Fatal("满队列仍接纳命令")
				}
				if len(inflight) != 0 {
					t.Fatal("拒绝的命令残留在途状态")
				}
			case <-time.After(time.Second):
				t.Fatal("满队列阻塞调用方")
			}
		case "duplicate":
			ctx, cancel := context.WithCancel(context.Background())
			inflight[1] = cancel
			if err := Call(1, "test", "{}"); err == nil {
				t.Fatal("重复 seq 被接纳")
			}
			Cancel(1)
			if ctx.Err() == nil {
				t.Fatal("原命令取消句柄被覆盖")
			}
		case "shutdown":
			for i := 0; i < 32; i++ {
				shuttingDown.Store(false)
				jobs = make(chan job, 1)
				inflight = map[int64]context.CancelFunc{}
				q = newQueue()
				inflightMu.Lock()
				called := make(chan error, 1)
				go func() { called <- Call(1, "test", "{}") }()
				time.Sleep(2 * time.Millisecond)
				stopped := make(chan struct{})
				go func() { Shutdown(); close(stopped) }()
				time.Sleep(2 * time.Millisecond)
				closing := shuttingDown.Load()
				inflightMu.Unlock()
				if err := <-called; closing && err == nil {
					t.Fatal("接纳边界关闭后仍受理命令")
				}
				<-stopped
				select {
				case j, ok := <-jobs:
					if ok && j.ctx.Err() == nil {
						t.Fatal("关停后接纳了未取消的任务")
					}
				default:
				}
				if err := Call(2, "test", "{}"); err == nil {
					t.Fatal("关停后仍接纳新任务")
				}
			}
		}
		return
	}
	for _, scenario := range []string{"full", "duplicate", "shutdown"} {
		t.Run(scenario, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^Test命令接纳边界$")
			cmd.Env = append(os.Environ(), "LP_ADMISSION_TEST="+scenario)
			if out, err := cmd.CombinedOutput(); err != nil {
				t.Fatalf("%v\n%s", err, out)
			}
		})
	}
}
