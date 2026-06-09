# ai-sidecar

Lightweight HTTP sidecar that exposes TripleA's ProAi engine to the map-room ai-bot-worker.

## Statelessness contract

**The sidecar is fully stateless.** Every `POST /decision` request carries the complete game state in its request body. A fresh `GameData` clone and a fresh `ProAi` instance are constructed per call — there is no session map, no in-memory game context that persists between requests. This means:

- Multiple sidecar instances can serve the same match interchangeably (shared-pool).
- `/sessions/*` routes return 404 with a JSON explanation — they do not exist.
- Determinism is controlled by the `seed` field in the request body, not by any server-side state.

## Endpoints

### `GET /health`

No authentication required. Returns the sidecar status, TripleA fork version, and git commit SHA baked in at build time.

```bash
curl http://localhost:8080/health
```

```json
{"status":"ok","version":"2.6.14960","commit":"a1b2c3d"}
```

Fields:
- `status` — always `"ok"` while the server is running.
- `version` — value of `LATEST` from `latest_version.properties` at build time, or `"unknown"` when not available.
- `commit` — short git SHA at build time, or `"unknown"` when not available.

### `POST /decision`

Requires `Authorization: Bearer <token>` (token from `SIDECAR_AUTH_TOKEN` env var, default `dev-token`).

Accepts a JSON body with a `kind` discriminator field. Returns a ready/error envelope.

**Purchase decision:**

```bash
curl -X POST http://localhost:8080/decision \
  -H "Authorization: Bearer dev-token" \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: my-request-id-123" \
  -d '{
    "kind": "purchase",
    "matchId": "bgio-match-abc",
    "state": {
      "territories": [],
      "players": [],
      "round": 1,
      "phase": "purchase",
      "currentPlayer": "Germans"
    },
    "seed": 42
  }'
```

```json
{"status":"ready","plan":{"kind":"purchase","purchases":[],"repairs":[],"warDeclarations":[]}}
```

**Noncombat-move decision:**

```bash
curl -X POST http://localhost:8080/decision \
  -H "Authorization: Bearer dev-token" \
  -H "Content-Type: application/json" \
  -d '{
    "kind": "noncombat-move",
    "matchId": "bgio-match-abc",
    "state": { "territories": [], "players": [], "round": 1, "phase": "noncombat", "currentPlayer": "Germans" },
    "seed": 42
  }'
```

**Response headers:**
- `X-Decision-Time-Ms` — elapsed time in milliseconds for the planning call. Present on all responses routed through `writeJsonTimed` (200, 501). Used by the 0.6 orchestrator to calibrate per-decision latency budgets.

**Error envelope (4xx / 5xx):**

```json
{"status":"error","error":"bad-request"}
```

Error codes: `bad-request`, `not-found`, `method-not-allowed`, `not-implemented`, `internal`.

### `POST /sessions/*` (and all `/sessions` sub-paths)

Returns 404 — the sidecar is stateless and has no sessions.

```bash
curl -X POST http://localhost:8080/sessions/reset \
  -H "Authorization: Bearer dev-token"
```

```json
{"status":"stateless","message":"This sidecar is fully stateless. POST /decision carries the entire game state; no session exists to reset or query."}
```

## Request-id propagation

Pass `X-Request-Id` on any `POST /decision` call. The value is bound to a thread-local and emitted on every `[AI-TRACE]` log line produced during that request:

```
[AI-TRACE] matchID=bgio-abc requestId=my-request-id-123 side=sidecar nation=Germans phase=purchase ...
```

This lets you correlate sidecar log lines with the ai-bot-worker request that triggered them across the distributed log stream.

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `SIDECAR_PORT` | `8080` | Listen port (`0` = OS-assigned, used in tests) |
| `SIDECAR_BIND_HOST` | `0.0.0.0` | Bind address |
| `SIDECAR_AUTH_TOKEN` | `dev-token` | Bearer token for `/decision` |
| `SIDECAR_DATA_DIR` | `data` | Path for any local data (currently unused at runtime) |
| `SIDECAR_LOG_LEVEL` | `INFO` | JUL log level: `FINE`, `INFO`, `WARNING`, `SEVERE` |

## Build

```bash
# From repo root
./gradlew :game-app:ai-sidecar:installDist

# Run
game-app/ai-sidecar/build/install/ai-sidecar/bin/ai-sidecar
```

The `version` and `commit` fields in `/health` are baked in by the Gradle `processResources` block at build time. Running directly from IDE sources (without a prior Gradle build) produces `"unknown"` for both.
