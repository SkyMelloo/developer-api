# SkyMelloo / MellooEssentials Developer API

This document describes the API surface intended for compatible SkyMelloo and MellooEssentials clients and for developers modifying or extending either mod.

> Base URL: `https://sky.melloo.me/api/public/mod/v1/`
>
> This is the **only** base URL a compatible client should use. `/api/mod/*` (no `public/v1`) is this
> project's own internal path, used by the official SkyMelloo/MellooEssentials builds - it can change
> shape at any time without notice. `/api/public/mod/v1/*` is stable: it won't change shape once
> published, and a future breaking change gets its own `/api/public/mod/v2/` instead. Changes to this
> API are announced in the [API Changelog](https://sky.melloo.me/changelog/api).
>
> The API is **not a general-purpose public Hypixel proxy**. Every `v1` route requires real mod auth (see section 2) - even the few whose internal equivalent is unauthenticated for the website's own browser frontend. A handful of bootstrap/metadata routes are the exception; each is marked below.
>
> **Developer notice:** You are responsible for your client and the traffic it generates. Rate limits and abuse controls apply. Excessive, abusive, evasive, or disruptive use may be restricted or have access revoked. Availability and compatibility are not guaranteed; build clients to fail gracefully.

## 1. API categories

This document covers the client-facing API intended for SkyMelloo, MellooEssentials, and compatible forks/extensions.

| Category | Authentication | Intended use |
|---|---|---|
| Public metadata | None | Version checks, changelog, dependencies, download status |
| Mod-authenticated | Signed Minecraft-client request | Presence, cloud settings, friends, relay chat, linking, staff encounters |
| Authenticated read API | Mod auth | Player/search data available to compatible authenticated mods |

---

# 2. Mod authentication

Protected mod endpoints do **not** use a reusable bearer token.

A client proves that it is a live, logged-in Minecraft account and registers an ephemeral Ed25519 public key for the current launch. Every protected request is then signed independently.

A mod-auth session lasts about **20 hours**.

## 2.1 Authentication flow

### Step 1 — Create an ephemeral Ed25519 key pair

Generate a new Ed25519 key pair for the current client launch.

The public key sent to the API must be:

- DER encoded
- SPKI format
- Base64 encoded

Keep the private key client-side.

### Step 2 — Request a challenge

```http
GET /api/public/mod/v1/auth/challenge
```

Example response:

```json
{
  "serverId": "0123456789abcdef0123456789abcdef",
  "serverTime": 1786390000000
}
```

`serverTime` can be used to calculate a clock offset. Signed requests are only accepted within a small timestamp window, so clients should not assume the local system clock is perfectly correct.

### Step 3 — Complete Minecraft's normal session-server join proof

Use the returned `serverId` with Minecraft/Mojang's normal `joinServer` flow for the currently logged-in Minecraft account.

The API subsequently asks Mojang's session server whether that username actually joined the supplied `serverId`.

The challenge is:

- one-time use
- short lived
- tied to the subsequent verification

### Step 4 — Register the public key

```http
POST /api/public/mod/v1/auth/verify
Content-Type: application/json
```

```json
{
  "serverId": "0123456789abcdef0123456789abcdef",
  "username": "ExamplePlayer",
  "uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "publicKey": "<base64-spki-der-ed25519-public-key>"
}
```

Example success response:

```json
{
  "expiresAt": 1786462000000,
  "serverTime": 1786390001000
}
```

The server verifies the Minecraft identity through Mojang before accepting the key.

Multiple compatible mods may authenticate the same Minecraft account simultaneously; each can have its own ephemeral key.

## 2.2 Personal API keys (testing only)

For testing without launching Minecraft each time, a logged-in sky.melloo.me account can accept the Developer API terms once on [Account → API](https://sky.melloo.me/account/api) and generate a personal key there, then send it as a single header instead of the signing flow above:

```http
X-SkyMelloo-Test-Key: <your key>
```

A key always acts as your own linked Minecraft account - there's no way to use one to act as anyone else. Two types:

| Type | Access | Who can generate one |
|---|---|---|
| Test key | `/api/public/mod/v1/` only | Any account with a linked Minecraft account |
| Developer key | `/api/public/mod/v1/` and the internal API | Accounts with the Developer role |

A key-authenticated response includes an `X-SkyMelloo-Notice` header as a reminder.

Keys expire **3 days** after being generated - regenerate on your account page to keep using one. This isn't automatable, since regenerating needs a real logged-in website session.

**Personal test keys are for your own local development and testing only. Never embed a test key in a build you distribute, publish, or commit to a public repository. If a leaked or shared test key is used for abusive traffic, we may revoke it - and any elevated access tied to your account - without warning. For anything you actually ship, use real mod-auth (2.1), not a test key.**

---

# 3. Signing protected requests

Every request to a `requireModAuth` endpoint must carry these headers:

```text
X-SkyMelloo-UUID
X-SkyMelloo-Timestamp
X-SkyMelloo-Nonce
X-SkyMelloo-Signature
```

Header names are case-insensitive.

## 3.1 Canonical signed message

Normalize the UUID by:

1. converting it to lowercase;
2. removing `-`.

Hash the **exact raw HTTP request body bytes** with SHA-256 and encode the digest as lowercase hexadecimal.

Then construct this UTF-8 message:

```text
<normalized uuid>
<HTTP METHOD>
<path without query string>
<timestamp>
<nonce>
<SHA-256 hex of raw request body>
```

In code-like form:

```text
message =
    normalizedUuid + "\n" +
    method.toUpperCase() + "\n" +
    pathWithoutQuery + "\n" +
    timestampString + "\n" +
    nonce + "\n" +
    sha256Hex(rawBody)
```

Sign those exact UTF-8 bytes with the session's Ed25519 private key.

Base64-encode the resulting signature and send it as:

```text
X-SkyMelloo-Signature: <base64 signature>
```

### Important details

- Sign the **path only**, not the query string.
- Use the full API path, e.g. `/api/public/mod/v1/settings`.
- The body hash must be calculated from the exact bytes sent over HTTP.
- For an empty body, hash the empty byte array.
- The timestamp is milliseconds since Unix epoch.
- Use a fresh, unpredictable nonce for every request.
- Reusing a nonce while it is still inside the replay window causes authentication to fail.
- The server currently allows roughly **20 seconds** of timestamp skew.

Example headers:

```http
X-SkyMelloo-UUID: aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
X-SkyMelloo-Timestamp: 1786390001234
X-SkyMelloo-Nonce: 6c901c25f61b4ef89cb98ad5a6f49d63
X-SkyMelloo-Signature: <base64>
```

## 3.2 Worked signing example

Suppose the client sends:

```http
POST /api/public/mod/v1/settings
Content-Type: application/json
X-SkyMelloo-Client: mod

{"settings":{"showScore":true}}
```

The client must hash the exact bytes of:

```text
{"settings":{"showScore":true}}
```

If:

```text
UUID      = aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
timestamp = 1786390001234
nonce     = 6c901c25f61b4ef89cb98ad5a6f49d63
```

then the signed message is conceptually:

```text
aaaaaaaabbbbccccddddeeeeeeeeeeee
POST
/api/public/mod/v1/settings
1786390001234
6c901c25f61b4ef89cb98ad5a6f49d63
<body SHA-256>
```

A safer implementation pattern is:

```text
rawBody = UTF8(JSON.stringify(payload))
bodyHash = SHA256(rawBody)
canonical = normalizedUuid + "\n" +
            "POST" + "\n" +
            "/api/public/mod/v1/settings" + "\n" +
            timestamp + "\n" +
            nonce + "\n" +
            hex(bodyHash)

signature = Ed25519.sign(privateKey, UTF8(canonical))

HTTP.send(rawBody) // send these exact bytes; do not stringify again
```

### Common signing mistakes

`401` responses are often caused by one of these:

- hashing one JSON serialization and sending another;
- including `?query=params` in the signed path;
- signing a relative path different from the actual API path;
- using seconds instead of milliseconds;
- local clock drift without applying `serverTime`;
- reusing a nonce;
- Base64-encoding the canonical message instead of the Ed25519 signature;
- sending a PKCS#8/private key where the verify endpoint expects the public SPKI key.

---

# 4. Client identity / mod namespace

Some shared endpoints distinguish SkyMelloo data from MellooEssentials data using:

```http
X-SkyMelloo-Client: mod
```

The current server treats the exact value `mod` as **SkyMelloo**. Other clients using the shared settings/presence code path are treated as **MellooEssentials**.

This currently affects at least:

- cloud-settings namespace;
- presence's `skymelloo` marker / per-mod online count.

A third independent mod namespace is not currently defined.

---

# 5. Rate limits

The API has multiple abuse-protection layers.

Current application-level limits, all on a 1-minute window:

- normal API traffic: **1200 requests / minute / IP**
- internal mod traffic (official builds): **500 requests / minute / verified Minecraft UUID**
- `/api/public/mod/v1/*`: **600 requests / minute / verified Minecraft UUID or personal key**
- personal API key traffic specifically: **200 requests / minute / key**
- fresh (non-cached) Hypixel-backed lookups: **5 / minute / caller**, shared Hypixel API key budget

A rate-limited request returns HTTP `429`, normally with:

```json
{
  "error": "Too many requests - please slow down."
}
```

These are safety ceilings, not polling targets. Clients should cache results, batch where supported, back off after `429`, and avoid unnecessary polling.

## Responsible use and access

Access to sky.melloo.me is provided for compatible SkyMelloo/MellooEssentials clients, forks, development, and reasonable experimentation. It is not an unlimited hosted backend for unrelated projects.

By using the API, you are responsible for your client and its traffic. In particular:

- do not intentionally bypass or evade rate limits, including deliberately pacing requests to stay just under a limit;
- do not rotate IPs, accounts, UUIDs, sessions, keys, or nonces to defeat abuse controls;
- do not send messages to other players in a way they'd reasonably consider spam;
- do not flood presence, search, player, relay, refresh, or other endpoints;
- do not scrape or mirror large portions of the service unnecessarily;
- do not use the API as a generic Hypixel-data proxy for another public service;
- do not send data on behalf of users without their knowledge where the feature is intended to be opt-in;
- do not impersonate SkyMelloo, MellooEssentials, staff, another Minecraft account, or an official release;
- do not probe private/internal routes or attempt to bypass authentication/authorization;
- do not deliberately interfere with other users or the availability of the service.

Repeatedly hitting `429` responses and immediately retrying is considered incorrect client behavior. Use exponential backoff with jitter and stop retrying aggressively.

Abusive, disruptive, automated, or clearly unreasonable use may result in requests being blocked or API access being restricted or revoked. Technical access controls may change without notice when necessary to protect the service.

There is no guarantee of uninterrupted availability, permanent endpoint compatibility, or a particular rate-limit allowance. Developers are responsible for handling downtime, API changes, malformed responses, timeouts, and upstream failures gracefully.

If your project needs substantially more traffic than a normal mod client, coordinate with the project before shipping it rather than designing around the current ceilings.

### Recommended `429` handling

A simple strategy:

```text
request
  |
  +-- 2xx --> continue normally
  |
  +-- 429 --> wait
              1st retry: ~2-3 seconds
              2nd retry: ~5-7 seconds
              3rd retry: ~10-15 seconds
              then stop/background the operation
```

Add a small random jitter so many clients do not retry simultaneously.

---

# 6. Public mod metadata

These endpoints do not require mod auth.

## `GET /api/public/mod/v1/version-check`

Query parameters:

| Parameter | Required | Description |
|---|---:|---|
| `version` | no | Client version, defaults to `0.0.0` |
| `hash` | no | Client build hash |

Example:

```http
GET /api/public/mod/v1/version-check?version=0.30.1&hash=<hash>
```

Response fields currently include:

```json
{
  "compatible": true,
  "integrityOk": true,
  "buildKind": "official",
  "minVersion": "0.0.0",
  "latestVersion": "0.30.1",
  "latestPublicVersion": "0.30.1",
  "latestVersionReleasedAt": "2026-08-10T20:00:00.000Z",
  "upToDate": true,
  "maintainerUsername": "Example",
  "message": null,
  "updateAvailableMessage": null,
  "unofficialBuildMessage": null
}
```

`unofficialBuildMessage` is `null` whenever `integrityOk` is `true`. When a build is reported as unverified/unofficial, this field carries a server-authored, human-readable notice - a compatible client **must** surface it to the user (see the compatibility rules below).

### Forks and modified builds

Build verification and mod API authentication are separate concepts.

A modified/test client can still perform the Minecraft + Ed25519 authentication flow. A build hash that is not registered as an official/dev build may simply be reported as unverified by the version endpoint.

Do not present an unofficial fork as an official SkyMelloo release.

---

## `GET /api/public/mod/v1/mellooessentials/version-check`

MellooEssentials equivalent of the SkyMelloo version check.

Query parameters:

- `version`
- optional `hash`

Response shape is similar to SkyMelloo's version endpoint, including `unofficialBuildMessage`.

---

## `GET /api/public/mod/v1/changelog`

Returns the public SkyMelloo changelog.

## `GET /api/public/mod/v1/mellooessentials/changelog`

Returns the public MellooEssentials changelog.

## `GET /api/public/mod/v1/dependencies`

Returns SkyMelloo dependencies:

```json
{
  "required": [],
  "recommended": []
}
```

## `GET /api/public/mod/v1/mellooessentials/dependencies`

Returns MellooEssentials dependencies:

```json
{
  "required": []
}
```

## `GET /api/public/mod/v1/download-status`

```json
{
  "available": true
}
```

## `GET /api/public/mod/v1/mellooessentials/download-status`

```json
{
  "available": true
}
```

## Downloads

```http
GET /api/public/mod/v1/download
GET /api/public/mod/v1/download/:version
GET /api/public/mod/v1/mellooessentials/download
GET /api/public/mod/v1/mellooessentials/download/:version
```

Published releases are downloadable publicly. Unpublished/dev builds are intentionally handled differently and are not part of the external developer API.

---

# 7. Presence

Presence is shared infrastructure used by SkyMelloo and MellooEssentials.

Presence entries expire quickly when clients stop reporting; the current TTL is about **20 seconds**.

## `POST /api/public/mod/v1/presence`

**Requires mod auth.**

The acting UUID and username come from the verified mod session. Do **not** send a UUID in the body expecting it to be trusted.

Example body:

```json
{
  "cosmetics": ["example_effect"],
  "status": "online",
  "dungeonSync": null,
  "afk": false,
  "accountLinked": true,
  "location": "Hub"
}
```

Fields:

| Field | Type | Notes |
|---|---|---|
| `cosmetics` | array of strings | Max 64 entries are retained |
| `status` | string | Truncated server-side |
| `dungeonSync` | object/null | Optional, SkyMelloo live-dungeon payload |
| `afk` | boolean | Current AFK state |
| `accountLinked` | boolean | Whether the client considers the account linked |
| `location` | string/null | Short current location/floor display text |

Response:

```json
{
  "ok": true
}
```

### `dungeonSync`

`dungeonSync` is intentionally treated mostly as an opaque mod-owned object by the presence layer. The server currently accepts an object up to roughly **300 KB** after JSON serialization.

SkyMelloo's website and run recorder understand the current SkyMelloo payload. If you are extending SkyMelloo, preserve existing field meanings unless both client and server are updated together.

---

## `POST /api/public/mod/v1/presence/query`

**Requires mod auth.**

```json
{
  "uuids": [
    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    "11111111-2222-3333-4444-555555555555"
  ]
}
```

At most 128 UUIDs are considered.

Example response:

```json
{
  "present": [
    {
      "uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
      "username": "ExamplePlayer",
      "cosmetics": [],
      "status": "online",
      "dungeonSync": null,
      "afk": false,
      "accountLinked": true,
      "skymelloo": true,
      "role": null
    }
  ]
}
```

Only currently-present users are returned.

`role` is resolved by the server from the user's real linked website/team role; clients cannot claim their own staff role.

---

## `GET /api/public/mod/v1/presence/count`

Public aggregate only.

```json
{
  "online": 12,
  "byMod": {
    "skymelloo": 8,
    "mellooessentials": 4
  }
}
```

No user identities are returned here.

---

## `GET /api/public/mod/v1/presence/stream`

Public Server-Sent Events stream used for live website updates.

Response content type:

```text
text/event-stream
```

Treat the exact event payloads as website-facing implementation detail unless you intentionally coordinate changes with the server.

---

# 8. Cloud settings

SkyMelloo and MellooEssentials use the same routes but keep separate server-side blobs.

Set the correct `X-SkyMelloo-Client` header before using these endpoints.

The settings object is opaque to the server. Maximum serialized settings size is currently **32 KiB**.

## `GET /api/public/mod/v1/settings`

**Requires mod auth.**

Success:

```json
{
  "settings": {
    "example": true
  },
  "updatedAt": 1786390000000
}
```

If no cloud copy exists:

```http
404 Not Found
```

```json
{
  "error": "No cloud settings saved yet"
}
```

Use `updatedAt` to decide whether local or cloud data is newer. Do not blindly overwrite the newer copy.

---

## `POST /api/public/mod/v1/settings`

**Requires mod auth.**

```json
{
  "settings": {
    "example": true
  }
}
```

Response:

```json
{
  "ok": true
}
```

Possible errors include `400` for a missing/non-object settings value and `413` for an oversized payload.

---

# 9. Account-link status

## `GET /api/public/mod/v1/permissions`

**Requires mod auth.**

Despite the historical endpoint name, features are not currently permission-tiered here. The endpoint reports whether the current Minecraft account is linked to a sky.melloo.me website account.

```json
{
  "accountLinked": true
}
```

Do not treat this as an authorization oracle for staff/admin actions.

---

# 10. Website account linking from a mod

## `POST /api/public/mod/v1/link/start`

**Requires mod auth.**

Creates a short-lived website linking token for the verified Minecraft identity.

Response:

```json
{
  "token": "<token>"
}
```

The website can inspect/complete that token through:

```text
GET  /api/account/link/token/:token
POST /api/account/link/token/:token/complete
```

The completion route requires a real logged-in website account.

A compatible mod should normally open the corresponding sky.melloo.me linking page rather than attempting to implement the website login flow itself.

---

## `POST /api/public/mod/v1/unlink`

**Requires mod auth.**

Unlinks the verified Minecraft account from its associated website account.

```json
{
  "ok": true
}
```

---

## `POST /api/public/mod/v1/verify`

**Requires mod auth.**

Used by the in-game verification-code flow.

```json
{
  "code": "ABC123"
}
```

Success:

```json
{
  "ok": true,
  "username": "ExamplePlayer"
}
```

The server always uses the UUID from mod auth, not a UUID supplied in the request body.

---

# 11. SkyMelloo Friends

This is a SkyMelloo-native friends system, separate from Hypixel's own friend list.

Friend mutations always act as the verified Minecraft account.

## `GET /api/public/mod/v1/friends`

**Requires mod auth.**

```json
{
  "friends": [
    {
      "uuid": "...",
      "username": "FriendName"
    }
  ],
  "requests": [
    {
      "uuid": "...",
      "username": "PendingName",
      "at": 1786390000000
    }
  ]
}
```

---

## `POST /api/public/mod/v1/friends/request`

**Requires mod auth.**

```json
{
  "username": "TargetPlayer"
}
```

Both players must have linked sky.melloo.me accounts.

Possible `status` values currently include:

- `pending`
- `accepted`
- `already_friends`
- `self`
- `limit`

Example:

```json
{
  "ok": true,
  "username": "TargetPlayer",
  "status": "pending"
}
```

---

## `POST /api/public/mod/v1/friends/accept`

```json
{
  "username": "TargetPlayer"
}
```

## `POST /api/public/mod/v1/friends/decline`

```json
{
  "username": "TargetPlayer"
}
```

## `POST /api/public/mod/v1/friends/remove`

```json
{
  "username": "TargetPlayer"
}
```

Typical response:

```json
{
  "ok": true,
  "username": "TargetPlayer"
}
```

---

# 12. Message relay

Relay messages are **ephemeral**. They are not a persistent chat-history service.

Current server behavior:

- maximum queued messages per inbox: 50
- message TTL: about 2 minutes
- maximum message text retained: 256 characters

## Direct message

### `POST /api/public/mod/v1/relay/message`

**Requires mod auth.**

The recipient must already be a confirmed SkyMelloo friend.

```json
{
  "toUsername": "FriendName",
  "text": "hello :3"
}
```

Response:

```json
{
  "ok": true,
  "username": "FriendName"
}
```

If the target is not a SkyMelloo friend, the server returns `403`.

---

## Party relay

### `POST /api/public/mod/v1/relay/party`

**Requires mod auth.**

```json
{
  "toUuids": [
    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
  ],
  "text": "hello party"
}
```

The server accepts at most 20 recipient UUIDs.

Important: the server cannot independently see the caller's Hypixel party. The client supplies the recipient UUID list.

Response:

```json
{
  "ok": true,
  "recipients": 1
}
```

---

## Inbox

### `GET /api/public/mod/v1/relay/inbox`

**Requires mod auth.**

```json
{
  "messages": [
    {
      "from": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "fromUsername": "FriendName",
      "text": "hello :3",
      "scope": "dm",
      "at": 1786390000000
    }
  ]
}
```

This endpoint **drains** the inbox. A successful poll removes the returned messages from the server queue.

Do not call it from two independent consumers unless you intentionally accept that one may drain messages before the other sees them.

---

# 13. Staff encounter endpoints

## `POST /api/public/mod/v1/staff-encounters`

**Requires mod auth.**

```json
{
  "players": [
    {
      "uuid": "...",
      "username": "..."
    }
  ]
}
```

The server checks roles itself. Non-staff players are not trusted merely because a client reports them.

At most 128 reported players are considered.

Response:

```json
{
  "ok": true,
  "recorded": 1
}
```

## `GET /api/public/mod/v1/staff-encounters`

**Requires mod auth.**

Returns the verified caller's own recorded encounter list.

---

# 14. Useful authenticated read-only website API

The following read-only data endpoints are available to compatible authenticated mods through the same signed mod-authentication mechanism.

They are useful for player/profile and search features, but their response shapes may evolve as the project changes.

Current player routes include:

```text
GET  /api/public/mod/v1/player/:username
GET  /api/public/mod/v1/player/:username/inventory
GET  /api/public/mod/v1/player/:username/museum
GET  /api/public/mod/v1/player/:username/auctions
POST /api/public/mod/v1/player/:username/request-refresh
```

Current search routes include:

```text
GET /api/public/mod/v1/search/top
GET /api/public/mod/v1/search/suggest
```

If you build a fork around these responses, expect them to evolve with the website.

Do not use sky.melloo.me as a generic Hypixel API proxy for unrelated services.

Also available, same auth:

```text
GET /api/public/mod/v1/user/:publicId
GET /api/public/mod/v1/item-icon/:id
GET /api/public/mod/v1/avatar/:username
GET /api/public/mod/v1/skin/:username
GET /api/public/mod/v1/pet-icon/:type
GET /api/public/mod/v1/minion-icon/:type
GET /api/public/mod/v1/bestiary-icon/:type
GET /api/public/mod/v1/status/services
GET /api/public/mod/v1/status/incidents
GET /api/public/mod/v1/health
```

`user/:publicId` is a sky.melloo.me account's public profile (avatar, bio, roles, linked Minecraft accounts) - not a Minecraft player lookup, use the player routes above for that. The icon routes mirror what the website itself renders. `status`/`health` let a client check for a known API outage before assuming its own request failed.

---

# 15. Dungeon sync and replay

The mod-to-server live dungeon path is:

```text
POST /api/public/mod/v1/presence
        └── dungeonSync
```

The server uses that data for live state, run recording, map/replay data and the boss-room prototype.

The website's replay/history endpoints are intentionally tied to a logged-in website account and, in several cases, only allow the user to view their own linked Minecraft account. They are **not** general mod-auth endpoints.

Examples include:

```text
GET  /api/dungeon-status/:username
GET  /api/dungeon-runs/:username
GET  /api/dungeon-runs/:username/:runId
GET  /api/dungeon-boss-room/:username
```

Do not assume a mod-auth signature alone grants access to these personal-data routes.

Public share links are a separate token-based mechanism.

---

# 16. Practical integration examples

These examples are intentionally implementation-oriented. Exact class names and HTTP libraries are up to your mod.

For a complete, runnable reference, see [skymelloo-example-mod](https://github.com/hexedmaya/skymelloo-example-mod) - a working Fabric mod with the full signing handshake and a real API call, end to end.

## Example: startup flow

A typical compatible client launch can do:

```text
1. Generate ephemeral Ed25519 key pair.
2. GET /api/public/mod/v1/auth/challenge.
3. Complete Minecraft joinServer proof for serverId.
4. POST /api/public/mod/v1/auth/verify with username, UUID and public key.
5. Store serverTime offset and expiresAt in memory.
6. GET /api/public/mod/v1/settings.
7. POST /api/public/mod/v1/presence when appropriate.
8. Sign every protected request with a fresh nonce.
9. Re-authenticate after expiry or an authentication failure that indicates the session is gone.
```

Do not persist the ephemeral private key merely to avoid authenticating on a later launch.

## Example: cloud-save startup decision

```text
local = loadLocalSettings()
cloud = signed GET /api/public/mod/v1/settings

if cloud is 404:
    if local exists:
        signed POST /api/public/mod/v1/settings { settings: local.data }
else if local does not exist:
    saveLocally(cloud.settings, cloud.updatedAt)
else if cloud.updatedAt > local.updatedAt:
    saveLocally(cloud.settings, cloud.updatedAt)
else if local.updatedAt > cloud.updatedAt:
    signed POST /api/public/mod/v1/settings { settings: local.data }
else:
    // already synchronized
```

A real client should also protect against rapid local/cloud ping-pong and should not upload settings continuously on every frame or tiny UI event.

## Example: presence loop

Presence is intentionally short lived. A compatible client may periodically refresh it while connected:

```text
while authenticated and Minecraft session active:
    payload = {
        cosmetics: enabledSharedCosmetics,
        status: currentStatus,
        afk: isAfk,
        accountLinked: accountLinked,
        location: shortLocation,
        dungeonSync: userEnabledDungeonSync ? currentDungeonState : null
    }

    signed POST /api/public/mod/v1/presence payload

    wait appropriate presence interval
```

Do not infer that a ~20-second server TTL means you should send as many requests as possible. Refresh only often enough for the feature to work reliably.

## Example: querying nearby/known players

```http
POST /api/public/mod/v1/presence/query
Content-Type: application/json
<signed headers>

{
  "uuids": [
    "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    "11111111-2222-3333-4444-555555555555"
  ]
}
```

Use one batched request rather than one request per UUID.

## Example: friend request

```http
POST /api/public/mod/v1/friends/request
Content-Type: application/json
<signed headers>

{
  "username": "TargetPlayer"
}
```

Handle `pending`, `accepted`, `already_friends`, `self`, and `limit` as normal application states rather than treating every non-new friendship as an exceptional crash condition.

## Example: relay inbox polling

```text
messages = signed GET /api/public/mod/v1/relay/inbox

for message in messages:
    display(message)
```

Remember that reading the inbox drains it. Do not have multiple unrelated components poll the same inbox independently.

## Example: resilient request wrapper

```text
function apiRequest(request):
    try:
        response = send(request, timeout)

        if response.status == 429:
            scheduleRetryWithBackoffAndJitter()
            return RATE_LIMITED

        if response.status == 401:
            if modSessionExpiredOrInvalid:
                reauthenticateOnce()
            return AUTH_FAILED

        if response.status >= 500:
            failSoftly()
            return TEMPORARILY_UNAVAILABLE

        return response

    catch timeout/network error:
        failSoftly()
        return OFFLINE
```

A SkyMelloo-compatible feature should normally degrade gracefully when sky.melloo.me is unavailable. Losing the companion API should not crash Minecraft.

---

# 17. Error handling

Most JSON API failures use:

```json
{
  "error": "Human-readable message"
}
```

Common status codes:

| Status | Meaning |
|---:|---|
| `400` | Invalid/missing input |
| `401` | Missing/invalid authentication |
| `403` | Authenticated but not allowed |
| `404` | Resource/state does not exist |
| `413` | Payload too large |
| `429` | Rate limited |
| `500` | Unexpected server error |
| `503` | Upstream dependency temporarily unavailable |

Clients should use HTTP status codes for control flow and treat exact human-readable error strings as display text, not stable machine identifiers.

---

# 18. Compatibility rules for forks

If you fork or extend SkyMelloo/MellooEssentials:

1. **Do not remove request signing.**
   The server intentionally derives the acting UUID from verified authentication rather than trusting request bodies.

2. **Generate a fresh ephemeral key for a new client session.**

3. **Use fresh nonces for every signed request.**

4. **Hash the exact outgoing body bytes.**
   Re-serializing JSON after signing can produce a different byte sequence and invalidate the request.

5. **Keep clock correction.**
   Use `serverTime` from the auth flow to compensate for local clock drift.

6. **Identify SkyMelloo correctly.**
   Use `X-SkyMelloo-Client: mod` where the shared API needs the SkyMelloo namespace.

7. **Use the `/api/public/mod/v1/` base URL, not `/api/mod/`.**
   The latter is this project's own internal path and can change shape without notice - see the base URL note at the top of this document.

8. **Do not claim official-build status.**
   An independently compiled fork may authenticate and use compatible endpoints while still being reported as an unofficial/unverified build.

9. **Surface build-status information to the user.**
   If your client uses this API, it must implement and surface the build-status fields returned by `/api/public/mod/v1/version-check` (and the MellooEssentials equivalent) to the user - including showing the server-provided notice when a build is reported as unofficial/unverified. Stripping or suppressing this notice while continuing to use the API is a violation of these terms and may result in access being revoked.

10. **Respect user privacy controls.**
    Presence and detailed dungeon synchronization are user-controlled features. Do not silently enable or broaden what a fork sends.

11. **Avoid unnecessary polling.**
    Presence is intentionally frequent; player/profile data and other heavy endpoints are not.

12. **Treat read-API response shapes as evolvable.**
    `/api/public/mod/v1/player/*` and `/api/public/mod/v1/search/*` may gain or change fields as the project evolves; parse defensively.

---

# 19. Minimal signed-request pseudocode

```text
challenge = GET /api/public/mod/v1/auth/challenge

keyPair = generateEd25519KeyPair()

minecraftJoinServer(challenge.serverId)

POST /api/public/mod/v1/auth/verify {
    serverId: challenge.serverId,
    username: currentMinecraftUsername,
    uuid: currentMinecraftUuid,
    publicKey: base64(spkiDer(keyPair.publicKey))
}

function signedRequest(method, path, jsonBody):
    rawBody = jsonBody == null
        ? emptyBytes
        : utf8(serializeExactlyOnce(jsonBody))

    timestamp = correctedCurrentTimeMillis()
    nonce = secureRandomNonce()
    normalizedUuid = lowercase(removeDashes(currentMinecraftUuid))
    bodyHash = lowercaseHex(sha256(rawBody))

    message =
        normalizedUuid + "\n" +
        uppercase(method) + "\n" +
        pathWithoutQuery(path) + "\n" +
        timestamp + "\n" +
        nonce + "\n" +
        bodyHash

    signature = ed25519Sign(keyPair.privateKey, utf8(message))

    send method/path/rawBody with headers:
        X-SkyMelloo-UUID: currentMinecraftUuid
        X-SkyMelloo-Timestamp: timestamp
        X-SkyMelloo-Nonce: nonce
        X-SkyMelloo-Signature: base64(signature)
        X-SkyMelloo-Client: mod   // SkyMelloo namespace where relevant
```

---

# 20. API stability and developer responsibility

This is a hobby-project API provided without an SLA or uptime guarantee.

Documented behavior describes the current implementation. Endpoints, response fields, authentication details, limits, or availability may change as SkyMelloo and MellooEssentials evolve.

When practical, incompatible changes should be introduced carefully, but clients must still:

- tolerate unknown response fields;
- avoid assuming JSON object key order;
- handle missing optional fields;
- handle `4xx`, `5xx`, timeouts and invalid/partial responses;
- avoid destructive behavior when the API is unreachable;
- avoid retry storms;
- keep local user data safe if synchronization fails.

You are responsible for code you distribute that uses this API, including its request volume, privacy behavior, error handling, and compliance with the licenses/terms applicable to your own project.

Use of the API does not imply endorsement, partnership, or official status, and compatibility with third-party clients is not guaranteed.

---

## License / project status

SkyMelloo is an unofficial Hypixel SkyBlock project and is not approved by or associated with Mojang, Microsoft, or Hypixel Inc.

When redistributing or modifying SkyMelloo, follow the repository's license and attribution requirements.
