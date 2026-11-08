-- One row per (random install id, UTC day, event): a second "launch" from the same install on the same day is ignored.
CREATE TABLE IF NOT EXISTS events (
  install_id TEXT NOT NULL,
  day        TEXT NOT NULL,
  event      TEXT NOT NULL,
  platform   TEXT NOT NULL,
  version    TEXT NOT NULL,
  PRIMARY KEY (install_id, day, event)
);
CREATE INDEX IF NOT EXISTS events_by_day ON events (event, day);

CREATE TABLE IF NOT EXISTS announcements (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  message    TEXT NOT NULL,
  level      TEXT NOT NULL DEFAULT 'info',
  active     INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL
);

-- Only a one-way hash of the Minecraft UUID is kept (no names), so this can only count distinct players.
CREATE TABLE IF NOT EXISTS players_seen (
  hash      TEXT PRIMARY KEY,
  first_day TEXT NOT NULL,
  last_day  TEXT NOT NULL
);
