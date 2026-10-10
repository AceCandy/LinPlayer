# 设计

## 现状与边界
PlayerPage SeekBy使用旧_position；_seekTarget失败不清/无超时，Poll的提前return也冻结后续暂停/弹幕等同步。PlayerBar已有拖动保护，保持其实现。核心player.seek为absolute+exact，回执不是呈现完成，不新增核心协议。

## 拥有者
新增仅供本播放页使用的PlaybackSeek状态对象，集中目标/代数/提交标记/单调时间与SemaphoreSlim提交门；通过现有PlayerSeek回调发送绝对pos。不是新引擎接口。相对输入以Target或真实position累加，绝对输入钳已知时长。请求在第一次await前写目标，等待锁后复查代数。失败仅清自己的当前目标。
Observe仅在已提交、非缓冲且实际位置距目标<1.5秒时清目标；单调时钟15秒释放显示目标。页面250ms Tick先检查超时，保证查询失败也释放；超时不会提前放开不可撤回命令的提交门。

## 页面接线
_position始终保存真实位置，_bar和时间文字使用Target??_position；立即显示目标，失败与超时恢复实际值。Poll用忙标记避免重叠，抓seek代数，响应后复查Stopped并仅用同代数采样确认目标，正常同步其余UI。停止只执行一次，清目标/禁止输入，等seek提交和在途起播后再发已有stopPlayback；静态pendingStop让下一页Start等待。GL迟到ready不能在已停止页起播。

## 验证与风险
复用tools下可执行断言和Avalonia.Headless模式，新seekcheck直接测生产状态与真实页面按钮/键盘/进度条接线；无新增框架依赖。增加测试程序集访问权限用于生产状态测试，不新增运行期控制入口。
不把显示位置写入业务进度。原生回执永久缺失时停止/新起播继续等待，不能超时强行让旧命令落到新媒体。位置容差不证明帧呈现；真实显卡与Windows环境另验。无持久化改动，回滚本批源码和测试即可。
