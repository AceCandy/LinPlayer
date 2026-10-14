# 执行

1. 补真实 DefaultMediaSourceFactory/ExoPlayer 外挂 ASS 回归，先证明选轨成功但 libass 未开。
2. 最小修复共享同步入口，并覆盖主源前缀、无合并、原始冒号、数据晚到、切换/关闭。
3. 相关 Android 回归、独立审查、记录失败测试盲点和边界。
4. 工作提交、归档和会话记录，手机 release 打包验签；不推送。
