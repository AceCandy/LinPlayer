# 验证记录

- 初始 ExoAssMergeTest 实际加载两条外挂，确认轨道 `1:` 前缀且默认轨选中；旧同步代码在等待 Native.assOpenFile 时失败。
- 修复后真实工厂/解析/合并链路通过：两条外挂切换、关闭及迟到数据；自制MKV内封字幕在有/无外挂时均正确打开头。
- LibassSelectionTest补充单层前缀、保留原ID冒号、null fallback、晚到头、空轨表关闭；原有选择和关闭测试保留。
- 自制样本无真实片源/账号信息，JNI影子只证明开轨调用，未证明设备像素显示。
- 仍缺用户具体字幕内容/片源和真机播放结果，不作该影片唯一根因结论。
- 忠实故障注入：恢复旧 activateTrack(sel.id) 后，两项 ExoAssMergeTest 均在等待开轨时失败；随后恢复修复。
- 最终相关 Android JVM 回归共163项，0失败、0错误、0跳过（字幕、偏好、轨道记忆、手机切核/控制器及TV轨道时序）。
- 独立只读审查未发现阻断问题；主线程核对两处生产调用和 diff --check 通过。
- `bash scripts/pack-android.sh` 成功；最终手机APK 29,681,796字节，SHA256 `fa9f76ac0005dac75c080963664b00fb38ce378a108fef4e0341f6ef64cdac9f`。
- apksigner验证v2/v3通过；13个ARM64 ELF均压缩，Manifest extractNativeLibs=true，交付副本与本次构建产物一致。
- 暂存隐私门禁通过；未启动调试服务，临时日志在记录结论后清理。
