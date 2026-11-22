'use strict';
const fs = require('node:fs');
const express = require('express');
const { openDatabase } = require('./db');
const { hashPassword, loadSecret, createTokens, rateLimit } = require('./security');
const { createStatusService } = require('./serverStatus');
const publicRoutes = require('./routes/public');
const authRoutes = require('./routes/auth');
const adminRoutes = require('./routes/admin');
const cosmeticsRoutes = require('./routes/cosmetics');
const friendsRoutes = require('./routes/friends');

/** Builds the Express app. Everything stateful (db, config) is injected so tests can run in memory. */
function createApp(config, options = {}) {
  const db = options.db || openDatabase(options.inMemory ? ':memory:' : config.dataDir);
  fs.mkdirSync(config.downloadsDir, { recursive: true });
  fs.mkdirSync(config.cosmeticsDir, { recursive: true });
  const tokens = createTokens(loadSecret(config));
  const status = options.statusService || createStatusService(config.server);
  const ctx = { config, db, tokens, status, now: () => Math.floor(Date.now() / 1000) };

  ensureAdmin(ctx, options.log || console);

  const app = express();
  app.disable('x-powered-by');
  if (config.trustProxy) app.set('trust proxy', 1);

  app.use((req, res, next) => {
    res.set({
      'X-Content-Type-Options': 'nosniff',
      'X-Frame-Options': 'DENY',
      'Referrer-Policy': 'strict-origin-when-cross-origin',
      'Content-Security-Policy': "default-src 'self'; img-src 'self' data: https://mc-heads.net https://crafatar.com; style-src 'self' 'unsafe-inline'; script-src 'self'; frame-ancestors 'none'",
    });
    next();
  });

  // The static website (a different origin) calls this API, e.g. the grant tool. Tokens travel in a header, not cookies.
  const allowedOrigins = (process.env.CORS_ORIGINS || 'https://vsmp-client.onrender.com').split(',').map((o) => o.trim());
  app.use('/api', (req, res, next) => {
    const origin = req.headers.origin;
    if (origin && allowedOrigins.includes(origin)) {
      res.set({
        'Access-Control-Allow-Origin': origin,
        'Access-Control-Allow-Headers': 'Content-Type, Authorization',
        'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
        Vary: 'Origin',
      });
    }
    return req.method === 'OPTIONS' ? res.sendStatus(204) : next();
  });

  // The installer upload route needs a raw body, so JSON parsing is skipped for it.
  app.use((req, res, next) => (req.path.startsWith('/api/admin/releases/') && req.method === 'PUT'
    ? next()
    : express.json({ limit: '64kb' })(req, res, next)));

  app.use('/api', rateLimit({ windowMs: 60_000, max: 240, bucket: 'api' }), publicRoutes(ctx));
  app.use('/api/cosmetics', cosmeticsRoutes(ctx));
  app.use('/api/friends', friendsRoutes(ctx));
  app.use('/api/presence', friendsRoutes.presence(ctx));
  app.use('/api/auth', authRoutes(ctx));
  app.use('/api/admin/cosmetics', cosmeticsRoutes.admin(ctx));
  app.use('/api/admin', adminRoutes(ctx));
  app.use('/api', (req, res) => res.status(404).json({ error: 'Not found' }));

  // Website and installers.
  app.use('/downloads', publicRoutes.downloads(ctx));
  app.get('/download/:platform', publicRoutes.latestDownload(ctx));
  app.use(express.static(config.websiteDir, { extensions: ['html'], index: 'index.html', maxAge: '5m' }));

  // eslint-disable-next-line no-unused-vars
  app.use((error, req, res, next) => {
    if (error.type === 'entity.parse.failed') return res.status(400).json({ error: 'Invalid JSON body' });
    if (error.type === 'entity.too.large') return res.status(413).json({ error: 'Request body too large' });
    (options.log || console).error(error);
    return res.status(500).json({ error: 'Internal server error' });
  });

  app.locals.ctx = ctx;
  return app;
}

function ensureAdmin(ctx, log) {
  const existing = ctx.db.prepare("SELECT id FROM users WHERE role = 'admin' LIMIT 1").get();
  if (existing) return;
  const generated = !ctx.config.admin.password;
  const password = ctx.config.admin.password || require('node:crypto').randomBytes(12).toString('base64url');
  ctx.db.prepare(
    "INSERT INTO users (username, email, password_hash, role, created_at) VALUES (?, ?, ?, 'admin', ?)",
  ).run(ctx.config.admin.username, ctx.config.admin.email, hashPassword(password), ctx.now());
  if (generated) {
    log.warn(`[VYRN] Created admin account "${ctx.config.admin.username}" with password: ${password}`);
    log.warn('[VYRN] Change it, or set ADMIN_PASSWORD before the first start. This is shown only once.');
  } else {
    log.info(`[VYRN] Created admin account "${ctx.config.admin.username}".`);
  }
}

module.exports = { createApp };
