# 用 Cloudflare Pages 部署本 Wiki（linplayer.902541.xyz）

本 Wiki 使用 VitePress 构建，输出为静态站点，推荐用 Cloudflare Pages 托管。

## 1) 本地预览

在仓库根目录执行：

```bash
cd docs
npm install
npm run dev
```

## 2) 创建 Cloudflare Pages 项目

在 Cloudflare Dashboard：

1. 打开 `Workers & Pages` → `Pages`
2. `Create a project` → 连接 GitHub 仓库 `zzzwannasleep/LinPlayer`
3. 构建配置（重点）：
   - Root directory：`docs`
   - Build command：`npm run build`
   - Build output directory：`.vitepress/dist`
4. 保存并首次构建

> 建议在 Pages 项目的 `Settings → Environment variables` 里设置 `NODE_VERSION=20`（或更高）。

## 3) 绑定自定义域名（你还没加 DNS 记录也没关系）

你要用的域名：`linplayer.902541.xyz`（已在 Cloudflare 托管）

1. 进入 Pages 项目 → `Custom domains`
2. 点击 `Set up a custom domain`，填写 `linplayer.902541.xyz`
3. 如果该域名在同一个 Cloudflare 账号/Zone 下，Cloudflare 会自动为你创建所需的 DNS 记录（通常是 CNAME）

如果需要手动加记录（少见情况）：
- 记录类型：CNAME
- 名称：`linplayer`
- 目标：你的 Pages 默认域名（形如 `xxx.pages.dev`）
- 代理：开启（橙云）

## 4) 常见坑

- 页面资源 404：确认 Pages 的 Root directory 是 `docs`，输出目录是 `.vitepress/dist`
- 构建失败：确认 Node 版本 ≥ 18（建议 20）

