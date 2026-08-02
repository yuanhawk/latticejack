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

## Current state: deployed and live at latticejack.itinerario.io — end-to-end success confirmed on real infrastructure

Both halves of this feature are built, deployed, and have each been
individually proven against real infrastructure (Azure and Cloudflare, not
mocks/local substitutes) — see "What's actually been verified, and how"
below for the full account, including two real bugs found and fixed doing
this for real rather than by re-reading the design. The short version:

- **`demo/run-demo.sh`** (this directory) — the VM-side wrapper — has run
  to full completion on the real Azure Cobalt 100 VM, all four stages
  passing, including a real AI reply through the PQC-encrypted channel.
- **`demo/worker/`** — the Cloudflare Worker (Durable Object + cron) — is
  deployed live at `latticejack.itinerario.io`, and its Durable Object
  state machine, alarm-based retry loop, real AAD token/VM start/VM
  deallocate calls, and Turnstile-gated `/api/start` have all been
  observed working against real Azure and Cloudflare infrastructure, not
  simulated.
- The one nuance worth stating precisely rather than rounding up: every
  fully-successful run observed so far involved a manual mid-flight fix
  (a stale VM-side env file) and a manual re-dispatch of `run-demo.sh`
  with the same session id/token Azure's real Run Command dispatch had
  already minted — not a single, fully unassisted click-to-success run.
  Both halves of that gap are independently proven (the real Run Command
  dispatch mechanism *does* successfully launch the script — confirmed via
  the VM's own systemd journal for the first, pre-fix attempt; the script
  itself, once launched, *does* run correctly to full success — confirmed
  via the fixed second attempt) but a genuinely clean one-click run from a
  cold `failed`/`idle` state hasn't been separately observed yet. Worth
  doing once before relying on this for judges.

See **[`demo/OWNER_SETUP.md`](OWNER_SETUP.md)** for the concrete setup
checklist this deployment followed (Azure custom-role/service-principal
setup, VM preparation, Cloudflare account/secrets/DNS work), kept up to
date as the source of truth for how to reproduce or redeploy this.

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

**A second real bug was found and fixed doing owner setup by hand**, not
just by reading the checklist: `OWNER_SETUP.md`'s original §2.6 (and
`wrangler.toml`'s default `DEMO_VM_SCRIPT_PATH`) said to deploy
`run-demo.sh` as a standalone copy at `/opt/latticejack-demo/run-demo.sh`
— but the script derives its own repo root as `"$(dirname "$0")/.."` to
`cd` there before running `./run-before.sh` etc., so a bare copy with no
checkout above it would have every stage fail immediately (`REPO_ROOT`
resolving to `/opt`, which has none of those scripts). Fixed in both
`OWNER_SETUP.md` and `wrangler.toml`'s default: the deployed path is now
the script's real location *inside* the pinned checkout
(`/opt/latticejack-demo-src/demo/run-demo.sh`), not a copy elsewhere.
Also added: `run-demo.sh` now sources a fixed `/etc/latticejack-demo.env`
file at startup if present (a no-op, unchanged-behavior fallback if it
isn't — confirmed against the local mock-sink tests above, none of which
create that file) — needed because Azure Run Command invokes the script
directly with no login shell in between, so `~/.bashrc`/`/etc/environment`
never reach it.

**Then run for real, on the actual Azure Cobalt 100 VM** (not a mock/local
substitute): a fresh, pinned-commit checkout at `/opt/latticejack-demo-src`
(the repo is public now, so a plain HTTPS clone works, no deploy key
needed), `libmlkem768ffm.so` rebuilt against it, `run-nativekem.sh`
re-verified passing on that fresh checkout, KleidiAI engagement
independently re-confirmed on this exact hardware (`nm` symbols, and a
manual `llama-server --verbose` run — which also surfaced and resolved a
false alarm: llama-server logs a dry-run sizing pass showing `0.00 MiB`
for every buffer *before* the real `mmap` load pass that shows the actual
`504.01 MiB` — `run-demo.sh`'s own verification grep already correctly
uses `tail -1` to read the real pass, not the dry-run; a naive first-match
grep would have false-negatived here). Then `run-demo.sh` itself was
invoked directly with a minimal, Run-Command-*like* environment (`env -i`
with only `PATH`/`HOME`/`INGEST_URL` set — everything else sourced from
`/etc/latticejack-demo.env`, exactly as a real deployment would) against a
mock ingest sink running on the VM itself. **All four stages passed**,
including a real, complete `ai` stage run: `handshake_ms=299.8`,
`request_to_response_ms=2973.5`, `ratio=0.101` (~9.9x — consistent with
the ~9.4x average already on record in
[`benchmarks/ai-inference-pqc/README.md`](../benchmarks/ai-inference-pqc/README.md)),
with the real KleidiAI verification event text confirmed correct
(`CPU_KLEIDIAI model buffer size =   504.01 MiB`, not a placeholder or
false positive). VM deallocated and confirmed afterward, per this
project's standing cost-control policy.

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

### Then deployed live and run for real, against real Azure and real Cloudflare

`wrangler deploy` shipped the Worker to `latticejack.itinerario.io` (a
zone already on this Cloudflare account's nameservers), with a real
SQLite-backed Durable Object, a real KV namespace, real Worker secrets
(`AZURE_CLIENT_SECRET`, `TURNSTILE_SECRET_KEY`), and a real Turnstile
widget registered for that domain — none of it simulated. One correction
made along the way: an earlier version of `OWNER_SETUP.md` claimed
Durable Objects require a Workers Paid plan; checked directly against
Cloudflare's current pricing docs, that's not accurate for a
SQLite-backed DO (`new_sqlite_classes`, which is what this project already
uses) — those are available on the Workers Free plan, so no billing
upgrade was needed.

The first real click-to-start attempt surfaced a real, live failure:
`fetchAadToken` got a `401` from AAD. Root-caused as a stale/mismatched
`AZURE_CLIENT_SECRET` — fixed by resetting the app registration's
credential and re-uploading it. That surfaced a second, subtler thing:
Azure app-registration secret resets are not instant — a freshly reset
secret was rejected by AAD's own token endpoint for several minutes even
when tested directly with `curl`, bypassing the Worker entirely, before
starting to work. (Resetting again mid-wait just restarts that clock —
worth knowing if this ever needs debugging again.) Once a reset secret
was left untouched for ~15–20 minutes, the Durable Object's own alarm
(observed firing for real, every 45s, via `wrangler tail`) retried the
stuck `deallocating` state on its own and reached a clean terminal
`failed`, confirming the whole alarm-retry/cleanup loop works
unattended, not just in the `decideAlarmAction` unit tests.

The next real attempt got further — real AAD token, real `startVm`, VM
reached `PowerState/running`, and the real `dispatchRunCommand` call
succeeded (confirmed independently via the VM's own `systemd journal`,
not just the Worker's optimistic 202-Accepted) — but zero `/api/ingest`
events ever arrived, and the session timed out on the Worker's own
3-minute silence dead-man's-switch. SSH'd into the VM (while it was still
up, before the Worker's cron/alarm could deallocate it) and found the
real cause: `/etc/latticejack-demo.env` was missing `INGEST_URL` entirely
— a leftover from before `OWNER_SETUP.md` §2.6's current heredoc was
finalized, never refreshed since. `run-demo.sh` checks for `INGEST_URL`
and exits before doing anything else if it's unset, which is exactly
consistent with the observed total silence (it never even got far enough
to POST its own `failed` event, since posting also needs `INGEST_URL`).
Fixed the file on the VM directly, then — rather than spend a whole new
VM billing cycle — re-dispatched `run-demo.sh` by hand over SSH with the
same session id/token the real Run Command dispatch had already minted
(visible in the systemd journal, since Run Command surfaces argv there),
letting the already-running Worker session pick up genuine progress.

**Result: full success.** All four stages (`before`, `after`, `nativekem`,
`ai`) ran and passed for real, streamed live through the deployed Worker,
polled from `/api/status`/`/api/log` exactly as a judge's browser would.
A real model reply came back through the PQC-encrypted channel
(`handshake_ms=296.8, request_to_response_ms=3043, ratio=0.098`, ~10.3x —
consistent with prior real-hardware numbers), the HelloRetryRequest
check confirmed real X25519MLKEM768 negotiation, and the VM deallocated
itself cleanly afterward (`vmPowerState: PowerState/deallocated`,
confirmed via the Worker's own status endpoint, not just assumed).

## What is not yet verified

Stated plainly, not rounded up to "done". Most of this list is now
resolved — see "Then deployed live and run for real" above for how each
item below was actually settled, not just re-reasoned about:

- **Resolved: real Azure ARM/AAD calls.** `fetchAadToken`, `startVm`,
  `getInstanceViewPowerState`, and `deallocateVm` have all now succeeded
  for real against the live subscription — a VM was genuinely started,
  polled to `PowerState/running`, and later confirmed
  `PowerState/deallocated`, all through these exact functions running in
  the deployed Worker, not the unit-tested fake-`fetch` path.
- **Resolved: the Run Command dispatch mechanism.** Confirmed via the
  VM's own `systemd journal`, independent of the Worker's own optimistic
  202-Accepted response, that a real `dispatchRunCommand` call really did
  launch `run-demo.sh` via `systemd-run --scope` on the real guest agent.
  One nuance still open: the specific run that dispatch launched failed
  early (the `INGEST_URL` bug, since fixed) — so no single run has yet
  been *both* triggered by a real fresh Run Command dispatch *and* run to
  full success in one unassisted shot. Each half is independently proven;
  the combination is likely but not yet separately observed.
- **Resolved: the DO's alarm firing on a real timer.** Watched fire
  repeatedly for real, every 45s, via `wrangler tail` — including
  correctly retrying a stuck `deallocating` state across multiple ticks
  until an Azure-side condition (a propagating credential) cleared, then
  reaching a clean terminal state on its own, unattended.
- **Resolved: a real Turnstile widget, rendered and solved by a human in
  a real browser.** Multiple real sessions were created via `/api/start`
  during this verification, which is only possible if `verifyTurnstile`
  accepted a real, human-solved token from the real widget — not just the
  server-side `siteverify` call against Cloudflare's test credentials.
- **Resolved (already noted previously): `run-demo.sh`'s own
  `llama-server`-startup code path.** Confirmed again on this deployment:
  a fresh `llama-server` process started for real from
  `LLAMA_SERVER_BIN`/`LLAMA_MODEL_PATH`, KleidiAI engagement confirmed via
  a real model reply, not a placeholder.
- **Still open: the cron dead-man's-switch (`scheduled()` in
  `worker.ts`) has never fired for real.** Its sub-pieces
  (`fetchAadToken`, `getInstanceViewPowerState`, `deallocateVm`) are the
  same functions now proven working via the alarm path above, but the
  15-minute cron trigger itself hasn't been separately observed firing.
  Low risk (the alarm is the primary mechanism; cron is a backstop for if
  the alarm itself is broken) but genuinely unobserved.
- **Still open: a single, fully unassisted click-to-success run.** As
  described above, every fully-successful run so far involved a manual
  mid-flight env-file fix and a manual re-dispatch. Worth running once
  more, cleanly, before relying on this for judges — see the note at the
  top of this file.

None of the above blocks this being genuinely live and working — the
feature is deployed and has demonstrably worked end to end. The two
remaining open items are narrow and low-risk (a 15-minute cron backstop,
and one more clean unassisted run to fully close the loop), not gaps in
whether the core design works.

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
