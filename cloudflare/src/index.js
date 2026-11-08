// V-Client API as a Cloudflare Worker + D1. Endpoints match what the launcher and the website call.
// Privacy: no IP addresses, user agents or player names are stored. See the privacy note on the website.

const EVENTS = new Set(['launch', 'play', 'install', 'update', 'join']);
const PLATFORMS = new Set(['windows', 'android']);
const LEVELS = new Set(['info', 'success', 'warning', 'danger']);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const json = (data, status = 200, headers = {}) => new Response(JSON.stringify(data), {
  status,
  headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers },
});

const today = () => new Date().toISOString().slice(0, 10);

function corsHeaders(request, env) {
  const origin = request.headers.get('Origin');
  if (origin && origin === env.ALLOWED_ORIGIN) {
    return {
      'Access-Control-Allow-Origin': origin,
      'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-API-Key',
      'Access-Control-Max-Age': '86400',
      Vary: 'Origin',
    };
  }
  return {};
}

/** Constant-time comparison so the key cannot be guessed from response timing. */
function sameSecret(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length || a.length === 0) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function authorized(request, env) {
  const header = request.headers.get('Authorization') || '';
  const supplied = header.startsWith('Bearer ') ? header.slice(7) : request.headers.get('X-API-Key') || '';
  return sameSecret(supplied, env.STATS_API_KEY || '');
}

async function readJson(request) {
  try {
    const text = await request.text();
    if (text.length > 4096) return null;
    return JSON.parse(text);
  } catch {
    return null;
  }
}

async function sha256Hex(text) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('').slice(0, 32);
}

async function serverStatus(env, ctx) {
  const host = env.JAVA_HOST;
  const port = Number(env.JAVA_PORT) || 25565;
  const cache = caches.default;
  const cacheKey = new Request(`https://status.cache.invalid/${host}:${port}`);
  const hit = await cache.match(cacheKey);
  if (hit) return hit.json();
  const started = Date.now();
  const response = await fetch(`https://api.mcstatus.io/v2/status/java/${encodeURIComponent(host)}:${port}`, {
    headers: { 'User-Agent': 'V-Client-API' },
    signal: AbortSignal.timeout(5000),
  });
  if (!response.ok) throw new Error(`status lookup returned ${response.status}`);
  const data = await response.json();
  const status = {
    java: data.online
      ? {
        online: true,
        host,
        port,
        version: data.version?.name_clean || '',
        players: { online: data.players?.online ?? 0, max: data.players?.max ?? 0 },
        latencyMs: Date.now() - started,
      }
      : { online: false },
    bedrock: { online: false },
  };
  ctx.waitUntil(cache.put(cacheKey, new Response(JSON.stringify(status), {
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'max-age=20' },
  })));
  return status;
}

async function statsSummary(env) {
  const total = await env.DB.prepare("SELECT COUNT(*) AS n FROM events WHERE event = 'launch'").first();
  const installs = await env.DB.prepare("SELECT COUNT(DISTINCT install_id) AS n FROM events WHERE event = 'launch'").first();
  const since = new Date(Date.now() - 29 * 86400000).toISOString().slice(0, 10);
  const daily = await env.DB.prepare(
    "SELECT day, COUNT(DISTINCT install_id) AS users FROM events WHERE event = 'launch' AND day >= ? GROUP BY day ORDER BY day",
  ).bind(since).all();
  const perDay = new Map(daily.results.map((row) => [row.day, row.users]));
  const dailyActiveUsers = [];
  for (let i = 29; i >= 0; i -= 1) {
    const day = new Date(Date.now() - i * 86400000).toISOString().slice(0, 10);
    dailyActiveUsers.push({ day, users: perDay.get(day) || 0 });
  }
  const versions = await env.DB.prepare(
    "SELECT version, COUNT(*) AS launches FROM events WHERE event = 'launch' GROUP BY version ORDER BY launches DESC LIMIT 50",
  ).all();
  return {
    totalLaunches: total?.n || 0,
    uniqueInstalls: installs?.n || 0,
    dailyActiveUsers,
    launchesPerVersion: versions.results,
  };
}

async function route(request, env, ctx) {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, '') || '/';
  const method = request.method;

  if (method === 'GET' && path === '/api/health') return json({ ok: true, time: new Date().toISOString() });

  if (method === 'GET' && path === '/api/config') {
    return json({
      server: {
        name: env.SERVER_NAME,
        java: { host: env.JAVA_HOST, port: Number(env.JAVA_PORT) },
        bedrock: { host: '', port: 0 },
        discordUrl: env.DISCORD_URL,
      },
    }, 200, { 'Cache-Control': 'public, max-age=300' });
  }

  if (method === 'GET' && path === '/api/server/status') {
    try {
      return json(await serverStatus(env, ctx), 200, { 'Cache-Control': 'public, max-age=15' });
    } catch {
      // The launcher and the site keep showing their last known status when this fails.
      return json({ error: 'Server status is temporarily unavailable' }, 502);
    }
  }

  if (method === 'GET' && path === '/api/announcements') {
    const rows = await env.DB.prepare(
      'SELECT id, message, level, created_at FROM announcements WHERE active = 1 ORDER BY created_at DESC LIMIT 10',
    ).all();
    return json({
      items: rows.results.map((r) => ({
        id: r.id, message: r.message, level: r.level, createdAt: new Date(r.created_at * 1000).toISOString(),
      })),
    }, 200, { 'Cache-Control': 'public, max-age=30' });
  }

  // The launcher's news page and update check fall back gracefully; updates are read from the website's update.json.
  if (method === 'GET' && path === '/api/news') return json({ items: [] });
  if (method === 'GET' && path === '/api/launcher/update') {
    return json({ updateAvailable: false, mandatory: false, current: String(url.searchParams.get('current') || ''), latest: null });
  }

  if (method === 'POST' && path === '/api/stats/event') {
    const body = await readJson(request);
    const installId = body && typeof body.installId === 'string' ? body.installId : '';
    const event = body && typeof body.event === 'string' ? body.event : '';
    if (!UUID.test(installId) || !EVENTS.has(event)) return json({ error: 'Invalid event' }, 400);
    const platform = PLATFORMS.has(body.platform) ? body.platform : 'windows';
    const version = String(body.version || '').replace(/[^0-9A-Za-z.\-+]/g, '').slice(0, 24) || 'unknown';
    await env.DB.prepare('INSERT OR IGNORE INTO events (install_id, day, event, platform, version) VALUES (?, ?, ?, ?, ?)')
      .bind(installId.toLowerCase(), today(), event, platform, version).run();
    return json({ ok: true }, 202);
  }

  // Counts distinct players without keeping any name: only a one-way hash of the UUID is stored.
  if (method === 'POST' && path === '/api/players/seen') {
    const body = await readJson(request);
    const id = body && typeof body.uuid === 'string' ? body.uuid.replace(/-/g, '').toLowerCase() : '';
    if (!/^[0-9a-f]{32}$/.test(id)) return json({ error: 'Invalid player' }, 400);
    const hash = await sha256Hex(id);
    await env.DB.prepare(
      `INSERT INTO players_seen (hash, first_day, last_day) VALUES (?, ?, ?)
       ON CONFLICT(hash) DO UPDATE SET last_day = excluded.last_day`,
    ).bind(hash, today(), today()).run();
    return json({ ok: true }, 202);
  }
  if (method === 'GET' && path === '/api/players/seen') {
    const row = await env.DB.prepare('SELECT COUNT(*) AS n FROM players_seen').first();
    return json({ uniquePlayers: row?.n || 0 }, 200, { 'Cache-Control': 'public, max-age=60' });
  }

  if (path === '/api/stats/summary' || path.startsWith('/api/admin/')) {
    if (!authorized(request, env)) return json({ error: 'Unauthorized' }, 401);
    if (method === 'GET' && path === '/api/stats/summary') return json(await statsSummary(env));

    if (method === 'POST' && path === '/api/admin/announcements') {
      const body = await readJson(request);
      const message = body && typeof body.message === 'string' ? body.message.trim().slice(0, 500) : '';
      if (!message) return json({ error: 'message is required' }, 400);
      const level = LEVELS.has(body.level) ? body.level : 'info';
      const result = await env.DB.prepare('INSERT INTO announcements (message, level, active, created_at) VALUES (?, ?, 1, ?)')
        .bind(message, level, Math.floor(Date.now() / 1000)).run();
      return json({ ok: true, id: result.meta.last_row_id }, 201);
    }
    const match = path.match(/^\/api\/admin\/announcements\/(\d+)$/);
    if (method === 'DELETE' && match) {
      await env.DB.prepare('UPDATE announcements SET active = 0 WHERE id = ?').bind(Number(match[1])).run();
      return json({ ok: true });
    }
  }

  return json({ error: 'Not found' }, 404);
}

export default {
  async fetch(request, env, ctx) {
    const cors = corsHeaders(request, env);
    const origin = request.headers.get('Origin');
    // Browsers on any other site are refused; the launcher (no Origin header) is allowed.
    if (origin && !cors['Access-Control-Allow-Origin']) return json({ error: 'Origin not allowed' }, 403);
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });
    let response;
    try {
      response = await route(request, env, ctx);
    } catch {
      response = json({ error: 'Internal error' }, 500);
    }
    const out = new Response(response.body, response);
    for (const [key, value] of Object.entries(cors)) out.headers.set(key, value);
    return out;
  },
};
