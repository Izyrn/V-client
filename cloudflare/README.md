# V-Client API (Cloudflare Worker + D1)

Replaces the Render backend. It never sleeps and stores almost nothing.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/server/status` | VSMP Java status and player count (looked up via mcstatus.io, cached 20 s) |
| `GET /api/announcements` | Active announcements |
| `POST /api/stats/event` | Anonymous event `{installId, platform, version, event}`; one row per install, day and event |
| `POST /api/players/seen` / `GET /api/players/seen` | Counts distinct players (only a one-way hash of the UUID is stored, never a name) |
| `GET /api/stats/summary` | **API key required.** Total launches, daily active users (30 days), launches per version |
| `POST /api/admin/announcements`, `DELETE /api/admin/announcements/:id` | **API key required.** Manage announcements |
| `GET /api/health`, `/api/config`, `/api/news`, `/api/launcher/update` | Small compatibility endpoints |

CORS allows only `https://eliteprofast.github.io` (see `ALLOWED_ORIGIN` in `wrangler.toml`). The launcher is not a browser and sends no `Origin`, so it is unaffected; browsers on other sites get HTTP 403.

## Deploy (one time)

```powershell
cd cloudflare
npm install
npx wrangler login
npx wrangler d1 create vclient          # copy the printed database_id into wrangler.toml
npm run db:init                          # creates the tables from schema.sql
npx wrangler secret put STATS_API_KEY    # paste a long random value; keep it private
npx wrangler deploy                      # prints https://vclient-api.<your-subdomain>.workers.dev
```

Then put that URL in `AppInfo.DEFAULT_BACKEND_URL` (launcher) and `API_BASE` at the top of `website/script.js`.

## Use

```powershell
curl -H "Authorization: Bearer YOUR_KEY" https://vclient-api.<your-subdomain>.workers.dev/api/stats/summary
curl -X POST -H "Authorization: Bearer YOUR_KEY" -H "Content-Type: application/json" `
  -d '{"message":"Maintenance tonight","level":"warning"}' https://vclient-api.<your-subdomain>.workers.dev/api/admin/announcements
```
