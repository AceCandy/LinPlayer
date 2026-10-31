# 设计

复用 player.getSkipRange/setSkipRange：无 scope 维持旧契约；新调用 scope=season/episode，action=intro_end/outro_start/intro_start/outro_end/clear，position/runtime_secs 表示当前位置。核心负责片段合并和校验。

新键采用 JSON 编码服务器+scope+季或分集身份。季优先 SeasonId，其次 SeriesId+季号（含0）；缺季身份拒绝季级写入。读取按旧整剧、本季、本集逐段覆盖，清除仅删所选范围。

片头存绝对起止；片尾延用 outro_len 表示距结尾开始距离，新增 outro_end_offset 表示距结尾结束距离。补标要求已有完整默认区间，倒序/越界/非有限数拒绝。旧数据不迁移或删除。

get 返回 scope/can_season、所选 range、对应 intro/outro（不含更高范围）和 legacy 标识；set 同形状。复制 map 后保存，失败回滚。

宿主成功刷新 chapterInfo，当前区间只显示手动提示、本次不自动跳过；下集沿用开关。手机/TV片尾跳过与下一集共用右下提示，保留后续内容时屏蔽旧末30秒下一集提示。
