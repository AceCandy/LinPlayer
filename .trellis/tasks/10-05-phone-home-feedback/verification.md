# 验证记录

## 六项落地
- MediaRowHeader恢复蓝紫竖杠；原有更多回调移到标题区域，不再显示更多文字。续播/合集没有新增未授权的更多页面。
- 悬浮栏为首页、搜索、聚合视界、收藏。搜索navigate而非switchTab，三个Tab索引和独立返回栈不变。胶囊宽240dp。
- 首页设置20dp无圈、外层44dp触控区与服务器胶囊居中对齐；顶栏搜索移除。
- 首页服务器菜单启用实色mediaPanel，其它LpMenu默认外观不变。
- 媒体库无标题及封面下库名；封面Fit；无URL、加载中与失败时居中库名，成功后隐藏。
- 续播时长10sp，紧凑mm:ss/h:mm:ss，剩余带文字提示、半透明黑底；仅续播电影/单集显示。栏目标题上下间距由20/10收紧为12/8dp。

## 验证证据
- 独立只读审查完成，主线程核对具体调用与渲染。补齐审查指出的320dp/1.3字号验证。
- PhoneHomeRefreshTest 15，FloatingTabTest 3，ResumeCardTest 7，PhoneThemeTest 8，PhoneBrowseUiTest 7，PhoneLibraryUiTest 4，共44项，0失败。
- 真实Compose截图：浅/深续播卡、菜单、Tab；320dp/1.3字号真实PhoneRoot与首页；模拟图片加载失败时库名居中、仍可进入媒体库。
- 菜单像素断言确认背景实色；标题点击进入对应库；搜索返回恢复原Tab及收藏末项滚动位置；普通卡片无时长；已有刷新/离屏加载请求数回归通过。
- 参数262处、字段202处（既有放行240处）、git diff --check通过。
- 用户纠正的首页竖杠、标题入口、库封面和搜索位置已写入Android规范与UI_MOBILE正本，避免被旧截图规范覆盖。

## 未验证及风险
- 未真机验收、未连接真实服务器播放；截图使用测试图片，不代表真实封面内容。
- 长库名缺图占位最多两行，超长仍省略。媒体封面仍Fit，不裁切图内库名。
- 设置保留原44dp触控区；图标尺寸与点击区域区分处理。
- 无调试服务启动；不提交、不推送。

- 正式包 `app-arm64-v8a-release.apk`：60.14 MiB；SHA256 `a5aa2215f36a66fd2286c93c37f5dedf2f9c3169e7c8f5b36119e403bc973441`；源/目标一致；双播放库ELF e_machine=183；整数MiB体积门禁通过。

- 正式包 `app-tv-armeabi-v7a-release.apk`：56.62 MiB；SHA256 `16650776a9a9201ec07bc7c5769fa442a871e5c7f45bcdaa0438feba2f77517f`；源/目标一致；双播放库ELF e_machine=40；整数MiB体积门禁通过。

- assembleRelease成功；双ABI正式包v2/v3验签通过。既有TV Compose映射警告不阻塞出包；临时日志已清理。
