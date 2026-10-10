# 设计

## 边界
现有PlayerController.seek按revision合并/串行请求并在回执后seekSubmitted=true；observePosition在非buffering且距目标<=1秒时清UI pending。已有完成标准保留。新增SeekTiming作为手机页面拥有的可选诊断对象，TV不登记；只随现有事件记录，不控制播放。

## 数据流
手机ObserveSeekTiming(controller)登记可选controller.seekTiming与生命周期观察，页面scope IO写既有Logs；每250ms只检查诊断15秒超时，不查询核心。seek有效登记、提交、失败和失效在控制器共用入口透传revision。observePosition增加可选paused/source信息，页面和通知复用已经取得的状态，不增加查询；TV旧调用保持默认参数。

测量自有效seek登记起算，stage=request/submitted/target_observed/clock_advanced。位置阶段沿用已提交、非buffering、<=1秒容差；其后非暂停非缓冲的两个有效样本累计实际时钟前进至少250ms才记录clock_advanced。该值包括采样等待和推进阈值（页面250ms、服务500ms），不等于seek完成或真实帧呈现。日志携带sample_source白名单，避免误认为设备渲染精确耗时。暂停到目标时停止该测量，不记恢复；暂停/重新buffering重置推进基线。EOF附近可能无250ms推进，超时表示未观察到该条件，不判故障。

独立诊断状态在UI pending清除后仍可等待clock推进；其15秒诊断超时不取消已发命令、不放锁。新请求以superseded终止旧测量；begin/stop/离页/后台终止。旧revision的回执/失败不能影响新请求。时钟使用elapsedRealtime，与控制器原nanoTime显示超时互不混算。

## 文件及回滚
新增SeekTiming.kt/测试；PlayerController.kt共用事件接线、PlayerPage.kt登记及状态透传、PlaybackService.kt状态透传，现有回归新增日志断言。无核心/FFI/设置变化；删除这些接线即可回滚。保留前批未提交改动。
