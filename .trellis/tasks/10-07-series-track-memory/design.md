# 本次连续播放轨道记忆

## 状态与生命周期

SessionTrackMemory由页面remember持有，只保存当前服务器/账号/剧集的一组手选（audio、sub），离页释放。scope使用三字段数据类而非拼接字符串；来自当前AppState.session与Episode详情series_id。不同scope或未知scope清空；新controller绑定自己的scope后才允许恢复。复用现有详情请求，取消后复查，不为记忆增加起播屏障或网络请求。

Controller登记明确成功手选：MPV利用面板/既有轮询给出的当前运行期轨表按ID采样真实身份，命令回执成功且同代数后记录；Media3从用户点击的Format采样而非等待selected回调。关闭字幕单独保存off。默认/详情初选/换核恢复不记录，失败不覆盖旧记录。若用户选中无法辨识的轨，清理该类旧记忆。

## 恢复

同剧新控制器等待起播成功和scope确认，700ms有限重试，沿现有提交屏障执行。MPV复用现有身份matcher；Media3候选从currentTracks的支持轨道生成，复用严格身份匹配约束（有效label/language、known forced/bitmap一致、唯一），不复用ID/ordinal。title保留严格匹配，跨集标题变化则不恢复；本批不引入宽松偏好评分。

用户当前手选优先，TV详情显式audioIndex/subIndex/subOff优先；fallback同片精确快照恢复优先，本次记忆不覆盖。已恢复类只提交一次；晚到或失败可重试，但手选立即失效该类恢复。subtitle off通过现有exoPick或MPV setTrack空ID；页面subOff显示同步。新媒体先回到全局字幕开关，避免同页换剧继承旧off。

## 最小修改边界

新增SessionTrackMemory.kt（作用域与临时选择）、测试；PlayerController接成功手选及共享语义候选恢复；PlayerPanel传已读取MPV轨表、Media3手选入口；PlayerPage与TV页面持有memory、复用详情与轮询；TvPlayerParts转发Media3手选与轨表。ExoEngine仅沿用已有默认机制，不改缓冲或默认选轨规则。Go核心、服务端、配置、导航字段、桌面本批不变。

回滚移除页面memory接入与新增恢复方法，无磁盘迁移。风险：严格标题或缺语义会放弃自动恢复，默认选择及手选仍可用；外挂ASS独立加载不具备可选轨身份时不作为可恢复轨。设备字幕实际显示、TV真机不由JVM替身证明。

## 独立复核收敛

首轮无阻断。第二轮提出“取消挂起详情会跳过clearSeriesContext”的风险；主线程点验源码：clear在请求前同步执行，没有前置挂起，取消发生在请求中不会撤销已完成解绑，因此该归因不成立。补两次账号切换+取消旧详情+旧选轨回执晚到的忠实场景回归，检查新记忆为空、页面手选off意图为空；最终结果写verification。另补manualSubtitleOff状态同步与详情id核验，防止off后用户重新打开字幕仍被页面呈现抑制。
