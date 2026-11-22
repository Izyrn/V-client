'use strict';
const express = require('express');
const { rateLimit } = require('../security');
const { authenticate } = require('./auth');

const USERNAME = /^[A-Za-z0-9_]{3,16}$/;
const MAX_FRIENDS = 200;
/** A player counts as online while the launcher has sent a heartbeat this recently (it sends one every ~45 s). */
const ONLINE_SECONDS = 120;
const clean = (value, max) => (typeof value === 'string' ? value.replace(/[\u0000-\u001f]/g, '').trim().slice(0, max) : '');

/**
 * Friends and presence for VYRN accounts: send a request by username, accept or decline, remove, and see
 * which friends are online and what they are doing. Only friends can see a player's presence, and only the
 * display fields (username, linked Minecraft name, activity, server) are ever shared; never an email.
 */
function friendsRoutes(ctx) {
  const { db, now } = ctx;
  const router = express.Router();
  const requireUser = authenticate(ctx);
  const writes = rateLimit({ windowMs: 15 * 60_000, max: 60, bucket: 'friends' });
  router.use(requireUser);

  const person = (row, presence) => {
    const online = !!presence && presence.last_seen >= now() - ONLINE_SECONDS;
    return {
      userId: row.id,
      username: row.username,
      minecraftName: row.minecraft_name || '',
      online,
      activity: online ? presence.activity : '',
      server: online ? presence.server : '',
      lastSeen: presence ? new Date(presence.last_seen * 1000).toISOString() : null,
    };
  };
  const presenceOf = (userId) => db.prepare('SELECT * FROM presence WHERE user_id = ?').get(userId);
  const userById = (id) => db.prepare('SELECT id, username, minecraft_name FROM users WHERE id = ?').get(id);

  router.get('/', (req, res) => {
    const me = req.user.id;
    const friends = [];
    const incoming = [];
    const outgoing = [];
    const rows = db.prepare('SELECT * FROM friendships WHERE requester_id = ? OR addressee_id = ? ORDER BY created_at').all(me, me);
    for (const row of rows) {
      const otherId = row.requester_id === me ? row.addressee_id : row.requester_id;
      const other = userById(otherId);
      if (!other) continue;
      if (row.status === 'accepted') friends.push(person(other, presenceOf(otherId)));
      else if (row.addressee_id === me) incoming.push({ requestId: row.id, userId: other.id, username: other.username });
      else outgoing.push({ requestId: row.id, userId: other.id, username: other.username });
    }
    friends.sort((a, b) => Number(b.online) - Number(a.online) || a.username.localeCompare(b.username));
    res.set('Cache-Control', 'no-store');
    return res.json({ friends, incoming, outgoing });
  });

  router.post('/request', writes, (req, res) => {
    const me = req.user.id;
    const username = clean(req.body?.username, 16);
    if (!USERNAME.test(username)) return res.status(400).json({ error: 'Enter their VYRN username (3-16 letters, numbers or _)' });
    const target = db.prepare('SELECT id, username FROM users WHERE username = ?').get(username);
    if (!target) return res.status(404).json({ error: `No VYRN account is called ${username}` });
    if (target.id === me) return res.status(400).json({ error: 'You cannot add yourself' });
    const existing = db.prepare(
      'SELECT * FROM friendships WHERE (requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?)',
    ).get(me, target.id, target.id, me);
    if (existing) {
      if (existing.status === 'accepted') return res.status(409).json({ error: `You and ${target.username} are already friends` });
      if (existing.requester_id === me) return res.status(409).json({ error: `You already sent ${target.username} a request` });
      // They already asked us: adding them back accepts it.
      db.prepare("UPDATE friendships SET status = 'accepted', responded_at = ? WHERE id = ?").run(now(), existing.id);
      return res.json({ status: 'accepted', username: target.username });
    }
    const count = db.prepare("SELECT COUNT(*) AS n FROM friendships WHERE (requester_id = ? OR addressee_id = ?) AND status = 'accepted'").get(me, me).n;
    if (count >= MAX_FRIENDS) return res.status(409).json({ error: `Friend lists are limited to ${MAX_FRIENDS}` });
    db.prepare("INSERT INTO friendships (requester_id, addressee_id, status, created_at) VALUES (?, ?, 'pending', ?)").run(me, target.id, now());
    return res.status(201).json({ status: 'pending', username: target.username });
  });

  router.post('/requests/:id/accept', writes, (req, res) => {
    const row = db.prepare("SELECT * FROM friendships WHERE id = ? AND addressee_id = ? AND status = 'pending'").get(Number(req.params.id), req.user.id);
    if (!row) return res.status(404).json({ error: 'That request no longer exists' });
    db.prepare("UPDATE friendships SET status = 'accepted', responded_at = ? WHERE id = ?").run(now(), row.id);
    return res.json({ ok: true });
  });

  // Decline (as the addressee) and cancel (as the requester) both delete the pending request.
  router.delete('/requests/:id', writes, (req, res) => {
    const result = db.prepare(
      "DELETE FROM friendships WHERE id = ? AND status = 'pending' AND (addressee_id = ? OR requester_id = ?)",
    ).run(Number(req.params.id), req.user.id, req.user.id);
    return result.changes ? res.json({ ok: true }) : res.status(404).json({ error: 'That request no longer exists' });
  });

  router.delete('/:userId', writes, (req, res) => {
    const other = Number(req.params.userId);
    const result = db.prepare(
      `DELETE FROM friendships WHERE status = 'accepted'
         AND ((requester_id = ? AND addressee_id = ?) OR (requester_id = ? AND addressee_id = ?))`,
    ).run(req.user.id, other, other, req.user.id);
    return result.changes ? res.json({ ok: true }) : res.status(404).json({ error: 'You are not friends with that player' });
  });

  return router;
}

/** PUT /api/presence: the launcher's heartbeat. */
friendsRoutes.presence = (ctx) => {
  const { db, now } = ctx;
  const router = express.Router();
  const beat = rateLimit({ windowMs: 60_000, max: 12, bucket: 'presence' });
  router.put('/', authenticate(ctx), beat, (req, res) => {
    const activity = clean(req.body?.activity, 40);
    const server = clean(req.body?.server, 60);
    const platform = ['windows', 'android'].includes(req.body?.platform) ? req.body.platform : '';
    db.prepare(`INSERT INTO presence (user_id, last_seen, activity, server, platform) VALUES (?, ?, ?, ?, ?)
      ON CONFLICT(user_id) DO UPDATE SET last_seen = excluded.last_seen, activity = excluded.activity,
        server = excluded.server, platform = excluded.platform`).run(req.user.id, now(), activity, server, platform);
    return res.json({ ok: true });
  });
  return router;
};

module.exports = friendsRoutes;
