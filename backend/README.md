# VYRN backend

One small Node.js service that powers the website, the Windows launcher and the Android app. No database server to install: data lives in a SQLite file.

- Node.js **22.13+** (uses the built-in `node:sqlite`; you may see an "experimental" warning on startup, which is expected)
- One dependency: `express`

```powershell
npm install
npm start          # http://localhost:8080
npm test           # API + server-ping tests
```

## Configuration

Environment variables or a `.env` file (see [.env.example](.env.example)).

| Variable | Default | Meaning |
| --- | --- | --- |
| `PORT` | `8080` | HTTP port |
| `DATA_DIR` | `./data` | SQLite database, signing secret, uploaded installers (back this folder up) |
| `TOKEN_SECRET` | generated | Signs login tokens; generated into `DATA_DIR/secret.key` when empty |
| `ADMIN_USERNAME` / `ADMIN_EMAIL` / `ADMIN_PASSWORD` | `admin` / – / random | First admin, created only if no admin exists. A random password is printed once |
| `JAVA_HOST` / `JAVA_PORT` | `neuron.zeroxhost.space` / `19145` | VSMP Java address |
| `BEDROCK_HOST` / `BEDROCK_PORT` | same host / `19145` | VSMP Bedrock address used by the Android **Play** button |
| `DISCORD_URL`, `WEBSITE_URL` | empty | Shown on the site and in both apps |
| `TRUST_PROXY` | `0` | Set `1` behind nginx/Caddy/Cloudflare so rate limits use the real client IP |
| `MAX_UPLOAD_MB` | `300` | Largest installer accepted |

## Deploying

Put it behind HTTPS (Caddy, nginx, Cloudflare Tunnel, …). Android release builds refuse plain `http://`. Example Caddyfile:

```
vclient.example.com {
    reverse_proxy localhost:8080
}
```

Run it as a service (`pm2 start src/server.js --name vclient`, a systemd unit, or a Windows service via NSSM) with `TRUST_PROXY=1`. The reverse proxy must allow request bodies at least as large as your installer (`request_body max_size` in Caddy, `client_max_body_size` in nginx).

## API

Public (no sign-in):

| Method + path | Returns |
| --- | --- |
| `GET /api/health` | `{ ok }` |
| `GET /api/config` | VSMP addresses (Java + Bedrock), Discord and website URLs |
| `GET /api/server/status` | Live `java` / `bedrock` status: online, players, version, latency (cached 20 s) |
| `GET /api/news?limit=&offset=&category=launcher\|server` | Published posts, pinned first |
| `GET /api/announcements` | Active announcements (`info`, `warning`, `critical`) |
| `GET /api/launcher/update?platform=windows\|android&current=1.0.0` | `updateAvailable`, `mandatory`, `latest { version, notes, size, sha256, url }` |
| `GET /api/launcher/releases` | Latest published release per platform (used by the website) |
| `POST /api/stats/event` | Anonymous usage counter `{ installId, platform, version, event }` |
| `GET /download/windows`, `/download/android` | Redirect to the newest published installer |
| `GET /api/cosmetics/catalog` | In-game cosmetics (capes, wings, hats, emotes, trails) with `ETag`; assets at `/api/cosmetics/assets/:file` |
| `GET /api/cosmetics/owned/:uuid` | Premium/event cosmetics a player has been granted |

Friends and presence (VYRN accounts, `Authorization: Bearer <token>`): `GET /api/friends` (friends with online status and activity, incoming and outgoing requests), `POST /api/friends/request` `{ username }`, `POST /api/friends/requests/:id/accept`, `DELETE /api/friends/requests/:id` (decline or cancel), `DELETE /api/friends/:userId`, and `PUT /api/presence` `{ activity, server, platform }` (the launcher sends this every ~45 seconds; a player counts as online for 2 minutes after the last one). Friends see only username, linked Minecraft name, activity and server, never an email.

Accounts (`Authorization: Bearer <token>`): `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/auth/me`, `PUT /api/auth/profile`, `PUT /api/auth/password`. Passwords are hashed with scrypt; auth endpoints are rate limited.

Management API (admin role only; there is no web panel, use curl or any HTTP client): `GET/POST/PUT/DELETE /api/admin/news`, `/api/admin/announcements`, `/api/admin/releases` (+ `PUT /api/admin/releases/:id/file?ext=.apk` with the raw installer as the body), `GET /api/admin/users`, `GET /api/admin/stats`, and `/api/admin/cosmetics` (items, PNG assets, per-player grants; see [docs/COSMETICS_API.md](../docs/COSMETICS_API.md)).

## Publishing an update

```bash
B=https://vsmp-client.onrender.com
TOKEN=$(curl -s -X POST $B/api/auth/login -H "Content-Type: application/json" \
  -d '{"login":"admin","password":"YOUR_ADMIN_PASSWORD"}' | node -pe 'JSON.parse(require("fs").readFileSync(0)).token')
AUTH="Authorization: Bearer $TOKEN"

# 1. create the release (platform windows|android; optional mandatory / minSupported force older versions to update)
ID=$(curl -s -X POST $B/api/admin/releases -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"platform":"android","version":"1.2.0","notes":"What is new"}' | node -pe 'JSON.parse(require("fs").readFileSync(0)).id')

# 2. upload the installer (.exe/.msi/.zip for windows, .apk for android); size + SHA-256 are recorded
curl -X PUT "$B/api/admin/releases/$ID/file?ext=.apk" -H "$AUTH" -H "Content-Type: application/octet-stream" --data-binary @app-release.apk

# 3. publish (apps see it on next launch); use {"published":false} to withdraw
curl -X PUT $B/api/admin/releases/$ID -H "$AUTH" -H "Content-Type: application/json" -d '{"published":true}'

# news / announcements
curl -X POST $B/api/admin/news -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"title":"Hello","body":"Text","category":"server"}'
curl -X POST $B/api/admin/announcements -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"message":"Maintenance tonight","level":"warning"}'
```

## Privacy

Usage counters contain a random install id, platform, launcher version and an event name (`launch`, `play`, `join`, `update`). No IP addresses, hardware ids or account names are stored with them.

## Grant tool and invite rewards

Open `/grants.html` on the backend (for example `https://vsmp-client-backend.onrender.com/grants.html`), sign in with the
admin account, then enter an Ely.by name or UUID to unlock one cosmetic, take it away, or give an invite reward.
The API is `POST /api/admin/cosmetics/grants` with `{ "player": "<name or uuid>", "cosmeticId": "ice_cape" }` or
`{ "player": "<name or uuid>", "invites": 10 }`; `GET /api/admin/cosmetics/rewards` lists the ladder.
Tiers (5, 8, 10, 12, 15, 17, 20 invites) live in `src/rewards.js`. On the free Render plan grants are lost on every
restart, so use a persistent disk or database before relying on them.

## Keeping data on Render's free plan (GitHub backup)

The free plan wipes the disk whenever the service sleeps or restarts, which would delete accounts, friends and
grants. Set two environment variables and the backend saves them to a private GitHub repository and restores them
at start-up (and saves again on shutdown):

- `BACKUP_GITHUB_REPO` - `owner/name` of a **private** repository (create an empty one, e.g. `vclient-data`).
- `BACKUP_GITHUB_TOKEN` - a fine-grained personal access token limited to that repository with
  "Contents: Read and write".

Checks the logs for `GitHub backup: on` and `Backup restored from GitHub`. If a restore fails, saving is switched
off so the stored backup is never overwritten with an empty database.
