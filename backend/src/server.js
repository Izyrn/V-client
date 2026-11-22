'use strict';
const { buildConfig } = require('./config');
const { createApp } = require('./app');
const { openDatabase } = require('./db');
const { createPersistence } = require('./persistence');

async function main() {
  const config = buildConfig();
  const db = openDatabase(config.dataDir);
  // On hosts that wipe the disk on restart, bring accounts, friends and grants back from the GitHub backup.
  const persistence = createPersistence(config);
  await persistence.restore(db);
  const app = createApp(config, { db });
  persistence.start(db);

  const server = app.listen(config.port, () => {
    console.log(`[VYRN] API + website listening on http://localhost:${config.port}`);
    console.log(`[VYRN] Data directory: ${config.dataDir}`);
    console.log(`[VYRN] GitHub backup: ${persistence.enabled ? 'on' : 'off (set BACKUP_GITHUB_TOKEN and BACKUP_GITHUB_REPO)'}`);
  });

  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
      console.log(`[VYRN] ${signal} received, saving and shutting down`);
      setTimeout(() => process.exit(1), 9000).unref();
      persistence.flush(db).finally(() => server.close(() => process.exit(0)));
    });
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
