# Owner setup: what a human has to do before this demo can run live

The live-demo feature (this directory + `demo/worker/`) is code-complete and
locally tested as far as that's possible without real credentials — see
[`demo/README.md`](README.md) for what "tested" means precisely and what
isn't. **Nothing in this checklist can be done by an agent working in this
repo**: it all requires an Azure subscription, a Cloudflare account, and
credentials that don't exist in this environment. This is the concrete,
ordered list of what you (the owner) need to do, and why each step is
shaped the way it is.

Work through the sections in order — each one depends on state from the
one before it (the VM must exist before you can scope a role to it; the
Worker must be deployed before you have a domain to test ingest against).

---

## 1. Azure: service principal with a custom, VM-scoped role

**Do not use a built-in role like Contributor, and do not scope it to the
resource group or subscription.** The Worker's `src/azure.ts` only ever
calls four VM-specific operations against one named VM
(`latticejack-arm64-runner`, per `docs/arm64-instance-setup.md`'s Azure
setup) — the credential embedded in the Worker should not be able to do
anything else in your Azure account. If this credential leaks (Worker
secret exposure, log leakage, a bug), the blast radius should be "start,
stop, and run a command on one specific VM," not "manage every resource in
the resource group."

1. **Create a custom role definition** scoped to exactly this VM's
   resource ID, with exactly these five permissions — no more:

   | Permission | Why the Worker needs it |
   |---|---|
   | `Microsoft.Compute/virtualMachines/start/action` | `POST .../start` — `startVm()` in `src/azure.ts`, called when a judge clicks "Start." |
   | `Microsoft.Compute/virtualMachines/deallocate/action` | `POST .../deallocate` — `deallocateVm()`, called on run completion/failure, by the alarm's hard-cap/silence-timeout paths, and by the independent cron dead-man's-switch. This is the permission that actually controls your Azure bill — without it, a stuck VM keeps running (and billing) with no way for the Worker to stop it. |
   | `Microsoft.Compute/virtualMachines/runCommand/action` | `POST .../runCommand` — `dispatchRunCommand()`, the only way the Worker gets `demo/run-demo.sh` running on the VM (see §5 below; there is no SSH from the Worker). |
   | `Microsoft.Compute/virtualMachines/instanceView/read` | `GET .../instanceView` — `getInstanceViewPowerState()`, polled by both the DO's alarm and the cron job to read the literal `PowerState/*` code rather than inferring VM state from anything else. |
   | `Microsoft.Compute/virtualMachines/read` | Baseline read access some of the above calls need to resolve the VM resource itself; without it the other calls can 403 even though they're separately granted. |

   Example (adjust IDs):
   ```bash
   az role definition create --role-definition '{
     "Name": "latticejack-demo-vm-operator",
     "Description": "Start/stop/runCommand/read on exactly one VM: latticejack-arm64-runner",
     "Actions": [
       "Microsoft.Compute/virtualMachines/start/action",
       "Microsoft.Compute/virtualMachines/deallocate/action",
       "Microsoft.Compute/virtualMachines/runCommand/action",
       "Microsoft.Compute/virtualMachines/instanceView/read",
       "Microsoft.Compute/virtualMachines/read"
     ],
     "AssignableScopes": [
       "/subscriptions/<SUBSCRIPTION_ID>/resourceGroups/<RESOURCE_GROUP>/providers/Microsoft.Compute/virtualMachines/latticejack-arm64-runner"
     ]
   }'
   ```

2. **Create an app registration / service principal** for the Worker to
   authenticate as (client-credentials OAuth2 flow — this is exactly what
   `fetchAadToken()` in `src/azure.ts` performs against
   `https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token`):
   ```bash
   az ad sp create-for-rbac --name latticejack-demo-worker --skip-assignment
   ```
   Record the output `appId` (→ `AZURE_CLIENT_ID`), `password` (→
   `AZURE_CLIENT_SECRET` — shown once, store it now), and `tenant` (→
   `AZURE_TENANT_ID`).

3. **Assign the custom role to that service principal, scoped to the VM
   only:**
   ```bash
   az role assignment create \
     --assignee <appId> \
     --role "latticejack-demo-vm-operator" \
     --scope "/subscriptions/<SUBSCRIPTION_ID>/resourceGroups/<RESOURCE_GROUP>/providers/Microsoft.Compute/virtualMachines/latticejack-arm64-runner"
   ```

4. **Verify the scope is actually narrow**, not just declared narrow —
   confirm the assignment's `scope` field is the full VM resource ID, not
   the resource group:
   ```bash
   az role assignment list --assignee <appId> --output table
   ```

---

## 2. One-time VM preparation

This is all on the `latticejack-arm64-runner` VM itself
(`docs/arm64-instance-setup.md` §"Option C" documents how that VM was
originally provisioned — Azure Cobalt 100, `Standard_D2pls_v6`, Ubuntu
24.04 aarch64). Do this once; the VM is started/stopped per-demo-run after
this, not re-provisioned each time.

1. **Install JDK 21 specifically — not JDK 25.** This project has a
   documented JDK 25+ BCJSSE regression (`scripts/require-jdk21.sh`,
   `docs/bouncycastle-pqc-notes.md`); every demo script pins JDK 21 via
   that script, but it can only pin a JDK that's actually installed.
   ```bash
   sudo apt update && sudo apt install -y openjdk-21-jdk maven git
   java -version   # confirm 21, and aarch64 not a cross-compiled shim
   ```
   If `scripts/require-jdk21.sh`'s candidate-path list doesn't find your
   distro's install location, extend that script's candidate list rather
   than working around it — see `docs/arm64-instance-setup.md`'s note on
   this.

2. **Check out the repo at a pinned commit** (not `main` floating —
   the demo should run exactly what was tested, not whatever landed since):
   ```bash
   git clone <this-repo-url> /opt/latticejack-demo-src   # or wherever; run-demo.sh derives REPO_ROOT from its own script location
   cd /opt/latticejack-demo-src
   git checkout <PINNED_COMMIT>
   ```
   Record which commit you pinned somewhere you'll remember — if the demo
   misbehaves later, "what commit is actually on the VM" is the first
   thing to check.

3. **Warm `~/.m2` and `target/`** so the demo's own `mvn package` (run
   fresh at the start of every session by `demo/run-demo.sh`, see its
   "Step 3") isn't paying first-download cost live in front of a judge:
   ```bash
   cd /opt/latticejack-demo-src
   source scripts/require-jdk21.sh
   mvn -q -DskipTests package
   mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath -Dmdep.outputFile=target/classpath.txt
   ```
   Re-running this after any dependency change keeps the warm cache
   current; `run-demo.sh` always re-runs `mvn package` itself at session
   start regardless (that's by design — it's the one step whose failure
   aborts the whole run with a top-level `failed` event — warming the
   cache just makes that step fast, not skippable).

4. **Build `libmlkem768ffm.so`** for the native-KEM stage
   (`run-nativekem.sh`, `src/main/java/com/latticejack/pqc/nativekem/`).
   Follow the build recipe in
   [`benchmarks/mlkem-ffm-bench/README.md`](../benchmarks/mlkem-ffm-bench/README.md#how-the-shared-library-was-built)
   exactly (the Linux `gcc -shared ... --whole-archive` steps) — that
   recipe produces both the `PQCP_MLKEM_NATIVE_MLKEM768_keypair`/`enc`/`dec`
   symbols and the `_derand` variants
   (`NativeMlkem768.java`'s comments confirm it's the same prebuilt
   binary; the RNG fix documented in
   `docs/regulated-deployment-guide.md` changed which entry points the
   *Java* code calls, not how the shared library itself is built). The
   result must land at exactly:
   ```
   /opt/latticejack-demo-src/vendor/mlkem-native/libmlkem768ffm.so
   ```
   (`run-nativekem.sh` derives this path as
   `$(pwd)/vendor/mlkem-native/libmlkem768ffm.so` and fails its own
   pre-check with a clear error if it's missing — verify by actually
   running `./run-nativekem.sh` once by hand on the VM, not just by
   confirming the file exists.)

5. **Build llama.cpp with the KleidiAI CPU backend, and get the model.**
   Follow [`component-ai/README.md`](../component-ai/README.md) §1–2
   exactly, on this VM (a fresh native build — the KleidiAI kernel
   selection is hardware-specific, confirmed to differ between the
   project's Mac prototype and its Azure build; don't copy a binary from
   another machine). In short:
   ```bash
   cd /opt/latticejack-demo-src/component-ai
   git clone --depth 1 https://github.com/ggml-org/llama.cpp.git
   cd llama.cpp
   cmake -B build -G Ninja -DGGML_CPU_KLEIDIAI=ON -DGGML_METAL=OFF \
     -DGGML_BLAS=OFF -DLLAMA_CURL=OFF -DCMAKE_BUILD_TYPE=Release
   cmake --build build -j "$(nproc)" --target llama-server llama-cli
   ```
   Then download the model (Q4_0 specifically — KleidiAI's kernels only
   cover `Q4_0`/`Q8_0`, not K-quants):
   ```bash
   curl -L -o Llama-3.2-1B-Instruct-Q4_0.gguf \
     https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_0.gguf
   ```
   **Verify KleidiAI actually engaged** — don't trust a clean exit code.
   Start `llama-server --verbose` once by hand and grep its log for both
   `KLEIDIAI = 1` and a non-zero `CPU_KLEIDIAI model buffer size` line
   (the exact two signals `demo/run-demo.sh` itself checks automatically
   on every run — see `benchmarks/ai-inference-pqc/README.md`
   "KleidiAI verification evidence" for what a false-positive looks like
   and why `--verbose` specifically is required to see it).

   Then set the two environment variables `demo/run-demo.sh` reads to
   find these (see its header comment for the exact names):
   ```bash
   # e.g. in the systemd unit / shell profile that runs demo/run-demo.sh
   export LLAMA_SERVER_BIN=/opt/latticejack-demo-src/component-ai/llama.cpp/build/bin/llama-server
   export LLAMA_MODEL_PATH=/opt/latticejack-demo-src/component-ai/llama.cpp/Llama-3.2-1B-Instruct-Q4_0.gguf
   ```
   If these are left unset, the "ai" stage doesn't crash the demo — it
   fails its own health check honestly and the other three stages still
   run and report correctly (verified behavior, see `demo/README.md`) —
   but you obviously want the AI stage actually working for a live demo,
   so don't skip this.

6. **Do NOT copy `run-demo.sh` to a standalone location — use it in place,
   inside the pinned checkout from step 2.** An earlier version of this
   checklist (and of `wrangler.toml`'s default) said to `cp` the script to
   a bare `/opt/latticejack-demo/run-demo.sh` with nothing above it — that
   is wrong and was caught by actually hand-deploying this: `run-demo.sh`
   derives its own repo root as `"$(dirname "$0")/.."` so it can `cd`
   there and run `./run-before.sh` etc. relative to it. Deployed as a
   standalone copy outside any checkout, that derivation resolves to the
   copy's parent directory (e.g. `/opt`), which has none of those scripts,
   and every stage fails immediately. The fix: point the Worker straight
   at the script's real location inside the step-2 checkout.
   ```bash
   sudo chmod +x /opt/latticejack-demo-src/demo/run-demo.sh
   ```
   `demo/worker/wrangler.toml`'s `DEMO_VM_SCRIPT_PATH` already defaults to
   `/opt/latticejack-demo-src/demo/run-demo.sh` to match — if you pinned a
   different checkout path in step 2, update both sides to agree, and
   confirm by reading the file, not by assuming each side "looks
   reasonable" in isolation (that's exactly how the original bug shipped).

   **Persisting `run-demo.sh`'s environment variables across a Run Command
   invocation.** The Worker's Run Command dispatch invokes this script
   directly (`systemd-run ... -- .../run-demo.sh SESSION_ID SESSION_TOKEN`)
   with no login shell in between, so `~/.bashrc`/`/etc/environment` (which
   only apply to interactive/PAM-initiated sessions) won't reach it.
   `run-demo.sh` sources a fixed file, `/etc/latticejack-demo.env`, at
   startup if it exists (harmless no-op if it doesn't — confirmed by the
   local mock-sink tests, which don't create this file). Create it once:
   ```bash
   sudo tee /etc/latticejack-demo.env > /dev/null <<'EOF'
   INGEST_URL=https://latticejack.itinerario.io/api/ingest   # set once §3's domain is live - see below
   LATTICEJACK_LLAMA_URL=http://127.0.0.1:8090
   LLAMA_SERVER_BIN=/opt/latticejack-demo-src/component-ai/llama.cpp/build/bin/llama-server
   LLAMA_MODEL_PATH=/opt/latticejack-demo-src/component-ai/models/Llama-3.2-1B-Instruct-Q4_0.gguf
   LLAMA_CONTEXT_SIZE=4096
   EOF
   sudo chmod 644 /etc/latticejack-demo.env
   ```
   `INGEST_URL` specifically can't be filled in for real until §3 gives you
   a live Worker domain — leave it as the placeholder above (or omit the
   line entirely) until then; `run-demo.sh` exits early with a clear error
   if it's unset or empty when a real session actually tries to run, which
   is the correct, honest failure mode rather than silently posting
   nowhere.

   **Confirm outbound HTTPS from the VM to the Worker's domain is actually
   reachable** — this is the one connection the whole live-streaming design
   depends on (the Worker cannot reach into the VM; all data flows VM →
   Worker via `run-demo.sh`'s outbound POSTs). Test from the VM itself,
   once §3 is live:
   ```bash
   curl -sS -o /dev/null -w "%{http_code}\n" https://<your-worker-domain>/api/status
   ```
   A `200` (or any HTTP response at all) confirms the network path is
   open; a connection failure here means check the VM's NSG/outbound rules
   before touching anything else.

---

## 3. Cloudflare setup

1. **Workers Paid plan — not required.** An earlier draft of this checklist
   claimed Durable Objects need a Workers Paid plan; checked directly
   against Cloudflare's current pricing docs and that's no longer (or
   wasn't ever, for this config) accurate. `wrangler.toml`'s migration
   already declares `DemoOrchestrator` under `new_sqlite_classes` (not
   `new_classes`), and Durable Objects with the SQLite storage backend are
   available on the **Workers Free plan** — free-tier caps (100k
   requests/day, 13,000 GB-s/day DO compute, 1,000 KV writes/day) are all
   far above what a judge-triggered demo actually needs. No billing action
   required before deploying.

2. **DNS / domain.** This is hosted at `latticejack.itinerario.io` — set up
   that DNS record pointing at the Worker, either via a Cloudflare-managed
   zone + Worker route, or the
   `custom_domain` route block already stubbed (commented out) in
   `demo/worker/wrangler.toml`'s inline TODO. This domain is what you'll
   set as `INGEST_URL` on the VM (§2.6 above) and is the URL judges will
   actually visit.

3. **KV namespace** (used only for the last-successful-run snapshot shown
   on the landing page — the *live* log lives in DO storage, not here):
   ```bash
   cd demo/worker
   wrangler kv namespace create LAST_RUN_KV
   ```
   Take the returned `id` (and `preview_id` if you also run
   `--preview`) and fill them into `wrangler.toml`'s
   `[[kv_namespaces]]` block, replacing the `<<FILL_IN_...>>` placeholders.

4. **Worker secrets to set** — these are the exact names the Worker code
   reads via `env.*` (from `demo/worker/src/env.ts`, the single source of
   truth both `worker.ts` and `orchestrator.ts` import — checked against
   the actual code, not guessed):
   ```bash
   cd demo/worker
   wrangler secret put AZURE_CLIENT_SECRET      # from §1.2 above
   wrangler secret put TURNSTILE_SECRET_KEY     # from §3.5 below
   ```
   The non-secret Azure identifiers (`AZURE_TENANT_ID`, `AZURE_CLIENT_ID`,
   `AZURE_SUBSCRIPTION_ID`, `AZURE_RESOURCE_GROUP`, `AZURE_VM_NAME`) are
   **not** secrets but are still environment-specific — fill their
   `<<FILL_IN_...>>` placeholders directly in `wrangler.toml`'s `[vars]`
   block (see that file's inline comments), along with `account_id`
   (`wrangler whoami` to find yours).

5. **Turnstile site registration.** Register a new Turnstile widget in
   the Cloudflare dashboard for whichever domain hosts the frontend. You
   get two keys:
   - **Site key** → goes in the frontend, `demo/worker/public/index.html`
     (find its `<<FILL_IN_...>>` placeholder there and replace it — this
     one is public by design, it's meant to be embedded client-side).
   - **Secret key** → `TURNSTILE_SECRET_KEY` Worker secret, §3.4 above
     (never goes in frontend code — `src/turnstile.ts` calls Cloudflare's
     siteverify endpoint with it server-side only).

---

## 4. What to test first, and what to watch for specifically

**Update: this has now been done for real.** §1–3 are complete, the
Worker is deployed at `latticejack.itinerario.io`, and a full real
end-to-end run has succeeded — see `demo/README.md`'s "Then deployed live
and run for real" section for the complete account. The predictions below
turned out to matter in a slightly different order than expected, so both
are kept: the original guidance (still correct advice for anyone
redeploying this from scratch) and what actually happened.

1. **Basic surface first, cheaply:** load the frontend URL, confirm the
   static page renders and Turnstile's widget actually shows. **What
   happened:** confirmed working — real sessions were only ever creatable
   because a real human solved the real widget in a real browser.

2. **One full end-to-end run, watched closely, specifically for this:**
   this project's own build process flagged the Run Command
   `systemd-run`/`setsid` dispatch (`composeRunCommandScript` in
   `demo/worker/src/azure.ts`) as **the highest-risk untested detail in
   this entire design** — it exists specifically to avoid a known failure
   mode where a plain `nohup ... & disown` gets silently reaped by the
   Azure guest agent's session teardown right after the Run Command's own
   top-level script returns, killing `run-demo.sh` before it can do
   anything.

   **What actually happened, on the very first live run:** the dispatch
   mechanism itself worked correctly — `run-demo.sh` genuinely launched
   via `systemd-run --scope` (confirmed via the VM's own `systemd
   journal`), no reaping, exactly as designed. But `/api/log` still showed
   zero events, for a different, mundane reason: `/etc/latticejack-demo.env`
   on the VM was missing `INGEST_URL` entirely (stale from before this
   section's own heredoc, below, was finalized — never refreshed on the
   actual VM after the fact). `run-demo.sh` exits on its very first check
   if `INGEST_URL` is unset, before it can even POST a `failed` event,
   which is exactly consistent with the total silence observed. Fixed by
   correcting the file on the VM directly (matching the heredoc in §2.6
   below exactly) and re-verified with a full passing run. **Lesson for
   next time this is redeployed:** if `/api/log` stays empty after a real
   Run Command dispatch, check `/etc/latticejack-demo.env` on the VM
   itself first (`cat` it, compare against §2.6's heredoc) before
   suspecting the dispatch mechanism — the mechanism itself is now proven
   working, an env-file drift is the more likely culprit.

   If nothing arrives, the DO's own dead-man's-switch (the alarm's
   3-minute silence timeout, `DEMO_SILENCE_TIMEOUT_SECONDS`) will fire and
   move the session to `deallocating` on its own — that's not a bug, it's
   the safety net working as designed to stop paying for a VM nothing is
   reporting from. Treat "the silence timeout fired" as the signal to go
   debug the spawn, not as the demo itself failing.

3. **After a run completes successfully once:** confirm the VM actually
   deallocates (check `az vm get-instance-view` or the Azure portal, don't
   just trust the frontend saying "done") — this is what keeps the
   ongoing cost near zero between demos, and it's independently enforced
   twice (the DO's own completion path, and the cron dead-man's-switch as
   a backstop) specifically so a single bug in either one doesn't leave
   the VM running. **Done:** confirmed via `az vm show --show-details`
   after the real successful run above — `PowerState/deallocated`, also
   independently visible via the Worker's own `/api/status`.

4. **Rate limits and spectator mode — still not tested.** Trigger the
   daily cap (`DEMO_DAILY_CAP`, default 20) and per-IP hourly cap
   (`DEMO_PER_IP_HOURLY_CAP`, default 3) at least once deliberately in a
   low-stakes test, and confirm a second browser tab hitting "Start"
   while a session is active correctly attaches as a spectator to the
   same session instead of starting a duplicate one.

5. **Not yet tested: the cron dead-man's-switch firing for real.** It's
   deployed (`[triggers] crons = ["*/15 * * * *"]` in `wrangler.toml`) but
   hasn't been separately observed firing — the alarm-based retry path
   above is the primary mechanism and has been proven working, so this is
   a backstop-of-a-backstop, low risk but worth a look if there's time
   before judging.
