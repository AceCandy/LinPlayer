# 执行

1. 添加本机源首页、目录返回/过滤、换版本字幕的实际组件回归，记录修复前失败。
2. 按行为归属做最小修复，执行目标测试及手机/TV相关回归。
3. 独立只读复核，修复必要问题，更新规范记录四条契约。
4. 参数、空白与隐私门禁后逐路径提交，归档并记会话。真机未验，不打包。

## 实施结果

- 四条故障均有修复前失败证据：本机首页无入口、说明.txt未过滤、子目录返回后电影.mkv不再显示、换版本后字幕调用只有1/2而缺新轨9。
- 目标13项回归通过；增加Emby→本机→Emby状态切换，确认localSource按核心source_kind变化且插件activeSource不受替代。
- 两个独立只读复核完成。浏览修复未发现可触发问题；字幕审查提出的“关闭只作用当前集”不符合既有同剧手选记忆契约，未按假定扩改。主线程核验新版本确实清旧预选，恢复受同剧/账号及轨道身份限制。

## 验证与交付

- 112项相关Robolectric测试通过：SharedTvBrowseTest 8、TvTrackTimingTest 5、TvPrivacyTest 3、TvFocusTest 36、FloatingTabTest 9、PhoneManagementUiTest 7、PlayerActivityTest 7、PlayerControllerTest 36、PlayerLaunchPageTest 1。
- 另用遥控器下键聚焦本机首页按钮、确认进入文件页的路径通过；人工查看TV本机首页及文件页实际组件渲染。截图使用测试夹具，不代表真实媒体。
- Android参数检查259处通过，diff空白检查通过；提交前按暂存区运行隐私门禁。
- 未连接真机，实际视频、字幕与遥控器设备兼容性未验；未生成APK。Windows及AppleTV既有工作树不纳入本次提交归档。
