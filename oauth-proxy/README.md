# LinPlayer OAuth 代理（Cloudflare Pages）

应用的**服务端小工具集**:问题反馈 / 崩溃报告转发、Sentry 推送、爱发电赞助者名单、
README 徽章等。需要保密的值(Telegram bot token 等)只放在服务端环境变量里,不进客户端。

> **为什么不能用 GitHub Pages？**
> GitHub Pages 只能托管**静态文件**，不能跑服务端代码、也不能安全保存 secret。
> Cloudflare Pages 自带 **Pages Functions**（Serverless），可以跑代码、用加密环境变量存 secret。

## 它做了什么

| 路由 | 方法 | 作用 |
|---|---|---|
| `/api/report` | POST | 应用内问题反馈 / 崩溃报告 → Telegram |
| `/api/afdian/verify` | POST | 爱发电订单校验 |
| `/sentry/hook` | POST | Sentry issue 推送 → Telegram |
| `/sentry/users` | GET | 活跃用户徽章 |
| `/afdian/sponsors.svg` | GET | 爱发电赞助者名单 |
| `/gh/:metric`、`/star/history.svg` | GET | README 徽章 |

## 目录结构

```
oauth-proxy/
  functions/
    _middleware.js              # CORS + 可选共享密钥校验
    api/report.js               # 反馈 / 崩溃报告
    api/afdian/verify.js
    sentry/  afdian/  gh/  star/
  public/
    index.html                  # 落地页
  .dev.vars.example             # 本地调试环境变量样例
```

---

## 一、部署到 Cloudflare Pages（推荐：连 Git 仓库）

1. 登录 Cloudflare 控制台 → **Workers & Pages** → **Create** → **Pages** → **Connect to Git**。
2. 选中本仓库，授权。
3. 构建配置：
   - **Production branch**：`main`
   - **Framework preset**：`None`
   - **Build command**：留空
   - **Build output directory**：`public`
   - **Root directory (Advanced)**：`oauth-proxy`  ← 关键，让它找到 `oauth-proxy/functions`
4. **Environment variables**（Settings → Environment variables，建议设为 *Encrypted*）：
   | 变量名 | 值 |
   |---|---|
   | `LINPLAYER_PROXY_KEY` | （可选）自定义共享密钥，挡脚本刷接口 |
   | `TG_BOT_TOKEN` | （崩溃报告用）@BotFather 给的 bot token。**只能放这里**，不能进客户端 |
   | `TG_CHAT_ID` | （崩溃报告用）收报告的会话 id：你和 bot 的私聊，或一个群 |
   | `SENTRY_CLIENT_SECRET` | （Sentry 转 TG 用）Internal Integration 的 Client Secret，校验推送签名 |
   | `SENTRY_HOOK_KEY` | （可选，Legacy WebHooks 才用）一串长随机数，填进 webhook 地址的 `?key=` |
5. 保存并 **Deploy**。完成后得到地址，例如 `https://linplayer-oauth.pages.dev`。

> 改了环境变量后需要 **Retry deployment / 重新部署** 才生效。

### 崩溃报告 → Telegram

- **应用里发的**(崩溃后下次启动自动发、出错横条上点「反馈」、设置里「发送日志给开发者」):
  走 `POST /api/report`,客户端核心层先脱敏再发,这里转成一条消息 + 一个 `report.txt` 附件。
  同一 IP 一分钟一条。
- **Sentry 收到的崩溃**(Internal Integration + 订阅 issue 推送,**不走告警规则**):
  1. Settings → Developer Settings → Custom Integrations → Create New Integration → **Internal Integration**
  2. Webhook URL 填 `https://<你的项目>.pages.dev/sentry/hook`;Permissions 里 **Issue & Event = Read**;
     Webhooks 勾 **issue**;保存
  3. 把它的 **Client Secret** 配成 CF 环境变量 `SENTRY_CLIENT_SECRET`(校验 `Sentry-Hook-Signature`),重新部署
  4. 验证:`LP_SELFCHECK_CRASH=1` 起一次 CI 出的包,TG 应收到「🔥 Sentry 新崩溃」和「🧨 崩溃报告」两条
  - ☠ 2026-09-18 实测:新版 Monitors 界面里告警规则选这个集成做动作,「Send Test Notification」显示成功,
    但集成的 Request Log 一条都没有 —— 根本没推过来。issue 推送这条路一次就通。
  - ☠ CF 里改了环境变量后,要对**最新那条**部署 Retry,或推一个新提交。对旧部署 Retry 会跑旧代码。

### 用 Wrangler CLI 部署（可选）

```bash
cd oauth-proxy
npm i -g wrangler
wrangler pages deploy public --project-name linplayer-oauth
# 然后在控制台为该项目补上环境变量（同上）
```

---

## 二、让 App 走代理

代理地址与共享密钥**不写进源码**,走编译期注入(全局红线:线路/中转地址和密钥
都不许进提交)。构建核心层前把这两个环境变量准备好:

| 环境变量 | 填什么 | 对应代理这边的 |
|---|---|---|
| `LP_SYNC_PROXY_BASE` | `https://<你的项目>.pages.dev/api` ← **必须带 `/api` 后缀** | 部署地址 |
| `LP_SYNC_PROXY_KEY` | 和代理的 `LINPLAYER_PROXY_KEY` **一模一样**;代理没设就留空 | `LINPLAYER_PROXY_KEY` |

```bash
export LP_SYNC_PROXY_BASE="https://<你的项目>.pages.dev/api"
export LP_SYNC_PROXY_KEY="<和 LINPLAYER_PROXY_KEY 相同>"
bash scripts/build-core.sh
```

GitHub Actions 里两个都放 **Secrets**(不是 Variables —— Variables 在日志里是明文)。
全表见 [`docs/go-migration/BUILD-SECRETS.md`](../docs/go-migration/BUILD-SECRETS.md)。

> ⚠️ **老的 Rust 版把共享密钥明文写在 `crates/core/src/sync/mod.rs` 里,已经进了
> git 历史。** 换语言不会让它消失 —— 那把 key 要轮换,并按红线改写历史。

## 三、本地调试

```bash
cd oauth-proxy
cp .dev.vars.example .dev.vars   # 填入真实值
npx wrangler pages dev public
```

---

## 四、防滥用（建议）

- 设置 `LINPLAYER_PROXY_KEY`，让随手刷接口的脚本被 401 挡掉
  （注意：该密钥也随 App 分发、可被提取，只是再加一道门槛，并非强鉴权）。
- Cloudflare 控制台为该 Pages 项目开启 **Rate Limiting / WAF 规则**（按 IP 限速）。
- 代理是**唯一持有 secret 的地方**：一旦发现滥用，直接在 Cloudflare 改/停项目或轮换
  secret 即可，无需发版客户端。
