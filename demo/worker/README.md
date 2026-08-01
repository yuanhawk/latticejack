# Latticejack demo orchestrator (Cloudflare Worker)

Receives a hackathon judge's "Start" click, starts a real Azure VM, dispatches
a wrapper script on it via Azure's ad-hoc Run Command API, and receives that
script's streamed output over `POST /api/ingest` (outbound HTTPS from the VM -
this Worker cannot SSH into or hold a connection to the VM). The frontend in
`public/` polls `/api/status` and `/api/log` to render the run live.

See the architecture notes inline in `wrangler.toml`, `src/orchestrator.ts`,
and `src/azure.ts` - the state machine, the single-alarm design, the
`systemd-run`/`setsid` Run Command dispatch requirement, and the Azure REST
endpoints used are all documented at their point of use.

## INGEST CONTRACT (read this if you're touching `demo/run-demo.sh`)

`demo/run-demo.sh` (built separately from this Worker) is expected to `POST`
one JSON object per event to this Worker's `/api/ingest`, with:

```
Authorization: Bearer <SESSION_TOKEN>
Content-Type: application/json
```

`<SESSION_ID>` and `<SESSION_TOKEN>` are the two arguments the Run Command
dispatch passes as `argv[1]`/`argv[2]` to the wrapper script (see
`composeRunCommandScript` in `src/azure.ts`). Every event body must include:

```jsonc
{
  "session_id": "<SESSION_ID>",   // must match the currently active session
  "seq": 0,                        // per-session monotonic counter, starting at 0
  // ...then one of the shapes below, selected by "type"
}
```

Event types (`type` field), see `src/types.ts` for the exact TS shapes:

| type           | required fields                          | effect |
|----------------|-------------------------------------------|--------|
| `stage_start`  | `stage` (`before`\|`after`\|`nativekem`\|`ai`) | marks that stage "running", appends a `=== [stage] starting ===` line to the log |
| `stage_end`    | `stage`, `status` (`done`\|`failed`), `exit_code`? | marks that stage done/failed, appends a log line |
| `log_chunk`    | `text` (raw string, may contain `\n`), `stage`? | appended verbatim to the live log |
| `verification` | `text` - **must be the literal line** the script printed, i.e. starting with `VERIFIED:` or `VERIFICATION FAILED:` | appended to the log AND recorded separately as a "verdict" (`/api/log`'s `verdicts` array) - the frontend's verdict panel only ever renders these literal strings, never a paraphrase |
| `done`         | `summary`? `{ handshake_ms, request_to_response_ms, ratio }` | ends the run successfully, triggers VM deallocation. The number shapes match the log line format `PqcAiTlsClient` already prints (see its `TIMING` block) - if `run-demo.sh` scrapes those from the `ai` stage's stdout, this is where to put them |
| `failed`       | `reason` (string), `stage`?               | ends the run as a failure, triggers VM deallocation, `reason` is shown verbatim in the frontend's failure state |

**This shape was authored here because `demo/run-demo.sh` did not exist yet at
the time this Worker was written.** If the wrapper script now sends something
different, reconcile the two - either adjust `run-demo.sh` to match this
contract, or update `src/types.ts` + the `switch` in
`DemoOrchestrator.handleIngest` (in `src/orchestrator.ts`) to match
`run-demo.sh`, and update this table. Don't let them silently diverge.

Design choices worth knowing about, both documented inline where they apply:

- `seq` is accepted but **not currently enforced for ordering** - events are
  appended to the log in arrival order, since HTTP POSTs from the VM aren't
  guaranteed to arrive in order under retry. It exists for future
  gap-detection/diagnostics.
- The live log is a single growing string in DO storage, ring-buffer-truncated
  at ~500KB from the front (see `truncateLog` in `src/util.ts`) - plenty for a
  ~12-minute-capped run. `/api/log?after=<offset>` offsets are `String.length`
  positions into that (possibly-already-truncated) buffer, not an absolute
  all-time byte counter.
- The session bearer token is **never sent to the browser** - it's minted in
  `POST /api/start`, embedded directly into the Run Command script the DO
  sends to Azure, and never appears in any response to the judge's browser.
  The browser only ever sees the `session_id`, which is what `/api/log` is
  keyed on.

## HTTP API surface

- `GET /api/status` - `{ state, active, sessionId, vmPowerState, vmPowerStateCheckedAt }`
- `POST /api/start` - body `{ turnstileToken }`; `{ mode: "started"|"spectator", sessionId, state }` on success, or a structured `{ error, ... }` (`turnstile_failed`, `daily_cap_reached`, `rate_limited`) with a `429`/`400` status
- `GET /api/log?session=<id>&after=<offset>` - `{ state, stages, log, nextOffset, verdicts, outcome, failureReason, doneSummary, vmPowerState }`
- `POST /api/ingest` - see INGEST CONTRACT above; bearer-token-authenticated
- everything else - static assets from `public/`

## Local development / testing

```sh
npm install
cp .dev.vars.example .dev.vars   # fill in placeholder secrets for local testing
npm run typecheck
npm test                          # vitest - pure logic + azure/turnstile modules with a fake fetch
npm run dev                       # wrangler dev
```

`wrangler dev` starts the Worker locally (with a local Durable Object + KV
simulation via Miniflare) using whatever's in `.dev.vars`. **No real Azure or
Turnstile calls are made by these commands** - `.dev.vars` should contain
harmless placeholder values, and any code path that actually calls Azure/AAD
will simply fail with a network/auth error if exercised against real bindings
locally, which is expected until this is deployed with real secrets.

### What's actually been verified vs. not

See the top-level task's structured report for the current, honest state of
`wrangler dev` startup verification and `npm test` coverage. In short: pure
logic (state transitions, cap checks, Azure/Turnstile HTTP call shapes with a
fake `fetch`) is unit-tested; the Durable Object's storage/alarm behavior and
the cron trigger are exercised only insofar as `wrangler dev`'s local
Miniflare simulation exercises them - real Azure calls, a real Turnstile
widget, and a real 12-minute/3-minute alarm cycle are **not** verified here
and can't be until deployed with real credentials.

## Deployment (not done by this task - see wrangler.toml's inline TODO)

Fill in every `<<FILL_IN_...>>` placeholder in `wrangler.toml` and
`public/index.html`, create the KV namespace, set the two secrets
(`AZURE_CLIENT_SECRET`, `TURNSTILE_SECRET_KEY`) with `wrangler secret put`,
then `wrangler deploy`.
