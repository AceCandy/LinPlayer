# 实施与验证记录

本文件为完成后补录，记录最终选择及已有验证证据。

## 最终实现

- TV 详情不显示全局导航，不保留导航占位；沉浸背景铺到页面顶部和两侧。
- 标题、元信息、简介与紧凑操作行统一安全边距；续播集名、剩余时间与细进度条独立展示。
- 浅色主题的图片阅读区仍保留白字与遮罩，仅底部衔接浅色正文；参数随宽度换行。
- 按用户最后确认，移除 TV 左上角屏幕返回按钮，手机返回按钮不变。
- 首焦点等待播放操作挂载且可用；页面已有焦点时不重新抢焦点。
- 删除电影路径中无内容的季与分集懒列表项，保留实际插件注入，避免离屏焦点搜索卡住。
- 参数焦点组向上退出时先回到首屏再聚焦播放；共享焦点恢复考虑禁用到启用变化，修复播放返回慢刷新丢焦点。

## 最终验证

最终代码测试报告位于 `apps/android/app/build/test-results/testDebugUnitTest/`，本地时间 2026-10-27 23:08 完成；提交收尾时未再修改生产与测试代码。

| 测试类 | 通过数 |
| --- | ---: |
| PhoneDetailOptionsTest | 26 |
| PlaybackReturnTest | 2 |
| VisualAlignmentTest | 4 |
| SharedTvBrowseTest | 29 |
| 合计 | 61 |

- 失败、错误与跳过均为 0。覆盖手机与 TV 深浅主题、800dp / 大字号、方向键往返、选季、演员返回与播放返回焦点。
- 新增无屏幕返回按钮断言先在旧实现失败，再在修改后通过。
- 独立复核未发现可信新增问题；共享焦点调整另有独立核验。
- `git diff --check` 通过。
- `python3 scripts/check-android-args.py` 通过，检查 189 处调用。
- 工作提交 `c7b2079f` 的 8 个暂存文件通过 `bash scripts/check-secrets.sh --staged`，无新增真实地址或凭据。
- 全仓隐私扫描命中不属于本次改动的旧日志 `.trellis/workspace/AceCandy/journal-2.md:24`，未修改旧日志或放宽规则。本次新增提交内容另按暂存门禁检查，不将其通过等同于全仓扫描通过。

## 渲染产物

以下为实际 Compose 页面渲染，使用合成测试素材，不是真机截图；产物已忽略，不入仓。

- `build/visual-alignment/tv/dark-detail.png`
- `build/visual-alignment/tv/dark-detail-options-focus.png`
- `build/visual-alignment/tv/dark-series.png`
- `build/visual-alignment/tv/light-detail.png`
- `apps/android/app/build/shared-tv/detail-large-text.png`

## 未验证与后续风险

- 未打 APK，未推送远端。
- 未进行真实电视、实体遥控器与真实片源联调；设备差异、实际图片对比度和播放链路仍需实机验收。
- 不将此任务归档视为全部 TV 功能或跨端统一验收完成；其它进行中的任务不变。
