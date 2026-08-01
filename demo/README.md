# Live demo: judge-triggered, real Arm64 hardware, streamed live

A judge (or anyone) visits a web page, clicks "Start," and watches this
project's four self-verifying demo scripts (`./run before`, `./run after`,
`./run-nativekem.sh`, `run-ai.sh`) run for real, on a real Azure Arm64 VM
that starts on demand and deallocates itself afterward — not a recording,
not simulated output. The four stages are exactly the same scripts and
verification checks documented elsewhere in this repo
(`README.md`'s Status table, `MIGRATION.md`, `docs/bouncycastle-pqc-notes.md`,
`benchmarks/ai-inference-pqc/README.md`); this feature's only job is to run
them on demand and stream what they print to a browser, live, with nothing
paraphrased — the frontend's verdict panel only ever renders the literal
`VERIFIED:` / `VERIFICATION FAILED:` lines those scripts already print.

## Current state: built and locally tested; **not yet deployed**

Both halves of this feature are code-complete:

- **`demo/run-demo.sh`** (this directory) — the VM-side wrapper. Starts
  `llama-server`, builds the project once, runs the four demo scripts in
  order, and streams progress as JSON events over HTTPS.
- **`demo/worker/`** — a Cloudflare Worker (Durable Object + cron) that
  receives those events, runs the state machine (idle → starting → running
  → deallocating → done), enforces rate limits, and serves the frontend a
  judge actually watches. See [`demo/worker/README.md`](worker/README.md)
  for its architecture, HTTP API surface, and the ingest wire contract in
  full — that document is the source of truth for both halves, not
  re-explained here.

**Neither half has been exercised against real Azure or Cloudflare
infrastructure.** No Azure subscription, service principal, or Cloudflare
account exists in the environment either half was built in — that's not
an oversight, it's a hard boundary an automated agent can't cross. Getting
this from "code-complete" to "actually live" requires a human with real
credentials to work through
**[`demo/OWNER_SETUP.md`](OWNER_SETUP.md)** — the concrete, ordered
checklist for the Azure custom-role/service-principal setup, VM
preparation, and Cloudflare account/secrets/DNS work, plus what to test
first once it's deployed and the one specific failure mode
(`OWNER_SETUP.md` §4) most worth watching for on the first live run.

## Architecture, in one paragraph

The Worker cannot SSH into or hold a connection to the VM — Azure's ad-hoc
Run Command API is fire-and-forget and truncates its own response, so all
live output flows the other direction: the VM (`run-demo.sh`) makes
outbound HTTPS `POST` calls to the Worker's `/api/ingest` as it runs, and
the browser polls the Worker's `/api/status` and `/api/log`. Full detail —
the Durable Object state machine, the single re-arming alarm covering both
a hard time cap and a silence-based dead-man's-switch, the independent
cron backstop, the `systemd-run`/`setsid` dispatch requirement and why
plain `nohup ... & disown` isn't safe here — lives in
[`demo/worker/README.md`](worker/README.md) and inline in
[`demo/worker/src/orchestrator.ts`](worker/src/orchestrator.ts) and
[`demo/worker/src/azure.ts`](worker/src/azure.ts); this document doesn't
repeat it.

## What's actually been verified, and how

**`demo/run-demo.sh`** was run end-to-end against a local mock ingest
sink (a trivial Python `http.server`), with JDK 21 pinned via
`scripts/require-jdk21.sh`. Confirmed, from the mock sink's received event
log, in order:

- `stage_start` / `log_chunk` / `verification` / `stage_end` for
  `before`, `after`, and `nativekem` — all real, full JVM+TLS runs, all
  passing, with correct `VERIFIED:` text captured.
- The `ai` stage's **honest failure path**: with `llama-server` absent or
  unreachable, `run-ai.sh`'s own health check fails cleanly, `exit_code=1`
  and `status=failed` are posted correctly, and the wrapper does not hang
  or crash — it still proceeds to a final `done` event, since one stage
  failing isn't treated as catastrophic (see "Design decisions" below).
- The `ai` stage's **success path**, opportunistically: a real
  `llama-server` happened to already be running locally during testing,
  producing a real model reply, a passing HelloRetryRequest verification,
  and a correctly-parsed `done.summary` (`handshake_ms`,
  `request_to_response_ms`, `ratio`).
- The **catastrophic build-abort path**: with compilation deliberately
  broken, the wrapper aborts immediately with a single top-level `failed`
  event and reason — no stage events at all — then behaves correctly again
  once the break is reverted.

**A real bug was found and fixed during this verification**, not just
theorized: `scripts/require-jdk21.sh` runs `set -euo pipefail` itself
(correct for the four demo scripts it's designed to be sourced by), but
`run-demo.sh` sources it directly into its own shell to pin JDK 21 — which
silently turned on `errexit` in the wrapper too. Under that, any stage
that legitimately failed (e.g. `run-ai.sh` exiting 1 on an unhealthy
`llama-server`) aborted the *entire* wrapper via `wait` returning
non-zero, before it could even record the failure — exactly the
"crash instead of a clean per-stage failure" problem this design is
supposed to prevent. Fixed with an explicit `set +e; set -uo pipefail`
immediately after the `source` line (see the comment at that line in
`run-demo.sh` for the full explanation); re-verified against all four
paths above after the fix.

**`demo/worker`** was run locally via `wrangler dev` (Miniflare's local
Durable Object/KV simulation) with placeholder `.dev.vars`, exercised over
real HTTP with `curl`: static asset serving, `/api/status`, `/api/start`
(including a real, successful call to Cloudflare's public Turnstile
`siteverify` endpoint using their documented always-pass test
credentials), spectator-mode attach-to-existing-session behavior, and
`/api/ingest`'s real bearer-token/session-id checks (401/409 on bad
credentials, then a full accepted event sequence, confirmed via
`/api/log`). `tsc --noEmit` and `npm test` (34 Vitest tests across
`util`/`turnstile`/`state-logic`/`azure`, the latter two using an
injectable fake `fetch`) both pass. Full detail:
[`demo/worker/README.md`](worker/README.md#whats-actually-been-verified-vs-not).

## What is not yet verified

Stated plainly, not rounded up to "done":

- **No real Azure ARM/AAD call has ever succeeded** — no credentials exist
  in either build environment. `fetchAadToken`/`startVm`/`deallocateVm`/
  `getInstanceViewPowerState` are unit-tested with a fake `fetch`
  (`demo/worker/test/azure.test.ts`) and were exercised against
  placeholder-credential *failure* responses during local `wrangler dev`
  testing (real 404s from AAD, handled gracefully, no crash) — but never
  against a real subscription.
- **The Run Command `systemd-run`/`setsid` dispatch has never been
  exercised against a real Azure guest agent.** This is called out
  explicitly, in both `src/azure.ts`'s own comment and
  `OWNER_SETUP.md` §4, as the single highest-risk untested detail in the
  whole design — it exists to avoid a specific known failure mode (a
  plain `nohup ... & disown` spawn getting silently reaped when the Run
  Command's own top-level script returns), but whether the guard actually
  works has only been reasoned about, never observed.
- **The DO's alarm has never fired on a real 30–90s+ timer.** Miniflare
  doesn't auto-fire Durable Object alarms without manually advancing
  simulated time, which wasn't set up. The alarm's decision logic
  (`decideAlarmAction`) is unit-tested in isolation — 11 passing cases
  covering the hard-cap, silence-timeout, idle, and deallocating branches
  — but never observed running on a real clock against a real DO.
- **The cron dead-man's-switch (`scheduled()` in `worker.ts`) has never
  fired for real.** Miniflare logs that it doesn't auto-trigger scheduled
  handlers, and `wrangler dev --test-scheduled` wasn't additionally run.
  Its sub-pieces (`fetchAadToken`, `getInstanceViewPowerState`,
  `deallocateVm`) are the same functions covered by the Azure unit tests
  above, but the trigger path itself is unverified.
- **No real Turnstile widget has ever rendered or been solved by a human
  in a browser.** Only the server-side `siteverify` call was exercised,
  against Cloudflare's documented test credentials — never the actual
  client-side widget on the actual frontend HTML.
- **`run-demo.sh`'s own `llama-server`-startup code path is not fully
  exercised.** No GGUF model was downloaded in the environment
  `run-demo.sh` was built and tested in, so the "start `llama-server`,
  wait for `/health`, grep the verbose log for KleidiAI engagement"
  sequence was verified only via (a) the "not configured, skip cleanly"
  path and (b) an opportunistic real success against a `llama-server`
  that happened to already be running locally for an unrelated reason —
  not via this script's own code actually launching a fresh
  `llama-server` process itself with `LLAMA_SERVER_BIN`/`LLAMA_MODEL_PATH`
  set. This matches what the work was scoped to accept going in, but it's
  still a real, specific gap between "tested" and "will work identically
  on the target VM" — `OWNER_SETUP.md` §2.5 asks the owner to close this
  gap by hand, once, before the first live demo.

None of the above blocks getting this deployed — they're exactly the set
of things that categorically cannot be verified without real Azure/
Cloudflare credentials and a real target VM, which is why
`OWNER_SETUP.md` exists as a separate, explicit handoff rather than this
being silently assumed to work.

## Design decisions worth knowing before changing anything here

- **The four stages are independent claims.** One stage failing (e.g. no
  `llama-server` configured) does not abort the run or the other three
  stages, and does not itself trigger a top-level `failed` event — it's
  recorded as a per-stage `stage_end{status:"failed"}` instead. `failed`
  is reserved for the one case where nothing downstream can possibly
  succeed: the shared `mvn package` step itself failing, or the wrapper
  crashing unexpectedly.
- **The ingest wire contract lives in `demo/worker/src/types.ts` and
  `demo/worker/src/orchestrator.ts`, not here.** The Worker side landed
  the contract first in this project's build process; `run-demo.sh`
  conforms to it rather than the other way around. If you need to change
  the event shape, change it there and re-read
  `demo/worker/README.md`'s "INGEST CONTRACT" section, which says
  explicitly not to let the two sides silently diverge.
- **JDK 21, not 25** — see `docs/bouncycastle-pqc-notes.md` for the
  documented JDK 25+ BCJSSE regression this project works around
  everywhere, including here.
