# VYRN cosmetics API

Cosmetics are **visual only**. The client draws them on your own character; nothing here is sent to a game
server, changes hitboxes, movement, or any gameplay value. New cosmetics are added on the backend (or in the
bundled catalog) and appear in the menu without updating the client, because every cosmetic is a JSON
*definition* the client interprets.

## How the pieces fit

```
backend  ──GET /api/cosmetics/catalog──▶  mod (CosmeticCatalog)  ──▶  Cosmetics menu (press K)
   │  ──GET /api/cosmetics/owned/<uuid>──▶        │
   └──GET /api/cosmetics/assets/<file>───▶  assets cached in <game>/vclient/cosmetics/
```

* The launcher passes the backend address to the game as `-Dvclient.backend=<url>` (News page → *Backend &
  updates*). Without one the menu shows the catalog bundled in the mod
  (`mod/src/main/resources/vclient/builtin-cosmetics.json`).
* The mod loads the bundled catalog, overlays the last downloaded catalog from disk, then refreshes it in the
  background (`ETag` / `304` aware). Assets download lazily, the first time a cosmetic is shown or equipped,
  are verified against their SHA-256, and are cached on disk.
* What you have equipped is saved in `<game>/config/vclient-cosmetics.json`. The master switch is the
  **Cosmetics** module in the Right Shift mod menu.

The Windows launcher has a matching **Cosmetics** page. It lists the same catalog (the bundled one plus the
backend's) and writes the same `config/vclient-cosmetics.json`, so what you equip there is what the game loads.
The live 3D preview is only in the game.

## Catalog document (schema 1)

`GET /api/cosmetics/catalog` (public, cacheable):

```json
{
  "schema": 1,
  "generatedAt": "2026-10-17T12:00:00Z",
  "removed": ["old_item_id"],
  "items": [
    {
      "id": "royal_cape",
      "version": 3,
      "category": "cape",
      "tier": "premium",
      "name": "Royal Cape",
      "description": "…",
      "sort": 13,
      "startsAt": null,
      "endsAt": null,
      "definition": { "texture": { "asset": "royal.png", "width": 64, "height": 32 } },
      "assets": [
        { "file": "royal.png", "url": "/api/cosmetics/assets/royal.png", "sha256": "…", "size": 1234 }
      ]
    }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `id` | `a-z 0-9 _ . -`, unique. A server item with the id of a bundled item replaces it. |
| `version` | Bump to make clients rebuild textures/models. The backend does it for you on every edit. |
| `category` | `cape`, `wings`, `hat`, `emote`, `animated`, `trail` (the menu tabs). Unknown values are ignored by older clients. |
| `tier` | `free` (always usable), `premium` (usable when the player owns it), `event` (usable while `startsAt ≤ now < endsAt`, or if owned). Unknown tiers are treated as premium (locked). |
| `startsAt` / `endsAt` | Unix seconds, `null` = no limit. |
| `definition` | How it looks – see below. `animated` items must set `definition.slot` (`cape`, `wings`, `hat`, `trail` or `emote`). |
| `assets` | Files the definition names. `url` may be relative to the backend. Only `https` (or `http` to localhost) is accepted. Max 2 MB each. |
| `removed` | Ids to hide, so retiring an item also retires a bundled one. |

`GET /api/cosmetics/owned/<minecraft uuid>` → `{ "uuid": "…", "owned": ["royal_cape"] }`. Premium items are
unlocked by an admin grant (below); the client treats the list as the player's unlocks.

Premium gating is a menu rule enforced by the client, not a security boundary: since cosmetics only change how
your own game looks, nothing is protected by it beyond honouring what you were granted.

## Definitions

Everything is data. Sizes are in model pixels (1/16 block) and angles in degrees.

### `texture`

```json
"texture": { "asset": "wings.png", "width": 64, "height": 64, "frames": 1, "frameTicks": 4 }
"texture": { "width": 64, "height": 32, "frames": 16, "frameTicks": 2,
             "generator": { "type": "shimmer", "colors": ["#1c2f6b", "#46f0c4"], "direction": "vertical" } }
```

* `asset` – a PNG from `assets`. With `frames > 1` the PNG is a vertical strip of equal frames (an animated cosmetic).
* `generator` – paints the texture in the client so no download is needed: `solid`, `gradient`, `stripes`,
  `checker`, `shimmer` (animated sweep), `sparkle` (animated noise). Options: `colors`, `direction`
  (`vertical`/`horizontal`), `size`, `border`.
* Capes use the standard 64×32 cape layout.

### Capes and animated capes (`slot: cape`)

Only a `texture`. An animated cape just has several frames.

### Hats, wings and animated pieces (`slot: hat | wings`)

A `texture` plus a `model`:

```json
"model": { "parts": [ {
  "bone": "body",              // "head" or "body": the player part it follows
  "pivot": [2.5, 1, 2.4],      // rotation point, relative to the bone
  "rotation": [0, -25, 0],
  "mirror": true,              // also draw a mirrored copy on the other side (wings)
  "cuboids": [ { "origin": [0, -1, 0], "size": [12, 3, 1], "uv": [0, 0], "inflate": 0 } ],
  "anim": { "axis": "y", "target": "rotation", "mode": "sine", "speed": 0.6, "amplitude": 18, "phase": 0 }
} ] }
```

`anim.target` is `rotation`, `position` or `scale`; `mode` is `sine`, `spin`, `bob` or `pulse`; `speed` is cycles
(or turns) per second.

### Trails (`slot: trail`)

```json
"trail": { "particle": "minecraft:end_rod", "rate": 2, "count": 1, "spread": [0.35, 0.9, 0.35],
           "offset": [0, 0.2, 0], "velocity": [0, 0.01, 0], "onlyMoving": false }
```

`rate` is ticks between spawns, `count` particles per spawn (capped at 8 per tick whatever the server says). For
`minecraft:dust` add `color` (`#RRGGBB`) and `scale`. Particles are spawned client-side through the normal
particle pipeline, so the player's *Particles* video setting and the *Trail density* slider apply.

### Emotes (`slot: emote`)

```json
"emote": { "duration": 50, "loop": false, "cancelOnMove": true, "tracks": [
  { "part": "rightArm", "axis": "x", "ease": "smooth", "keys": [[0, 0], [8, -165], [42, -165], [50, 0]] } ] }
```

`part` is `head`, `body`, `rightArm`, `leftArm`, `rightLeg` or `leftLeg`. Keys are `[tick, value]` pairs.
`channel` is `rotation` (default, degrees) or `position` (pixels). Tracks add to the normal pose unless
`"absolute": true`. Emotes only move your model: they are visible in third person (F5) and in the Cosmetics
preview; they are not sent to the server.

## Admin endpoints

All need an admin token (`Authorization: Bearer …`, see backend/README.md).

| Method & path | Purpose |
| --- | --- |
| `GET /api/admin/cosmetics` | Every cosmetic (including disabled) and uploaded assets |
| `POST /api/admin/cosmetics` | Create (`id`, `category`, `tier`, `name`, `description`, `sort`, `startsAt`, `endsAt`, `enabled`, `definition`) |
| `PUT /api/admin/cosmetics/:id` | Change any of those fields; bumps `version` |
| `DELETE /api/admin/cosmetics/:id` | Delete |
| `PUT /api/admin/cosmetics/assets/:name` | Upload a PNG (raw body, ≤ 2 MB). Bumps the version of cosmetics that use it |
| `DELETE /api/admin/cosmetics/assets/:name` | Remove a PNG |
| `POST /api/admin/cosmetics/grants` | `{ "uuid": "…", "cosmeticId": "…" }` unlock an item for a player |
| `DELETE /api/admin/cosmetics/grants` | Same body, revoke |
| `GET /api/admin/cosmetics/grants?uuid=` | List grants |

Example – publish a cape with its own artwork:

```bash
curl -X PUT  -H "Authorization: Bearer $TOKEN" -H "Content-Type: image/png" \
     --data-binary @royal.png  $BACKEND/api/admin/cosmetics/assets/royal.png
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" $BACKEND/api/admin/cosmetics -d '{
  "id": "royal_cape", "category": "cape", "tier": "premium", "name": "Royal Cape",
  "description": "A velvet cape fit for the VSMP court.",
  "definition": { "texture": { "asset": "royal.png", "width": 64, "height": 32 } } }'
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" $BACKEND/api/admin/cosmetics/grants \
     -d '{ "uuid": "1331c81d33844eb6baf6a511c8298af3", "cosmeticId": "royal_cape" }'
```

Players see the new cape the next time the menu refreshes (it also refreshes on every game start or with the
menu's **Refresh** button).

## Performance notes

* Models are built once per cosmetic version; per frame only a handful of float transforms are set.
* Textures are decoded and generated on a background thread; only the final GPU upload runs on the render thread.
* Nothing is rendered for other players or when the player is invisible/spectating; with no cosmetic equipped the
  render and tick hooks return immediately.
* Skin/cape resolution is memoised, so the per-frame cost for an unchanged player is a few reference comparisons.
* Trails are limited to 8 particles per tick and follow the player's particle setting.

## What is not included (yet)

Other players cannot see your cosmetics. Showing them would need the server to know what each player has
equipped and a way to authenticate that, which is a separate feature (`PUT /equipped` plus a batched lookup);
the catalog format and the renderer were written so it can be added without changing definitions.
