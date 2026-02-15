# LinPlayer DandanPlay Proxy (Cloudflare Worker)

This Worker keeps `AppSecret` on server-side and signs requests for DandanPlay.

## Purpose

- Do not expose `AppSecret` inside client apps.
- Keep LinPlayer default source as official `https://api.dandanplay.net`.
- Internally reroute official requests to this Worker when user credentials are empty.

This Worker only proxies DandanPlay requests. Other danmaku servers (for example
`danmu_api` and `misaka_danmu_server`) should still be called directly by client.

## API routes

- Health check:
1. `GET /health`
2. `GET /healthz`
- Proxy routes:
1. `/api/v2/*`
2. `/t/<token>/api/v2/*` (optional token mode)

Allowed methods: `GET`, `POST`, `OPTIONS`.

## Prerequisites

1. Node.js 18+ (20+ recommended)
2. Cloudflare account
3. DandanPlay credential(s): one or multiple `AppId/AppSecret`

## Quick deploy

```bash
cd workers/dandanplay-proxy
npm install
npx wrangler login
npx wrangler secret put DANDANPLAY_APP_ID
npx wrangler secret put DANDANPLAY_APP_SECRET
# optional
npx wrangler secret put PROXY_TOKEN
npx wrangler deploy
```

After deploy, Wrangler prints your Worker URL, usually:
`https://<worker-name>.<subdomain>.workers.dev`

## Local development

```bash
cd workers/dandanplay-proxy
npm install
npx wrangler dev
```

Use local secrets with `.dev.vars` (already ignored by `.gitignore`):

```env
DANDANPLAY_APP_ID=your_app_id
DANDANPLAY_APP_SECRET=your_app_secret
# optional
PROXY_TOKEN=your_token
```

## Security options

### 1) No token mode

- Base URL: `https://<worker-domain>`
- Client calls: `https://<worker-domain>/api/v2/...`

### 2) Token mode (recommended for public workers)

Set:

```bash
npx wrangler secret put PROXY_TOKEN
```

Then use base URL:
`https://<worker-domain>/t/<PROXY_TOKEN>`

Example:
`https://<worker-domain>/t/my-secret-token/api/v2/match`

## Verify deployment

### Health check

```bash
curl https://<worker-domain>/health
```

Expected JSON:

```json
{"ok":true,"service":"dandanplay-proxy","now":"..."}
```

### Proxy check

```bash
curl -X POST "https://<worker-domain>/api/v2/match" ^
  -H "Content-Type: application/json" ^
  -d "{\"fileName\":\"demo.mkv\",\"fileHash\":null,\"fileSize\":1,\"videoDuration\":60,\"matchMode\":\"fileNameOnly\"}"
```

If token mode is enabled, use:
`https://<worker-domain>/t/<PROXY_TOKEN>/api/v2/match`

## LinPlayer integration

### A) Use Worker URL directly in settings

In LinPlayer danmaku settings:

1. API URL:
1. No token mode: `https://<worker-domain>`
1. Token mode: `https://<worker-domain>/t/<PROXY_TOKEN>`
2. Leave client `AppId`/`AppSecret` empty.

### B) Keep official URL as visible default and reroute internally

Build LinPlayer with:

```bash
flutter build <platform> --dart-define=LINPLAYER_DANDANPLAY_PROXY_URL=https://<worker-domain>
```

Behavior:

- Official source + empty credentials: routed to Worker internally
- Official source + user credentials: direct official call
- Non-official source: direct call

## Multi-secret load balancing

If you have two (or more) app secrets, Worker can balance requests and auto-fallback.

Set only:

1. Secret: `DANDANPLAY_APP_ID`
2. Secret: `DANDANPLAY_APP_SECRET` (single value or multiple values split by newline/comma)

Example `DANDANPLAY_APP_SECRET` value:

```text
secret_a
secret_b
```

or:

```text
secret_a,secret_b
```

Other credential variable names are ignored by this Worker.

### Strategy

Set Worker variable `LOAD_BALANCE_STRATEGY`:

1. `random` (default)
2. `round_robin`
3. `hash` (stable by path/query)

On upstream status `401/403/429/5xx`, Worker retries next credential automatically.

### Optional debug headers

Set variable `DEBUG_HEADERS=true` to return:

1. `X-Proxy-Credential-Index`
2. `X-Proxy-Credential-Count`

Use only for troubleshooting.

## Configuration notes

`wrangler.toml`:

- `UPSTREAM_ORIGIN` defaults to `https://api.dandanplay.net`
- `ALLOW_ORIGIN` defaults to `*`
- `LOAD_BALANCE_STRATEGY` defaults to `random`

Change CORS if needed:

```toml
[vars]
ALLOW_ORIGIN = "https://your-app-domain.example"
```

## Signature rule

Signature input used by Worker:

`AppId + UnixTimestamp(seconds) + RequestUri(path) + AppSecret`

Then:

1. SHA-256 digest
2. Base64 encode
3. Send headers `X-AppId`, `X-Timestamp`, `X-Signature`
