'use strict';
const fs = require('node:fs');
const path = require('node:path');

/**
 * Keeps the important data alive on hosts whose disk is wiped on every restart (Render's free plan).
 * The accounts, friends, grants, news and the token secret are saved as one JSON file in a PRIVATE GitHub
 * repository, and restored into the empty database at start-up. Turned on by two environment variables:
 *
 *   BACKUP_GITHUB_TOKEN  a fine-grained token with "Contents: read and write" on that one repository
 *   BACKUP_GITHUB_REPO   "owner/name" of the private repository
 *
 * Without them nothing changes. Tables are restored in this order so references line up.
 */
const TABLES = ['users', 'news', 'announcements', 'cosmetics', 'friendships', 'cosmetic_grants', 'players'];
const BACKUP_FILE = 'vclient-backup.json';
const SAVE_INTERVAL_MS = 15_000;

function settings(env = process.env) {
  const token = (env.BACKUP_GITHUB_TOKEN || '').trim();
  const repo = (env.BACKUP_GITHUB_REPO || '').trim();
  if (!token || !/^[\w.-]+\/[\w.-]+$/.test(repo)) return null;
  return { token, repo, file: (env.BACKUP_GITHUB_FILE || BACKUP_FILE).trim() };
}

function exportData(db, secret) {
  const data = { version: 1, savedAt: new Date().toISOString(), secret: secret || '', tables: {} };
  for (const table of TABLES) data.tables[table] = db.prepare(`SELECT * FROM ${table}`).all();
  return data;
}

/** Fills the empty database from a backup. Returns how many rows were restored. */
function importData(db, data) {
  let restored = 0;
  db.exec('BEGIN');
  try {
    for (const table of TABLES) {
      const rows = data.tables?.[table];
      if (!Array.isArray(rows)) continue;
      for (const row of rows) {
        const columns = Object.keys(row);
        if (!columns.length) continue;
        db.prepare(`INSERT OR REPLACE INTO ${table} (${columns.join(', ')}) VALUES (${columns.map(() => '?').join(', ')})`)
          .run(...columns.map((column) => row[column]));
        restored += 1;
      }
    }
    db.exec('COMMIT');
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
  return restored;
}

function createPersistence(config, options = {}) {
  const cfg = options.settings === undefined ? settings() : options.settings;
  const fetchImpl = options.fetch || globalThis.fetch;
  const log = options.log || console;
  if (!cfg) return { enabled: false, restore: async () => 0, start() {}, flush: async () => {} };

  const url = `https://api.github.com/repos/${cfg.repo}/contents/${encodeURIComponent(cfg.file)}`;
  const headers = {
    Authorization: `Bearer ${cfg.token}`,
    Accept: 'application/vnd.github+json',
    'User-Agent': 'vclient-backend',
    'X-GitHub-Api-Version': '2022-11-28',
  };
  const secretFile = path.join(config.dataDir, 'secret.key');
  let sha = null;
  let lastSaved = '';
  let canSave = false; // Saving is only allowed once we know what is already stored, so a failed restore cannot wipe it.
  let saving = false;

  async function load() {
    const response = await fetchImpl(url, { headers, signal: AbortSignal.timeout(20_000) });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(`GitHub answered HTTP ${response.status} while reading the backup`);
    const body = await response.json();
    sha = body.sha;
    if (body.content) return JSON.parse(Buffer.from(body.content, 'base64').toString('utf8'));
    const raw = await fetchImpl(url, { headers: { ...headers, Accept: 'application/vnd.github.raw+json' }, signal: AbortSignal.timeout(30_000) });
    if (!raw.ok) throw new Error(`GitHub answered HTTP ${raw.status} while reading the backup`);
    return JSON.parse(await raw.text());
  }

  function currentSecret() {
    try { return fs.readFileSync(secretFile, 'utf8').trim(); } catch { return ''; }
  }

  /** Call before the app starts: restores the secret file and the tables into an empty database. */
  async function restore(db) {
    try {
      const data = await load();
      if (data) {
        if (data.secret && !fs.existsSync(secretFile) && !config.tokenSecret) {
          fs.mkdirSync(config.dataDir, { recursive: true });
          fs.writeFileSync(secretFile, data.secret, { mode: 0o600 });
        }
        const empty = db.prepare('SELECT COUNT(*) AS n FROM users').get().n === 0;
        const restored = empty ? importData(db, data) : 0;
        lastSaved = JSON.stringify({ ...data, savedAt: '' });
        log.log(`[VYRN] Backup restored from GitHub (${restored} rows, saved ${data.savedAt}).`);
      } else {
        log.log('[VYRN] No GitHub backup exists yet; it will be created on the first change.');
      }
      canSave = true;
      return 1;
    } catch (error) {
      log.error(`[VYRN] Could not restore the GitHub backup, so saving is switched off to protect it: ${error.message}`);
      return 0;
    }
  }

  async function save(db) {
    if (!canSave || saving) return;
    const data = exportData(db, currentSecret());
    const fingerprint = JSON.stringify({ ...data, savedAt: '' });
    if (fingerprint === lastSaved) return;
    saving = true;
    try {
      for (let attempt = 0; attempt < 2; attempt++) {
        const response = await fetchImpl(url, {
          method: 'PUT',
          headers: { ...headers, 'Content-Type': 'application/json' },
          body: JSON.stringify({
            message: 'Backup', content: Buffer.from(JSON.stringify(data)).toString('base64'), ...(sha ? { sha } : {}),
          }),
          signal: AbortSignal.timeout(30_000),
        });
        if (response.ok) {
          sha = (await response.json()).content.sha;
          lastSaved = fingerprint;
          return;
        }
        if (response.status === 409 || response.status === 422) {
          const fresh = await fetchImpl(url, { headers, signal: AbortSignal.timeout(20_000) });
          sha = fresh.ok ? (await fresh.json()).sha : null;
          continue;
        }
        throw new Error(`GitHub answered HTTP ${response.status} while saving the backup`);
      }
    } catch (error) {
      log.error(`[VYRN] Backup save failed (will retry): ${error.message}`);
    } finally {
      saving = false;
    }
  }

  let timer = null;
  return {
    enabled: true,
    restore,
    save,
    start(db) {
      timer = setInterval(() => save(db), SAVE_INTERVAL_MS);
      timer.unref();
    },
    async flush(db) {
      if (timer) clearInterval(timer);
      saving = false;
      await save(db);
    },
  };
}

module.exports = { createPersistence, exportData, importData, settings, TABLES };
