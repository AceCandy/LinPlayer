# 验证记录

- 回归 `Test收藏类型由服务端返回` 在修复前分页和全量入口均失败（返回空）；修复后通过。
- `go test ./emby -count=1` 与 `bash scripts/check-core.sh` 全部通过。
- Android 参数检查 265 处通过；隐私扫描与 diff 空白检查通过。
- 独立只读审查确认两入口不固定类型，收藏范围、排序和分页保留。审查提出规范路径疑问，主线程核实 `.trellis/spec/shared/product-and-api.md` 的契约 diff 已存在，未暂存。
- 手机 arm64 release 包重建，apksigner 验签通过，最终交付包与 Gradle 输出 cmp 一致。
- 未验证真实服务端收藏数据、真实请求或真机展示；未重建 TV / 桌面包。未修改服务端、未启动调试服务、未提交。
