# 实现边界

Go 只透传服务端已有 SeasonId 为可选 season_id，不改请求参数、服务端、绑定或 has_primary。手机与TV正式浏览共用 NetImage：根据条目关系及当前代理 URL 构造候选，加载失败后推进，候选变化重置位置。桌面 Images 共用候选生成和依次加载，原缓存、代理鉴权与尺寸机制保持。

详情已有明确季上下文时补足展示关系；Android BrowseCache 和桌面 CardItem 序列化保留 season_id。已有 HasPrimary=false 不能阻断分集和季的父级回退。各端占位仍由展示控件负责。

只提交本需求文件，保留开工前已有的桌面浏览/下载/菜单与 Apple TV 研究改动。可整体回滚本需求提交，不涉及数据迁移。
