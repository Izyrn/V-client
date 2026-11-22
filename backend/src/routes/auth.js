'use strict';
const express = require('express');
const { hashPassword, verifyPassword, rateLimit } = require('../security');

const USERNAME = /^[A-Za-z0-9_]{3,16}$/;
const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const MC_NAME = /^[A-Za-z0-9_]{0,16}$/;

const userView = (row) => ({
  id: row.id,
  username: row.username,
  email: row.email,
  role: row.role,
  minecraftName: row.minecraft_name,
  elybyName: row.elyby_name,
  createdAt: new Date(row.created_at * 1000).toISOString(),
});

/** Reads the Bearer token and attaches req.user, or answers 401. */
function authenticate(ctx) {
  return (req, res, next) => {
    const header = req.headers.authorization || '';
    const payload = header.startsWith('Bearer ') ? ctx.tokens.verify(header.slice(7)) : null;
    const user = payload && ctx.db.prepare('SELECT * FROM users WHERE id = ?').get(payload.uid);
    if (!user) return res.status(401).json({ error: 'Sign in required' });
    req.user = user;
    return next();
  };
}

function requireAdmin(ctx) {
  const auth = authenticate(ctx);
  return (req, res, next) => auth(req, res, () => (req.user.role === 'admin'
    ? next()
    : res.status(403).json({ error: 'Administrator access required' })));
}

function authRoutes(ctx) {
  const { db, tokens, now } = ctx;
  const router = express.Router();
  const strict = rateLimit({ windowMs: 15 * 60_000, max: 30, bucket: 'auth' });
  const requireUser = authenticate(ctx);

  router.post('/register', strict, (req, res) => {
    const { username, email, password } = req.body || {};
    if (typeof username !== 'string' || !USERNAME.test(username)) {
      return res.status(400).json({ error: 'Username must be 3-16 letters, numbers or underscores' });
    }
    if (typeof email !== 'string' || !EMAIL.test(email) || email.length > 120) {
      return res.status(400).json({ error: 'Enter a valid email address' });
    }
    if (typeof password !== 'string' || password.length < 8 || password.length > 200) {
      return res.status(400).json({ error: 'Password must be at least 8 characters' });
    }
    const taken = db.prepare('SELECT 1 FROM users WHERE username = ? OR email = ?').get(username, email);
    if (taken) return res.status(409).json({ error: 'That username or email is already registered' });
    const result = db.prepare(
      'INSERT INTO users (username, email, password_hash, created_at, last_login_at) VALUES (?, ?, ?, ?, ?)',
    ).run(username, email, hashPassword(password), now(), now());
    const user = db.prepare('SELECT * FROM users WHERE id = ?').get(Number(result.lastInsertRowid));
    return res.status(201).json({ token: tokens.issue(user.id), user: userView(user) });
  });

  router.post('/login', strict, (req, res) => {
    const { login, password } = req.body || {};
    if (typeof login !== 'string' || typeof password !== 'string') {
      return res.status(400).json({ error: 'Enter your username (or email) and password' });
    }
    const user = db.prepare('SELECT * FROM users WHERE username = ? OR email = ?').get(login, login);
    // Same message for unknown user and wrong password so accounts cannot be enumerated.
    if (!user || !verifyPassword(password, user.password_hash)) {
      return res.status(401).json({ error: 'Incorrect username or password' });
    }
    db.prepare('UPDATE users SET last_login_at = ? WHERE id = ?').run(now(), user.id);
    return res.json({ token: tokens.issue(user.id), user: userView(user) });
  });

  router.get('/me', requireUser, (req, res) => res.json({ user: userView(req.user) }));

  // Links the VYRN account to the player's Minecraft / Ely.by names so both launchers share them.
  router.put('/profile', requireUser, (req, res) => {
    const { minecraftName, elybyName } = req.body || {};
    const next = {
      minecraft_name: minecraftName === undefined ? req.user.minecraft_name : minecraftName,
      elyby_name: elybyName === undefined ? req.user.elyby_name : elybyName,
    };
    if (typeof next.minecraft_name !== 'string' || !MC_NAME.test(next.minecraft_name)
      || typeof next.elyby_name !== 'string' || !MC_NAME.test(next.elyby_name)) {
      return res.status(400).json({ error: 'Minecraft names are up to 16 letters, numbers or underscores' });
    }
    db.prepare('UPDATE users SET minecraft_name = ?, elyby_name = ? WHERE id = ?')
      .run(next.minecraft_name, next.elyby_name, req.user.id);
    return res.json({ user: userView(db.prepare('SELECT * FROM users WHERE id = ?').get(req.user.id)) });
  });

  router.put('/password', requireUser, strict, (req, res) => {
    const { currentPassword, newPassword } = req.body || {};
    if (typeof currentPassword !== 'string' || !verifyPassword(currentPassword, req.user.password_hash)) {
      return res.status(403).json({ error: 'Current password is incorrect' });
    }
    if (typeof newPassword !== 'string' || newPassword.length < 8 || newPassword.length > 200) {
      return res.status(400).json({ error: 'Password must be at least 8 characters' });
    }
    db.prepare('UPDATE users SET password_hash = ? WHERE id = ?').run(hashPassword(newPassword), req.user.id);
    return res.json({ ok: true });
  });

  return router;
}

module.exports = authRoutes;
module.exports.authenticate = authenticate;
module.exports.requireAdmin = requireAdmin;
module.exports.userView = userView;
