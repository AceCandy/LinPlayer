const DEFAULT_UPSTREAM_ORIGIN = 'https://api.dandanplay.net';
const RETRYABLE_UPSTREAM_STATUS = new Set([401, 403, 429, 500, 502, 503, 504]);
let roundRobinCursor = 0;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const allowOrigin = env.ALLOW_ORIGIN || '*';

    if (request.method === 'OPTIONS') {
      return withCors(new Response(null, { status: 204 }), allowOrigin);
    }

    if (url.pathname === '/health' || url.pathname === '/healthz') {
      return withCors(
        json(
          {
            ok: true,
            service: 'dandanplay-proxy',
            now: new Date().toISOString(),
          },
          200,
        ),
        allowOrigin,
      );
    }

    if (request.method !== 'GET' && request.method !== 'POST') {
      return withCors(
        json(
          { error: 'method_not_allowed', message: 'Only GET and POST are supported.' },
          405,
        ),
        allowOrigin,
      );
    }

    const credentials = readCredentials(env);
    if (credentials.length === 0) {
      return withCors(
        json(
          {
            error: 'missing_worker_secret',
            message:
              'Set DANDANPLAY_APP_ID and DANDANPLAY_APP_SECRET (one or multiple secrets split by newline/comma).',
          },
          500,
        ),
        allowOrigin,
      );
    }

    const route = parseRoute(url.pathname);
    if (!route.ok) {
      return withCors(
        json(
          {
            error: 'not_found',
            message:
              'Use /api/v2/* or /t/<token>/api/v2/*, e.g. /api/v2/match or /api/v2/comment/{episodeId}.',
          },
          404,
        ),
        allowOrigin,
      );
    }

    if (env.PROXY_TOKEN && route.token !== env.PROXY_TOKEN) {
      return withCors(
        json(
          {
            error: 'unauthorized',
            message:
              'Token required. Use base URL https://<worker>/t/<token> in LinPlayer Danmaku API settings.',
          },
          401,
        ),
        allowOrigin,
      );
    }

    const upstreamOrigin = (env.UPSTREAM_ORIGIN || DEFAULT_UPSTREAM_ORIGIN).replace(/\/+$/, '');
    const upstreamUrl = `${upstreamOrigin}${route.upstreamPath}${url.search}`;
    const method = request.method.toUpperCase();
    const requestBodyBuffer = method === 'GET' ? null : await request.arrayBuffer();
    const candidateOrder = buildCredentialOrder(credentials.length, env.LOAD_BALANCE_STRATEGY, url);

    let lastResponse = null;
    let lastError = null;
    for (let i = 0; i < candidateOrder.length; i++) {
      const credentialIndex = candidateOrder[i];
      const credential = credentials[credentialIndex];
      const headers = await buildSignedHeaders(request, route.upstreamPath, credential);
      try {
        const upstreamResp = await fetch(upstreamUrl, {
          method,
          headers,
          body: requestBodyBuffer == null ? undefined : requestBodyBuffer,
          redirect: 'follow',
        });
        lastResponse = upstreamResp;
        if (!shouldRetryWithAnotherCredential(upstreamResp.status, i, candidateOrder.length)) {
          return withCors(
            buildProxyResponse(upstreamResp, {
              credentialIndex,
              credentialCount: credentials.length,
              debugHeaders: isDebugHeadersEnabled(env),
            }),
            allowOrigin,
          );
        }
      } catch (err) {
        lastError = err;
        if (i >= candidateOrder.length - 1) {
          break;
        }
      }
    }

    if (lastResponse != null) {
      return withCors(
        buildProxyResponse(lastResponse, {
          credentialIndex: candidateOrder[candidateOrder.length - 1],
          credentialCount: credentials.length,
          debugHeaders: isDebugHeadersEnabled(env),
        }),
        allowOrigin,
      );
    }

    return withCors(
      json(
        {
          error: 'upstream_unreachable',
          message: `Failed to reach upstream: ${String(lastError)}`,
        },
        502,
      ),
      allowOrigin,
    );
  },
};

function readCredentials(env) {
  const appId = normalizeString(env.DANDANPLAY_APP_ID);
  if (!appId) return [];
  const secrets = splitSecrets(env.DANDANPLAY_APP_SECRET);
  if (secrets.length === 0) return [];
  return dedupeCredentials(secrets.map((secret) => ({ appId, appSecret: secret })));
}

function splitSecrets(rawValue) {
  const raw = normalizeString(rawValue);
  if (!raw) return [];
  return raw
    .split(/[\r\n,]+/)
    .map((it) => it.trim())
    .filter((it) => it.length > 0);
}

function dedupeCredentials(list) {
  const seen = new Set();
  const out = [];
  for (const item of list) {
    const appId = normalizeString(item?.appId);
    const appSecret = normalizeString(item?.appSecret);
    if (!appId || !appSecret) continue;
    const key = `${appId}\u0000${appSecret}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ appId, appSecret });
  }
  return out;
}

function normalizeString(value) {
  const v = (value ?? '').toString().trim();
  return v.length > 0 ? v : '';
}

function buildCredentialOrder(count, strategyRaw, url) {
  const countSafe = Math.max(0, count | 0);
  if (countSafe <= 1) return countSafe === 1 ? [0] : [];
  const strategy = normalizeString(strategyRaw).toLowerCase() || 'random';

  let firstIndex = 0;
  if (strategy === 'round_robin') {
    firstIndex = roundRobinCursor % countSafe;
    roundRobinCursor = (roundRobinCursor + 1) % countSafe;
  } else if (strategy === 'hash') {
    const hashInput = `${url.pathname}${url.search}`;
    firstIndex = positiveHash(hashInput) % countSafe;
  } else {
    firstIndex = randomInt(countSafe);
  }

  const order = [firstIndex];
  for (let i = 0; i < countSafe; i++) {
    if (i !== firstIndex) order.push(i);
  }
  return order;
}

function positiveHash(input) {
  let h = 2166136261;
  for (let i = 0; i < input.length; i++) {
    h ^= input.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0) & 0x7fffffff;
}

function randomInt(maxExclusive) {
  const arr = new Uint32Array(1);
  crypto.getRandomValues(arr);
  return arr[0] % maxExclusive;
}

async function buildSignedHeaders(request, requestPath, credential) {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const raw = `${credential.appId}${timestamp}${requestPath}${credential.appSecret}`;
  const signature = await sha256Base64(raw);

  const headers = new Headers();
  const passThroughHeaders = ['accept', 'accept-language', 'content-type', 'user-agent'];
  for (const key of passThroughHeaders) {
    const value = request.headers.get(key);
    if (value) headers.set(key, value);
  }
  headers.set('X-AppId', credential.appId);
  headers.set('X-Timestamp', timestamp);
  headers.set('X-Signature', signature);
  return headers;
}

function shouldRetryWithAnotherCredential(status, attemptIndex, totalAttempts) {
  if (attemptIndex >= totalAttempts - 1) return false;
  return RETRYABLE_UPSTREAM_STATUS.has(status);
}

function buildProxyResponse(response, { credentialIndex, credentialCount, debugHeaders }) {
  const outHeaders = new Headers(response.headers);
  outHeaders.set('X-Proxy-Upstream', 'dandanplay');
  if (debugHeaders) {
    outHeaders.set('X-Proxy-Credential-Index', String(credentialIndex));
    outHeaders.set('X-Proxy-Credential-Count', String(credentialCount));
  }
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers: outHeaders,
  });
}

function isDebugHeadersEnabled(env) {
  const v = normalizeString(env.DEBUG_HEADERS).toLowerCase();
  return v === '1' || v === 'true' || v === 'yes';
}

function parseRoute(pathname) {
  if (pathname.startsWith('/api/v2/')) {
    return { ok: true, token: '', upstreamPath: pathname };
  }
  if (pathname === '/api/v2') {
    return { ok: true, token: '', upstreamPath: pathname };
  }

  const m = pathname.match(/^\/t\/([^/]+)(\/api\/v2(?:\/.*)?)$/);
  if (m) {
    return {
      ok: true,
      token: decodeURIComponent(m[1]),
      upstreamPath: m[2],
    };
  }

  return { ok: false };
}

async function sha256Base64(input) {
  const bytes = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  const arr = new Uint8Array(digest);
  let binary = '';
  for (let i = 0; i < arr.length; i++) {
    binary += String.fromCharCode(arr[i]);
  }
  return btoa(binary);
}

function withCors(response, allowOrigin) {
  const headers = new Headers(response.headers);
  headers.set('Access-Control-Allow-Origin', allowOrigin);
  headers.set('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  headers.set(
    'Access-Control-Allow-Headers',
    'Content-Type, Accept, Accept-Language, User-Agent, X-Requested-With',
  );
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

function json(payload, status) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
    },
  });
}
