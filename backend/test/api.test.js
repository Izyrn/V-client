'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const { buildConfig } = require('../src/config');
const { createApp } = require('../src/app');
const { pingJava, varInt } = require('../src/serverStatus');

const silent = { info() {}, warn() {}, error() {} };

async function start(t) {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'vclient-test-'));
  const config = buildConfig({ dataDir });
  config.admin.password = 'admin-password-1';
  const fakeStatus = { get: async () => ({ java: { online: true, players: { online: 3, max: 20 } }, bedrock: { online: false } }) };
  const app = createApp(config, { inMemory: true, log: silent, statusService: fakeStatus });
  const server = await new Promise((resolve) => { const s = app.listen(0, () => resolve(s)); });
  t.after(() => { server.close(); fs.rmSync(dataDir, { recursive: true, force: true }); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = async (method, url, { token, body, raw, headers } = {}) => {
    const response = await fetch(base + url, {
      method,
      headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
      body: raw || (body ? JSON.stringify(body) : undefined),
      redirect: 'manual',
    });
    const type = response.headers.get('content-type') || '';
    return { status: response.status, headers: response.headers, json: type.includes('json') ? await response.json() : null, response };
  };
  const login = await call('POST', '/api/auth/login', { body: { login: 'admin', password: 'admin-password-1' } });
  return { call, admin: login.json.token, base };
}

test('auth: register, login, wrong password, profile', async (t) => {
  const { call } = await start(t);
  const bad = await call('POST', '/api/auth/register', { body: { username: 'x', email: 'bad', password: '1' } });
  assert.equal(bad.status, 400);

  const reg = await call('POST', '/api/auth/register', { body: { username: 'Steve_1', email: 'steve@example.com', password: 'password123' } });
  assert.equal(reg.status, 201);
  assert.equal(reg.json.user.role, 'user');
  assert.ok(!('passwordHash' in reg.json.user));

  const dup = await call('POST', '/api/auth/register', { body: { username: 'steve_1', email: 'other@example.com', password: 'password123' } });
  assert.equal(dup.status, 409);

  const wrong = await call('POST', '/api/auth/login', { body: { login: 'steve@example.com', password: 'nope-nope' } });
  assert.equal(wrong.status, 401);
  const ok = await call('POST', '/api/auth/login', { body: { login: 'steve@example.com', password: 'password123' } });
  assert.equal(ok.status, 200);

  const profile = await call('PUT', '/api/auth/profile', { token: ok.json.token, body: { minecraftName: 'Steve', elybyName: 'SteveEly' } });
  assert.equal(profile.json.user.minecraftName, 'Steve');
  const badName = await call('PUT', '/api/auth/profile', { token: ok.json.token, body: { minecraftName: 'bad name!' } });
  assert.equal(badName.status, 400);
  assert.equal((await call('GET', '/api/auth/me')).status, 401);
  assert.equal((await call('GET', '/api/auth/me', { token: ok.json.token + 'x' })).status, 401);
});

test('admin routes reject anonymous and normal users', async (t) => {
  const { call } = await start(t);
  assert.equal((await call('POST', '/api/admin/news', { body: { title: 'a', body: 'b' } })).status, 401);
  const reg = await call('POST', '/api/auth/register', { body: { username: 'player1', email: 'p@example.com', password: 'password123' } });
  assert.equal((await call('GET', '/api/admin/stats', { token: reg.json.token })).status, 403);
});

test('news and announcements: admin creates, public reads, drafts hidden', async (t) => {
  const { call, admin } = await start(t);
  const created = await call('POST', '/api/admin/news', { token: admin, body: { title: 'Hello', body: 'World', category: 'server', pinned: true } });
  assert.equal(created.status, 201);
  await call('POST', '/api/admin/news', { token: admin, body: { title: 'Draft', body: 'x', published: false } });
  await call('POST', '/api/admin/news', { token: admin, body: { title: 'Launcher 1.1', body: 'x' } });

  const list = await call('GET', '/api/news');
  assert.deepEqual(list.json.items.map((n) => n.title), ['Hello', 'Launcher 1.1']);
  const serverOnly = await call('GET', '/api/news?category=server');
  assert.equal(serverOnly.json.items.length, 1);
  assert.equal((await call('GET', '/api/news/2')).status, 404);

  await call('POST', '/api/admin/announcements', { token: admin, body: { message: 'Maintenance tonight', level: 'warning' } });
  await call('POST', '/api/admin/announcements', { token: admin, body: { message: 'Expired', endsAt: '2000-01-01T00:00:00Z' } });
  await call('POST', '/api/admin/announcements', { token: admin, body: { message: 'Off', active: false } });
  const announcements = await call('GET', '/api/announcements');
  assert.deepEqual(announcements.json.items.map((a) => a.message), ['Maintenance tonight']);

  assert.equal((await call('POST', '/api/admin/announcements', { token: admin, body: { message: 'x', level: 'loud' } })).status, 400);
  assert.equal((await call('DELETE', `/api/admin/news/${created.json.id}`, { token: admin })).status, 200);
});

test('releases: upload, publish, update check, download counter', async (t) => {
  const { call, admin } = await start(t);
  const release = await call('POST', '/api/admin/releases', { token: admin, body: { platform: 'android', version: '1.2.0', notes: 'New', minSupported: '1.0.0' } });
  assert.equal(release.status, 201);
  assert.equal((await call('POST', '/api/admin/releases', { token: admin, body: { platform: 'android', version: '1.2.0' } })).status, 409);

  // Cannot publish without a file, nothing is offered yet.
  assert.equal((await call('PUT', `/api/admin/releases/${release.json.id}`, { token: admin, body: { published: true } })).status, 409);
  assert.equal((await call('GET', '/api/launcher/update?platform=android&current=1.0.0')).json.updateAvailable, false);

  const wrongExt = await call('PUT', `/api/admin/releases/${release.json.id}/file?ext=.exe`, { token: admin, raw: Buffer.from('x'), headers: { 'Content-Type': 'application/octet-stream' } });
  assert.equal(wrongExt.status, 400);
  const bytes = Buffer.from('fake apk contents');
  const upload = await call('PUT', `/api/admin/releases/${release.json.id}/file?ext=.apk`, { token: admin, raw: bytes, headers: { 'Content-Type': 'application/octet-stream' } });
  assert.equal(upload.status, 200);
  assert.equal(upload.json.size, bytes.length);
  assert.equal(upload.json.sha256, require('node:crypto').createHash('sha256').update(bytes).digest('hex'));

  assert.equal((await call('PUT', `/api/admin/releases/${release.json.id}`, { token: admin, body: { published: true } })).json.published, true);

  const older = await call('GET', '/api/launcher/update?platform=android&current=1.0.0');
  assert.equal(older.json.updateAvailable, true);
  assert.equal(older.json.latest.version, '1.2.0');
  const current = await call('GET', '/api/launcher/update?platform=android&current=1.2.0');
  assert.equal(current.json.updateAvailable, false);
  const ancient = await call('GET', '/api/launcher/update?platform=android&current=0.9.0');
  assert.equal(ancient.json.mandatory, true, 'below minSupported must force the update');
  assert.equal((await call('GET', '/api/launcher/update?platform=ios')).status, 400);

  const redirect = await call('GET', '/download/android');
  assert.equal(redirect.status, 302);
  assert.match(redirect.headers.get('location'), /^\/downloads\/VYRN-android-1\.2\.0\.apk$/);
  const direct = await call('GET', older.json.latest.url);
  assert.equal(direct.status, 200);
  assert.equal((await call('GET', '/api/admin/releases', { token: admin })).json.items[0].downloads, 1);
  assert.equal((await call('GET', '/downloads/..%2Fsecret.key')).status, 404);
});

test('stats events are validated and counted', async (t) => {
  const { call, admin } = await start(t);
  const id = 'a1b2c3d4-e5f6-7890';
  assert.equal((await call('POST', '/api/stats/event', { body: { installId: id, platform: 'windows', version: '1.0.0', event: 'launch' } })).status, 202);
  assert.equal((await call('POST', '/api/stats/event', { body: { installId: id, platform: 'windows', version: '1.0.0', event: 'launch' } })).status, 202);
  assert.equal((await call('POST', '/api/stats/event', { body: { installId: '!', platform: 'windows', version: '1', event: 'launch' } })).status, 400);
  const stats = await call('GET', '/api/admin/stats', { token: admin });
  assert.equal(stats.json.activeInstalls.last24Hours, 1);
  assert.equal(stats.json.launchesPerDay[0].launches, 2);
  assert.equal(stats.json.users.total, 1);
});

test('server status endpoint and config', async (t) => {
  const { call } = await start(t);
  const status = await call('GET', '/api/server/status');
  assert.equal(status.json.java.players.online, 3);
  assert.equal((await call('GET', '/api/config')).json.server.java.port, 19145);
  assert.equal((await call('GET', '/api/nope')).status, 404);
  assert.equal((await call('POST', '/api/auth/login', { raw: '{bad', headers: { 'Content-Type': 'application/json' } })).status, 400);
});

test('Minecraft status ping parses a Server List Ping response', async () => {
  const json = Buffer.from(JSON.stringify({ version: { name: '1.21.11' }, players: { online: 7, max: 50 }, description: { text: '§aVSMP ', extra: [{ text: 'SMP' }] } }));
  const body = Buffer.concat([varInt(0), varInt(json.length), json]);
  const server = net.createServer((socket) => socket.once('data', () => socket.write(Buffer.concat([varInt(body.length), body]))));
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const result = await pingJava('127.0.0.1', server.address().port);
  server.close();
  assert.equal(result.online, true);
  assert.deepEqual(result.players, { online: 7, max: 50 });
  assert.equal(result.motd, 'VSMP SMP');

  const down = await pingJava('127.0.0.1', 1);
  assert.equal(down.online, false);
});

test('cosmetics: admin publishes an item with an asset, players download it, premium needs a grant', async (t) => {
  const { call, admin, base } = await start(t);
  const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(80, 7)]);

  assert.equal((await call('POST', '/api/admin/cosmetics', { body: { id: 'x' } })).status, 401);
  assert.equal((await call('GET', '/api/cosmetics/catalog')).json.items.length, 0);

  const badAsset = await call('PUT', '/api/admin/cosmetics/assets/cape.png', { token: admin, raw: Buffer.from('not a png'), headers: { 'Content-Type': 'image/png' } });
  assert.equal(badAsset.status, 400);
  const asset = await call('PUT', '/api/admin/cosmetics/assets/royal.png', { token: admin, raw: png, headers: { 'Content-Type': 'image/png' } });
  assert.equal(asset.status, 201);
  assert.equal(asset.json.size, png.length);

  const invalid = await call('POST', '/api/admin/cosmetics', { token: admin, body: { id: 'Bad Id', category: 'cape', name: 'x', definition: {} } });
  assert.equal(invalid.status, 400);
  const noSlot = await call('POST', '/api/admin/cosmetics', { token: admin, body: { id: 'anim', category: 'animated', name: 'x', definition: {} } });
  assert.equal(noSlot.status, 400);

  const created = await call('POST', '/api/admin/cosmetics', {
    token: admin,
    body: { id: 'royal_cape', category: 'cape', tier: 'premium', name: 'Royal Cape', definition: { texture: { asset: 'royal.png', width: 64, height: 32 } } },
  });
  assert.equal(created.status, 201);
  assert.equal((await call('POST', '/api/admin/cosmetics', { token: admin, body: { id: 'royal_cape', category: 'cape', name: 'dup', definition: {} } })).status, 409);

  const catalog = await call('GET', '/api/cosmetics/catalog');
  assert.equal(catalog.json.schema, 1);
  const item = catalog.json.items[0];
  assert.equal(item.id, 'royal_cape');
  assert.equal(item.tier, 'premium');
  assert.equal(item.assets[0].file, 'royal.png');
  assert.equal(item.assets[0].sha256, require('node:crypto').createHash('sha256').update(png).digest('hex'));

  const again = await call('GET', '/api/cosmetics/catalog', { headers: { 'If-None-Match': catalog.headers.get('etag') } });
  assert.equal(again.status, 304);

  const download = await fetch(base + item.assets[0].url);
  assert.equal(download.status, 200);
  assert.deepEqual(Buffer.from(await download.arrayBuffer()), png);
  assert.equal((await call('GET', '/api/cosmetics/assets/missing.png')).status, 404);
  assert.equal((await call('GET', '/api/cosmetics/assets/..%2Fsecret.png')).status, 404);

  const uuid = '1331c81d33844eb6baf6a511c8298af3';
  assert.deepEqual((await call('GET', `/api/cosmetics/owned/${uuid}`)).json.owned, []);
  assert.equal((await call('POST', '/api/admin/cosmetics/grants', { token: admin, body: { uuid, cosmeticId: 'Not Valid!' } })).status, 400);
  assert.equal((await call('POST', '/api/admin/cosmetics/grants', { token: admin, body: { uuid, cosmeticId: 'royal_cape' } })).status, 201);
  assert.deepEqual((await call('GET', `/api/cosmetics/owned/${uuid}`)).json.owned, ['royal_cape']);
  const tier = await call('POST', '/api/admin/cosmetics/grants', { token: admin, body: { player: uuid, invites: 10 } });
  assert.equal(tier.status, 201);
  assert.deepEqual(tier.json.granted, ['ice_cape', 'lava_cape', 'neon_cape']);
  assert.deepEqual((await call('GET', `/api/cosmetics/owned/${uuid}`)).json.owned.sort(),
    ['ice_cape', 'lava_cape', 'neon_cape', 'royal_cape']);
  assert.equal((await call('POST', '/api/admin/cosmetics/grants', { token: admin, body: { player: '!!', invites: 5 } })).status, 400);
  assert.equal((await call('GET', '/api/admin/cosmetics/rewards', { token: admin })).json.items.length, 7);
  assert.equal((await call('GET', '/api/cosmetics/owned/not-a-uuid')).status, 400);

  // Replacing the asset bumps the item version so clients download it again.
  await call('PUT', '/api/admin/cosmetics/assets/royal.png', { token: admin, raw: Buffer.concat([png, Buffer.from('x')]), headers: { 'Content-Type': 'image/png' } });
  assert.equal((await call('GET', '/api/cosmetics/catalog')).json.items[0].version, 2);

  // Disabling hides it from the catalog and tells clients to drop it.
  await call('PUT', '/api/admin/cosmetics/royal_cape', { token: admin, body: { enabled: false } });
  const hidden = (await call('GET', '/api/cosmetics/catalog')).json;
  assert.equal(hidden.items.length, 0);
  assert.deepEqual(hidden.removed, ['royal_cape']);
});

test('friends: request, accept, presence, decline and remove', async (t) => {
  const { call } = await start(t);
  const signUp = async (name) => (await call('POST', '/api/auth/register', { body: { username: name, email: `${name}@example.com`, password: 'password123' } })).json.token;
  const alice = await signUp('Alice_1');
  const bob = await signUp('Bob_22');
  const carol = await signUp('Carol_3');

  assert.equal((await call('GET', '/api/friends')).status, 401);
  assert.equal((await call('POST', '/api/friends/request', { token: alice, body: { username: 'Alice_1' } })).status, 400);
  assert.equal((await call('POST', '/api/friends/request', { token: alice, body: { username: 'Nobody_9' } })).status, 404);
  assert.equal((await call('POST', '/api/friends/request', { token: alice, body: { username: '!!' } })).status, 400);

  const sent = await call('POST', '/api/friends/request', { token: alice, body: { username: 'bob_22' } });
  assert.equal(sent.status, 201);
  assert.equal(sent.json.status, 'pending');
  assert.equal((await call('POST', '/api/friends/request', { token: alice, body: { username: 'Bob_22' } })).status, 409);

  const bobsView = (await call('GET', '/api/friends', { token: bob })).json;
  assert.equal(bobsView.incoming.length, 1);
  assert.equal(bobsView.incoming[0].username, 'Alice_1');
  assert.equal((await call('GET', '/api/friends', { token: alice })).json.outgoing[0].username, 'Bob_22');

  // Only the addressee can accept.
  const requestId = bobsView.incoming[0].requestId;
  assert.equal((await call('POST', `/api/friends/requests/${requestId}/accept`, { token: alice })).status, 404);
  assert.equal((await call('POST', `/api/friends/requests/${requestId}/accept`, { token: carol })).status, 404);
  assert.equal((await call('POST', `/api/friends/requests/${requestId}/accept`, { token: bob })).status, 200);

  // Presence: friends see activity, strangers see nothing, and nobody is online before a heartbeat.
  assert.equal((await call('GET', '/api/friends', { token: alice })).json.friends[0].online, false);
  assert.equal((await call('PUT', '/api/presence', { token: bob, body: { activity: 'Playing Minecraft', server: 'VSMP', platform: 'windows' } })).status, 200);
  const alicesView = (await call('GET', '/api/friends', { token: alice })).json;
  assert.equal(alicesView.friends[0].username, 'Bob_22');
  assert.equal(alicesView.friends[0].online, true);
  assert.equal(alicesView.friends[0].activity, 'Playing Minecraft');
  assert.equal(alicesView.friends[0].server, 'VSMP');
  assert.ok(!('email' in alicesView.friends[0]));
  assert.equal((await call('GET', '/api/friends', { token: carol })).json.friends.length, 0);

  // Adding someone who already asked you accepts their request; declining removes it.
  await call('POST', '/api/friends/request', { token: carol, body: { username: 'Alice_1' } });
  const back = await call('POST', '/api/friends/request', { token: alice, body: { username: 'Carol_3' } });
  assert.equal(back.json.status, 'accepted');
  const dave = await signUp('Dave_44');
  await call('POST', '/api/friends/request', { token: dave, body: { username: 'Bob_22' } });
  const pending = (await call('GET', '/api/friends', { token: bob })).json.incoming[0];
  assert.equal((await call('DELETE', `/api/friends/requests/${pending.requestId}`, { token: bob })).status, 200);
  assert.equal((await call('GET', '/api/friends', { token: bob })).json.incoming.length, 0);

  // Removing a friend works for either side.
  const bobId = alicesView.friends[0].userId;
  assert.equal((await call('DELETE', `/api/friends/${bobId}`, { token: alice })).status, 200);
  assert.equal((await call('GET', '/api/friends', { token: bob })).json.friends.length, 0);
  assert.equal((await call('DELETE', `/api/friends/${bobId}`, { token: alice })).status, 404);
});

test('players: the launcher reports who plays, only admins can list them', async (t) => {
  const { call, admin } = await start(t);
  const uuid = '3bf39b66aa3832a9b8c955a490978486';
  assert.equal((await call('POST', '/api/players/seen', { body: { uuid, name: 'NyroxPlayZ', accountType: 'offline', version: '1.3.0' } })).status, 202);
  assert.equal((await call('POST', '/api/players/seen', { body: { uuid, name: 'NyroxPlayZ', accountType: 'offline', version: '1.3.0' } })).status, 202);
  assert.equal((await call('POST', '/api/players/seen', { body: { uuid: 'nope', name: 'x' } })).status, 400);
  assert.equal((await call('GET', '/api/admin/players')).status, 401);
  const list = await call('GET', '/api/admin/players?q=nyrox', { token: admin });
  assert.equal(list.status, 200);
  assert.equal(list.json.items.length, 1);
  assert.equal(list.json.items[0].name, 'NyroxPlayZ');
  assert.equal(list.json.items[0].launches, 2);
  assert.equal(list.json.total, 1);
  await call('POST', '/api/admin/cosmetics/grants', { token: admin, body: { uuid, cosmeticId: 'ice_cape' } });
  assert.equal((await call('GET', '/api/admin/players', { token: admin })).json.items[0].grants, 1);
});
