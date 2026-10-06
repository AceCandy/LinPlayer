# 设计

复用 LibraryIds 和 Views ID/name，以完整 Views 缓存替换短剧库集合。总览依库归属构建栏目，再为未匹配任何可见库的收藏保留类型栏目。FavoriteCategory 路由扩展可选 libraryId/title，库内页过滤收藏接口的原始分页结果，本地排序保持现状。栏目 key 使用库 ID，详情仍使用原 Item.type。

变更限于 ListPages、Nav、PhoneRoot、相关测试、手机设计与规范；不新增接口、依赖或全量预加载。
