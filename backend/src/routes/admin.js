'use strict';
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { pipeline } = require('node:stream/promises');
const express = require('express');
const { requireAdmin } = require('./auth');
const { releaseView, newsView } = require('./public');
const { compareVersions } = require('../security');

const VERSION = /^\d+(\.\d+){0,3}$/;
const EXTENSIONS = { windows: ['.exe', '.msi', '.zip'], android: ['.apk'] };

const text = (value, max) => (typeof value === 'string' && value.trim() && value.length <= max ? value.trim() : null);
const toEpoch = (value) => {
  if (value === null || value === undefined || value === '') return null;
  const ms = Date.parse(value);
  return Number.isNaN(ms) ? undefined : Math.floor(ms / 1000);
};

function adminRoutes(ctx) {
  const { db, config, now } = ctx;
  const router = express.Router();
  router.use(requireAdmin(ctx));

  // ---- Players ----------------------------------------------------------
  // Everyone whose launcher started the game, newest first, with how many cosmetics were granted to them.
  router.get('/players', (req, res) => {
    const limit = Math.min(Math.max(Number.parseInt(req.query.limit, 10) || 100, 1), 500);
    const search = String(req.query.q || '').trim().slice(0, 32).replace(/[%_]/g, '');
    const rows = db.prepare(
      `SELECT p.*, (SELECT COUNT(*) FROM cosmetic_grants g WHERE g.player_uuid = p.uuid) AS grants
       FROM players p WHERE p.name LIKE ? ORDER BY p.last_seen DESC LIMIT ?`,
    ).all(`%${search}%`, limit);
    const t = now();
    res.json({
      total: db.prepare('SELECT COUNT(*) AS n FROM players').get().n,
      activeToday: db.prepare('SELECT COUNT(*) AS n FROM players WHERE last_seen >= ?').get(t - 86400).n,
      accounts: db.prepare('SELECT COUNT(*) AS n FROM users').get().n,
      items: rows.map((r) => ({
        uuid: r.uuid, name: r.name, accountType: r.account_type, version: r.version, launches: r.launches, grants: r.grants,
        firstSeen: new Date(r.first_seen * 1000).toISOString(), lastSeen: new Date(r.last_seen * 1000).toISOString(),
      })),
    });
  });

  // ---- News -------------------------------------------------------------
  router.get('/news', (req, res) => {
    res.json({ items: db.prepare('SELECT * FROM news ORDER BY created_at DESC, id DESC').all().map((r) => ({ ...newsView(r), published: !!r.published })) });
  });

  router.post('/news', (req, res) => {
    const { title, body, category = 'launcher', pinned = false, published = true } = req.body || {};
    if (!text(title, 140) || !text(body, 8000) || !['launcher', 'server'].includes(category)) {
      return res.status(400).json({ error: 'A title (max 140), body (max 8000) and category (launcher or server) are required' });
    }
    const result = db.prepare(
      'INSERT INTO news (title, body, category, pinned, published, author_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
    ).run(title.trim(), body.trim(), category, pinned ? 1 : 0, published ? 1 : 0, req.user.id, now(), now());
    return res.status(201).json(newsView(db.prepare('SELECT * FROM news WHERE id = ?').get(Number(result.lastInsertRowid))));
  });

  router.put('/news/:id', (req, res) => {
    const row = db.prepare('SELECT * FROM news WHERE id = ?').get(Number(req.params.id));
    if (!row) return res.status(404).json({ error: 'News post not found' });
    const b = req.body || {};
    const title = b.title === undefined ? row.title : text(b.title, 140);
    const body = b.body === undefined ? row.body : text(b.body, 8000);
    const category = b.category === undefined ? row.category : b.category;
    if (!title || !body || !['launcher', 'server'].includes(category)) return res.status(400).json({ error: 'Invalid news fields' });
    db.prepare('UPDATE news SET title = ?, body = ?, category = ?, pinned = ?, published = ?, updated_at = ? WHERE id = ?')
      .run(title, body, category, (b.pinned ?? !!row.pinned) ? 1 : 0, (b.published ?? !!row.published) ? 1 : 0, now(), row.id);
    return res.json(newsView(db.prepare('SELECT * FROM news WHERE id = ?').get(row.id)));
  });

  router.delete('/news/:id', (req, res) => {
    const result = db.prepare('DELETE FROM news WHERE id = ?').run(Number(req.params.id));
    return result.changes ? res.json({ ok: true }) : res.status(404).json({ error: 'News post not found' });
  });

  // ---- Announcements ----------------------------------------------------
  const announcementView = (r) => ({
    id: r.id, message: r.message, level: r.level, active: !!r.active,
    startsAt: r.starts_at ? new Date(r.starts_at * 1000).toISOString() : null,
    endsAt: r.ends_at ? new Date(r.ends_at * 1000).toISOString() : null,
    createdAt: new Date(r.created_at * 1000).toISOString(),
  });

  router.get('/announcements', (req, res) => {
    res.json({ items: db.prepare('SELECT * FROM announcements ORDER BY created_at DESC, id DESC').all().map(announcementView) });
  });

  router.post('/announcements', (req, res) => {
    const { message, level = 'info', active = true, startsAt, endsAt } = req.body || {};
    const start = toEpoch(startsAt);
    const end = toEpoch(endsAt);
    if (!text(message, 300) || !['info', 'warning', 'critical'].includes(level) || start === undefined || end === undefined) {
      return res.status(400).json({ error: 'A message (max 300) and level (info, warning, critical) are required; dates must be ISO timestamps' });
    }
    const result = db.prepare('INSERT INTO announcements (message, level, active, starts_at, ends_at, created_at) VALUES (?, ?, ?, ?, ?, ?)')
      .run(message.trim(), level, active ? 1 : 0, start, end, now());
    return res.status(201).json(announcementView(db.prepare('SELECT * FROM announcements WHERE id = ?').get(Number(result.lastInsertRowid))));
  });

  router.put('/announcements/:id', (req, res) => {
    const row = db.prepare('SELECT * FROM announcements WHERE id = ?').get(Number(req.params.id));
    if (!row) return res.status(404).json({ error: 'Announcement not found' });
    const b = req.body || {};
    const message = b.message === undefined ? row.message : text(b.message, 300);
    const level = b.level === undefined ? row.level : b.level;
    const start = b.startsAt === undefined ? row.starts_at : toEpoch(b.startsAt);
    const end = b.endsAt === undefined ? row.ends_at : toEpoch(b.endsAt);
    if (!message || !['info', 'warning', 'critical'].includes(level) || start === undefined || end === undefined) {
      return res.status(400).json({ error: 'Invalid announcement fields' });
    }
    db.prepare('UPDATE announcements SET message = ?, level = ?, active = ?, starts_at = ?, ends_at = ? WHERE id = ?')
      .run(message, level, (b.active ?? !!row.active) ? 1 : 0, start, end, row.id);
    return res.json(announcementView(db.prepare('SELECT * FROM announcements WHERE id = ?').get(row.id)));
  });

  router.delete('/announcements/:id', (req, res) => {
    const result = db.prepare('DELETE FROM announcements WHERE id = ?').run(Number(req.params.id));
    return result.changes ? res.json({ ok: true }) : res.status(404).json({ error: 'Announcement not found' });
  });

  // ---- Releases (launcher updates) -------------------------------------
  const adminRelease = (r) => ({ ...releaseView(r), published: !!r.published, downloads: r.downloads, hasFile: !!r.file_name });

  router.get('/releases', (req, res) => {
    const rows = db.prepare('SELECT * FROM releases').all()
      .sort((a, b) => a.platform.localeCompare(b.platform) || compareVersions(b.version, a.version));
    res.json({ items: rows.map(adminRelease) });
  });

  router.post('/releases', (req, res) => {
    const { platform, version, notes = '', minSupported = '0.0.0', mandatory = false } = req.body || {};
    if (!['windows', 'android'].includes(platform) || !VERSION.test(version || '') || !VERSION.test(minSupported)
      || typeof notes !== 'string' || notes.length > 4000) {
      return res.status(400).json({ error: 'platform (windows or android) and a numeric version such as 1.2.0 are required' });
    }
    if (db.prepare('SELECT 1 FROM releases WHERE platform = ? AND version = ?').get(platform, version)) {
      return res.status(409).json({ error: `${platform} ${version} already exists` });
    }
    const result = db.prepare('INSERT INTO releases (platform, version, notes, min_supported, mandatory, created_at) VALUES (?, ?, ?, ?, ?, ?)')
      .run(platform, version, notes, minSupported, mandatory ? 1 : 0, now());
    return res.status(201).json(adminRelease(db.prepare('SELECT * FROM releases WHERE id = ?').get(Number(result.lastInsertRowid))));
  });

  router.put('/releases/:id', (req, res, next) => {
    if (req.headers['content-type'] && !req.is('json')) return next(); // file upload handled below
    const row = db.prepare('SELECT * FROM releases WHERE id = ?').get(Number(req.params.id));
    if (!row) return res.status(404).json({ error: 'Release not found' });
    return express.json({ limit: '64kb' })(req, res, (error) => {
      if (error) return next(error);
      const b = req.body || {};
      const notes = b.notes ?? row.notes;
      const minSupported = b.minSupported ?? row.min_supported;
      const published = b.published ?? !!row.published;
      if (typeof notes !== 'string' || notes.length > 4000 || !VERSION.test(minSupported)) {
        return res.status(400).json({ error: 'Invalid release fields' });
      }
      if (published && !row.file_name) return res.status(409).json({ error: 'Upload the installer before publishing this release' });
      db.prepare('UPDATE releases SET notes = ?, min_supported = ?, mandatory = ?, published = ? WHERE id = ?')
        .run(notes, minSupported, (b.mandatory ?? !!row.mandatory) ? 1 : 0, published ? 1 : 0, row.id);
      return res.json(adminRelease(db.prepare('SELECT * FROM releases WHERE id = ?').get(row.id)));
    });
  });

  // Raw-body upload: PUT /api/admin/releases/:id/file?ext=.exe  (body = installer bytes)
  router.put('/releases/:id/file', async (req, res, next) => {
    try {
      const row = db.prepare('SELECT * FROM releases WHERE id = ?').get(Number(req.params.id));
      if (!row) return res.status(404).json({ error: 'Release not found' });
      const ext = String(req.query.ext || '').toLowerCase();
      if (!EXTENSIONS[row.platform].includes(ext)) {
        return res.status(400).json({ error: `${row.platform} builds must be ${EXTENSIONS[row.platform].join(', ')}` });
      }
      const length = Number(req.headers['content-length'] || 0);
      if (!length) return res.status(411).json({ error: 'Content-Length is required' });
      if (length > config.maxUploadBytes) return res.status(413).json({ error: 'File is larger than the upload limit' });

      const fileName = `VYRN-${row.platform}-${row.version}${ext}`;
      const target = path.join(config.downloadsDir, fileName);
      const temp = `${target}.${crypto.randomBytes(4).toString('hex')}.part`;
      const hash = crypto.createHash('sha256');
      let size = 0;
      req.on('data', (chunk) => { size += chunk.length; hash.update(chunk); });
      try {
        await pipeline(req, fs.createWriteStream(temp));
        if (size !== length) throw new Error('Upload was cut short');
        fs.renameSync(temp, target);
      } catch (error) {
        fs.rmSync(temp, { force: true });
        throw error;
      }
      if (row.file_name && row.file_name !== fileName) fs.rmSync(path.join(config.downloadsDir, row.file_name), { force: true });
      db.prepare('UPDATE releases SET file_name = ?, sha256 = ?, size = ? WHERE id = ?').run(fileName, hash.digest('hex'), size, row.id);
      return res.json(adminRelease(db.prepare('SELECT * FROM releases WHERE id = ?').get(row.id)));
    } catch (error) { return next(error); }
  });

  router.delete('/releases/:id', (req, res) => {
    const row = db.prepare('SELECT * FROM releases WHERE id = ?').get(Number(req.params.id));
    if (!row) return res.status(404).json({ error: 'Release not found' });
    if (row.file_name) fs.rmSync(path.join(config.downloadsDir, row.file_name), { force: true });
    db.prepare('DELETE FROM releases WHERE id = ?').run(row.id);
    return res.json({ ok: true });
  });

  // ---- Users & statistics ----------------------------------------------
  router.get('/users', (req, res) => {
    const rows = db.prepare('SELECT id, username, email, role, minecraft_name, elyby_name, created_at, last_login_at FROM users ORDER BY created_at DESC LIMIT 500').all();
    res.json({
      items: rows.map((r) => ({
        id: r.id, username: r.username, email: r.email, role: r.role,
        minecraftName: r.minecraft_name, elybyName: r.elyby_name,
        createdAt: new Date(r.created_at * 1000).toISOString(),
        lastLoginAt: r.last_login_at ? new Date(r.last_login_at * 1000).toISOString() : null,
      })),
    });
  });

  router.get('/stats', (req, res) => {
    const t = now();
    const day = 86400;
    const one = (sql, ...args) => db.prepare(sql).get(...args);
    const all = (sql, ...args) => db.prepare(sql).all(...args);
    const perDay = all(
      `SELECT date(created_at, 'unixepoch') AS day, COUNT(*) AS launches, COUNT(DISTINCT install_id) AS installs
         FROM events WHERE event = 'launch' AND created_at >= ? GROUP BY day ORDER BY day`,
      t - 14 * day,
    );
    res.json({
      users: {
        total: one('SELECT COUNT(*) AS n FROM users').n,
        newLast7Days: one('SELECT COUNT(*) AS n FROM users WHERE created_at >= ?', t - 7 * day).n,
      },
      activeInstalls: {
        last24Hours: one('SELECT COUNT(DISTINCT install_id) AS n FROM events WHERE created_at >= ?', t - day).n,
        last7Days: one('SELECT COUNT(DISTINCT install_id) AS n FROM events WHERE created_at >= ?', t - 7 * day).n,
        last30Days: one('SELECT COUNT(DISTINCT install_id) AS n FROM events WHERE created_at >= ?', t - 30 * day).n,
      },
      launchesPerDay: perDay,
      byPlatform: all('SELECT platform, COUNT(DISTINCT install_id) AS installs FROM events WHERE created_at >= ? GROUP BY platform', t - 30 * day),
      byVersion: all(
        `SELECT platform, version, COUNT(DISTINCT install_id) AS installs FROM events WHERE created_at >= ?
          GROUP BY platform, version ORDER BY installs DESC LIMIT 20`,
        t - 30 * day,
      ),
      downloads: all('SELECT platform, version, downloads FROM releases WHERE file_name IS NOT NULL ORDER BY downloads DESC LIMIT 20'),
      content: {
        newsPosts: one('SELECT COUNT(*) AS n FROM news').n,
        activeAnnouncements: one('SELECT COUNT(*) AS n FROM announcements WHERE active = 1').n,
      },
    });
  });

  return router;
}

module.exports = adminRoutes;
