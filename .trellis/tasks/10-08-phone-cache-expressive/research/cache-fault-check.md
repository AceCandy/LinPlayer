# 缓存忠实故障注入

每项临时注入后测试失败，随后恢复原文件；最终绿与race另记。

range-version: RED
--- FAIL: TestPersistentRejectsWrongRangeOrVersionBeforeFeed (0.10s)
    --- FAIL: TestPersistentRejectsWrongRangeOrVersionBeforeFeed/3 (0.05s)
        persistent_test.go:170: invalid range/version reached player or disk
    --- FAIL: TestPersistentRejectsWrongRangeOrVersionBeforeFeed/4 (0.03s)
        persistent_test.go:170: invalid range/version reached player or disk
    --- FAIL: TestPersistentRejectsWrongRangeOrVersionBeforeFeed/5 (0.03s)
        persistent_test.go:170: invalid range/version reached player or disk
FAIL
FAIL	linplayer/core/net/prefetch	0.106s
FAIL

block-integrity: RED
--- FAIL: TestPersistentDamagedAndUncommittedBlocksReload (0.06s)
    --- FAIL: TestPersistentDamagedAndUncommittedBlocksReload/hash (0.06s)
        persistent_test.go:214: wrong bytes/length: 4194304
FAIL
FAIL	linplayer/core/net/prefetch	0.068s
FAIL

ttl: RED
--- FAIL: TestPersistentBudgetTTLAndActiveClear (0.08s)
    persistent_test.go:238: expired entry retained
FAIL
FAIL	linplayer/core/net/prefetch	0.088s
FAIL

budget: RED
--- FAIL: TestPersistentGlobalBudgetWithConcurrentWeakStreams (0.04s)
    persistent_test.go:418: budget exceeded: 62914560 <nil>
    persistent_test.go:418: budget exceeded: 62914560 <nil>
    persistent_test.go:418: budget exceeded: 62914560 <nil>
    persistent_test.go:418: budget exceeded: 62914560 <nil>
FAIL
FAIL	linplayer/core/net/prefetch	0.038s
FAIL
