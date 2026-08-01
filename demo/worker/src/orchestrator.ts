// DemoOrchestrator - the one Durable Object class for this project.
//
// Holds: the state machine, the "only one live run" gate (spectator mode
// for a second concurrent starter), the append-only live log + stage
// table, session/bearer-token minting, the AAD token cache (in DO storage,
// not isolate memory), the daily/per-IP rate-limit counters (in DO
// storage, the source of truth), and the single periodic alarm.
//
// Concurrency note: a Durable Object instance's `fetch`/`alarm` handlers
// are protected by the runtime's automatic input/output gating - while an
// `await this.state.storage...` call is in flight, the runtime defers
// delivering the next event to this same instance. That gives us the
// "single global mutex" the architecture calls for without hand-rolling a
// lock: the application-level check in handleStart (canStartNewSession)
// is what actually decides spectator-vs-new-session, and it can't race
// itself because of that gating.

import {
  fetchAadToken,
  startVm,
  deallocateVm,
  getInstanceViewPowerState,
  composeRunCommandScript,
  dispatchRunCommand,
} from "./azure";
import { azureConfigFromEnv, type Env } from "./env";
import { decideAlarmAction, initialStages, isUnderCap, canStartNewSession } from "./state-logic";
import { randomHexToken, timingSafeEqual, truncateLog, utcDayBucket, utcHourBucket } from "./util";
import { verifyTurnstile } from "./turnstile";
import type {
  AadTokenCache,
  AzurePowerState,
  IngestEvent,
  OrchestratorState,
  StageName,
  StageState,
} from "./types";

const MAX_LOG_CHARS = 500_000; // ring-buffer cap, see util.truncateLog
const AAD_REFRESH_SKEW_MS = 5 * 60 * 1000; // refresh at expiresAt - 5min, per spec
const VM_START_POLL_TIMEOUT_MS = 3 * 60 * 1000;
const VM_START_POLL_INTERVAL_MS = 10_000;
const VM_DEALLOC_POLL_TIMEOUT_MS = 3 * 60 * 1000;
const VM_DEALLOC_POLL_INTERVAL_MS = 10_000;
const STATUS_REFRESH_STALE_MS = 60_000;

interface Meta {
  state: OrchestratorState;
  sessionId: string | null;
  sessionToken: string | null;
  sessionStartedAt: number | null;
  lastIngestAt: number | null;
  clientIp: string | null;
  outcome: "success" | "failure" | null;
  failureReason: string | null;
  doneSummary: { handshake_ms?: number; request_to_response_ms?: number; ratio?: number } | null;
  vmPowerState: AzurePowerState;
  vmPowerStateCheckedAt: number | null;
}

const META_KEYS = [
  "state",
  "sessionId",
  "sessionToken",
  "sessionStartedAt",
  "lastIngestAt",
  "clientIp",
  "outcome",
  "failureReason",
  "doneSummary",
  "vmPowerState",
  "vmPowerStateCheckedAt",
] as const;

function defaultMeta(): Meta {
  return {
    state: "idle",
    sessionId: null,
    sessionToken: null,
    sessionStartedAt: null,
    lastIngestAt: null,
    clientIp: null,
    outcome: null,
    failureReason: null,
    doneSummary: null,
    vmPowerState: "PowerState/unknown",
    vmPowerStateCheckedAt: null,
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export class DemoOrchestrator implements DurableObject {
  private readonly state: DurableObjectState;
  private readonly env: Env;

  constructor(state: DurableObjectState, env: Env) {
    this.state = state;
    this.env = env;
  }

  // ---------------------------------------------------------------------
  // Storage helpers
  // ---------------------------------------------------------------------

  private async loadMeta(): Promise<Meta> {
    const stored = await this.state.storage.get(META_KEYS as unknown as string[]);
    const defaults = defaultMeta();
    const meta = { ...defaults } as Record<string, unknown>;
    for (const key of META_KEYS) {
      if (stored.has(key)) meta[key] = stored.get(key);
    }
    return meta as unknown as Meta;
  }

  private async patchMeta(patch: Partial<Meta>): Promise<void> {
    await this.state.storage.put(patch as Record<string, unknown>);
  }

  private async loadStages(): Promise<Record<StageName, StageState>> {
    const stored = await this.state.storage.get<Record<StageName, StageState>>("stages");
    return stored ?? initialStages();
  }

  private async loadLog(): Promise<string> {
    return (await this.state.storage.get<string>("log")) ?? "";
  }

  private async appendLog(text: string): Promise<void> {
    const current = await this.loadLog();
    const next = truncateLog(current + text, MAX_LOG_CHARS);
    await this.state.storage.put("log", next);
  }

  private async loadVerdicts(): Promise<string[]> {
    return (await this.state.storage.get<string[]>("verdicts")) ?? [];
  }

  private async ensureAlarmScheduled(): Promise<void> {
    const existing = await this.state.storage.getAlarm();
    if (existing === null) {
      const intervalMs = this.alarmIntervalMs();
      await this.state.storage.setAlarm(Date.now() + intervalMs);
    }
  }

  private alarmIntervalMs(): number {
    return (Number(this.env.DEMO_ALARM_INTERVAL_SECONDS) || 45) * 1000;
  }

  private hardCapMs(): number {
    return (Number(this.env.DEMO_HARD_CAP_SECONDS) || 720) * 1000;
  }

  private silenceMs(): number {
    return (Number(this.env.DEMO_SILENCE_TIMEOUT_SECONDS) || 180) * 1000;
  }

  // ---------------------------------------------------------------------
  // AAD token cache - in DO storage, NOT isolate memory (isolates are
  // ephemeral and the cron's scheduled() handler runs in a different
  // isolate than the DO, so an in-memory cache here wouldn't even help
  // the cron path anyway - it mints its own token independently).
  // ---------------------------------------------------------------------

  private async getAadTokenCached(): Promise<AadTokenCache> {
    const cached = await this.state.storage.get<AadTokenCache>("aadToken");
    const now = Date.now();
    if (cached && cached.expiresAt - AAD_REFRESH_SKEW_MS > now) {
      return cached;
    }
    const cfg = azureConfigFromEnv(this.env);
    const fresh = await fetchAadToken(cfg);
    const entry: AadTokenCache = {
      access_token: fresh.access_token,
      expiresAt: now + fresh.expires_in * 1000,
    };
    await this.state.storage.put("aadToken", entry);
    return entry;
  }

  // ---------------------------------------------------------------------
  // fetch() router - dispatched to by worker.ts's /api/* forwarding.
  // ---------------------------------------------------------------------

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (url.pathname === "/api/status" && request.method === "GET") {
        return await this.handleStatus();
      }
      if (url.pathname === "/api/start" && request.method === "POST") {
        return await this.handleStart(request);
      }
      if (url.pathname === "/api/log" && request.method === "GET") {
        return await this.handleLogRead(url);
      }
      if (url.pathname === "/api/ingest" && request.method === "POST") {
        return await this.handleIngest(request);
      }
      if (url.pathname === "/internal/cron-check" && request.method === "GET") {
        return await this.handleCronCheck();
      }
      return jsonResponse({ error: "not_found" }, 404);
    } catch (err) {
      console.error("DemoOrchestrator error", err);
      return jsonResponse({ error: "internal_error", message: String(err) }, 500);
    }
  }

  // ---------------------------------------------------------------------
  // GET /api/status
  // ---------------------------------------------------------------------

  private async handleStatus(): Promise<Response> {
    let meta = await this.loadMeta();
    const activeStates: OrchestratorState[] = ["vm_starting", "running_stages", "deallocating"];
    const isActive = activeStates.includes(meta.state);

    // Opportunistic refresh: only when idle and the cached reading is
    // stale, to avoid hammering Azure on every landing-page poll.
    if (
      !isActive &&
      (meta.vmPowerStateCheckedAt === null ||
        Date.now() - meta.vmPowerStateCheckedAt > STATUS_REFRESH_STALE_MS)
    ) {
      try {
        const cfg = azureConfigFromEnv(this.env);
        const token = await this.getAadTokenCached();
        const powerState = await getInstanceViewPowerState(cfg, token.access_token);
        await this.patchMeta({ vmPowerState: powerState, vmPowerStateCheckedAt: Date.now() });
        meta = { ...meta, vmPowerState: powerState, vmPowerStateCheckedAt: Date.now() };
      } catch (err) {
        // Best-effort only; the badge just shows the last known reading.
        console.warn("status refresh failed", err);
      }
    }

    return jsonResponse({
      state: meta.state,
      active: isActive,
      sessionId: isActive ? meta.sessionId : null,
      vmPowerState: meta.vmPowerState,
      vmPowerStateCheckedAt: meta.vmPowerStateCheckedAt,
    });
  }

  // ---------------------------------------------------------------------
  // POST /api/start
  // ---------------------------------------------------------------------

  private async handleStart(request: Request): Promise<Response> {
    let body: { turnstileToken?: string };
    try {
      body = await request.json();
    } catch {
      return jsonResponse({ error: "invalid_json" }, 400);
    }

    const clientIp = request.headers.get("CF-Connecting-IP") ?? "unknown";

    const turnstile = await verifyTurnstile(
      this.env.TURNSTILE_SECRET_KEY,
      body.turnstileToken ?? "",
      clientIp,
    );
    if (!turnstile.success) {
      return jsonResponse(
        { error: "turnstile_failed", errorCodes: turnstile.errorCodes ?? [] },
        400,
      );
    }

    const meta = await this.loadMeta();

    if (!canStartNewSession(meta.state)) {
      // Spectator mode: hand back the existing live session instead of
      // starting a second one.
      return jsonResponse({
        mode: "spectator",
        sessionId: meta.sessionId,
        state: meta.state,
      });
    }

    const now = Date.now();
    const dayBucket = utcDayBucket(now);
    const hourBucket = utcHourBucket(now);
    const dailyCap = Number(this.env.DEMO_DAILY_CAP) || 20;
    const ipCap = Number(this.env.DEMO_PER_IP_HOURLY_CAP) || 3;

    const dailyKey = `dailyCount:${dayBucket}`;
    const ipKey = `ipCount:${clientIp}:${hourBucket}`;
    const [dailyCount, ipCount] = await Promise.all([
      this.state.storage.get<number>(dailyKey),
      this.state.storage.get<number>(ipKey),
    ]);

    if (!isUnderCap(dailyCount ?? 0, dailyCap)) {
      return jsonResponse(
        { error: "daily_cap_reached", cap: dailyCap, message: "Daily demo-run cap reached; try again tomorrow." },
        429,
      );
    }
    if (!isUnderCap(ipCount ?? 0, ipCap)) {
      return jsonResponse(
        {
          error: "rate_limited",
          cap: ipCap,
          message: "You've started this demo a few times in the last hour; please wait before trying again.",
        },
        429,
      );
    }

    await this.state.storage.put(dailyKey, (dailyCount ?? 0) + 1);
    await this.state.storage.put(ipKey, (ipCount ?? 0) + 1);

    const sessionId = randomHexToken(16);
    const sessionToken = randomHexToken(32);

    await this.state.storage.put({
      state: "vm_starting" as OrchestratorState,
      sessionId,
      sessionToken,
      sessionStartedAt: now,
      lastIngestAt: now,
      clientIp,
      outcome: null,
      failureReason: null,
      doneSummary: null,
      stages: initialStages(),
      log: `=== session ${sessionId} starting, requesting VM start ===\n`,
      verdicts: [],
    });

    await this.ensureAlarmScheduled();

    // Fire-and-forget: a Durable Object stays alive as long as it has a
    // pending (unresolved) task, so this doesn't need an explicit
    // waitUntil the way a stateless Worker fetch handler would - but we
    // still route through state.waitUntil where available for extra
    // safety/clarity about intent.
    const task = this.runStartupSequence(sessionId, sessionToken);
    this.state.waitUntil?.(task);
    task.catch((err) => console.error("runStartupSequence crashed", err));

    return jsonResponse({ mode: "started", sessionId, state: "vm_starting" });
  }

  private async runStartupSequence(sessionId: string, sessionToken: string): Promise<void> {
    try {
      const cfg = azureConfigFromEnv(this.env);
      const token = await this.getAadTokenCached();

      await startVm(cfg, token.access_token);
      await this.appendLog("=== VM start requested, waiting for PowerState/running ===\n");

      const reachedRunning = await this.pollForPowerState(
        cfg,
        () => this.getAadTokenCached(),
        "PowerState/running",
        VM_START_POLL_TIMEOUT_MS,
        VM_START_POLL_INTERVAL_MS,
      );
      if (!reachedRunning) {
        throw new Error(
          `VM did not reach PowerState/running within ${VM_START_POLL_TIMEOUT_MS / 1000}s`,
        );
      }
      await this.appendLog("=== VM running, dispatching Run Command ===\n");

      const script = composeRunCommandScript(sessionId, sessionToken, this.env.DEMO_VM_SCRIPT_PATH);
      // dispatchRunCommand has its own internal retry (3x ~20s) to absorb
      // guest-agent warm-up lag; we don't add another retry layer here.
      await dispatchRunCommand(cfg, token.access_token, script);
      await this.appendLog("=== Run Command dispatched, waiting for the VM to POST /api/ingest ===\n");

      await this.patchMeta({ state: "running_stages" });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      console.error("VM startup sequence failed", err);
      await this.appendLog(`=== VM startup failed: ${message} ===\n`);
      await this.patchMeta({
        state: "deallocating",
        outcome: "failure",
        failureReason: `vm_startup_failed: ${message}`,
      });
      // Best-effort cleanup in case the VM partially started.
      const cleanup = this.attemptDeallocate();
      this.state.waitUntil?.(cleanup);
      cleanup.catch((cleanupErr) => console.error("cleanup deallocate failed", cleanupErr));
    }
  }

  private async pollForPowerState(
    cfg: ReturnType<typeof azureConfigFromEnv>,
    getToken: () => Promise<AadTokenCache>,
    target: AzurePowerState,
    timeoutMs: number,
    intervalMs: number,
  ): Promise<boolean> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      try {
        const token = await getToken();
        const powerState = await getInstanceViewPowerState(cfg, token.access_token);
        await this.patchMeta({ vmPowerState: powerState, vmPowerStateCheckedAt: Date.now() });
        if (powerState === target) return true;
      } catch (err) {
        console.warn("instanceView poll failed, will retry", err);
      }
      await sleep(intervalMs);
    }
    return false;
  }

  // ---------------------------------------------------------------------
  // GET /api/log
  // ---------------------------------------------------------------------

  private async handleLogRead(url: URL): Promise<Response> {
    const sessionParam = url.searchParams.get("session");
    const afterParam = Number(url.searchParams.get("after") ?? "0");
    const after = Number.isFinite(afterParam) && afterParam >= 0 ? afterParam : 0;

    const meta = await this.loadMeta();
    if (!meta.sessionId || sessionParam !== meta.sessionId) {
      return jsonResponse({ error: "session_not_found" }, 404);
    }

    const [log, stages, verdicts] = await Promise.all([
      this.loadLog(),
      this.loadStages(),
      this.loadVerdicts(),
    ]);

    return jsonResponse({
      state: meta.state,
      stages,
      log: log.slice(after),
      nextOffset: log.length,
      verdicts,
      outcome: meta.outcome,
      failureReason: meta.failureReason,
      doneSummary: meta.doneSummary,
      vmPowerState: meta.vmPowerState,
    });
  }

  // ---------------------------------------------------------------------
  // POST /api/ingest
  // ---------------------------------------------------------------------

  private async handleIngest(request: Request): Promise<Response> {
    const authHeader = request.headers.get("Authorization") ?? "";
    const bearerMatch = /^Bearer (.+)$/.exec(authHeader);
    const providedToken = bearerMatch?.[1] ?? "";

    const meta = await this.loadMeta();
    if (!meta.sessionToken || !timingSafeEqual(providedToken, meta.sessionToken)) {
      return jsonResponse({ error: "unauthorized" }, 401);
    }

    let event: IngestEvent;
    try {
      event = await request.json();
    } catch {
      return jsonResponse({ error: "invalid_json" }, 400);
    }

    if (!event || event.session_id !== meta.sessionId) {
      return jsonResponse({ error: "session_mismatch" }, 409);
    }

    // Update freshness for the silence-timeout watchdog. Per spec: do NOT
    // reset/reschedule the alarm itself here (that would defeat the point
    // of a periodic alarm) - just update the storage value the alarm reads.
    await this.state.storage.put("lastIngestAt", Date.now());

    switch (event.type) {
      case "stage_start": {
        const stages = await this.loadStages();
        stages[event.stage] = { ...stages[event.stage], status: "running", startedAt: Date.now() };
        await this.state.storage.put("stages", stages);
        await this.appendLog(`=== [${event.stage}] starting ===\n`);
        break;
      }
      case "stage_end": {
        const stages = await this.loadStages();
        stages[event.stage] = {
          ...stages[event.stage],
          status: event.status,
          endedAt: Date.now(),
          exitCode: event.exit_code ?? null,
        };
        await this.state.storage.put("stages", stages);
        await this.appendLog(`=== [${event.stage}] ${event.status} ===\n`);
        break;
      }
      case "log_chunk": {
        const text = event.text.endsWith("\n") ? event.text : event.text + "\n";
        await this.appendLog(text);
        break;
      }
      case "verification": {
        const text = event.text.endsWith("\n") ? event.text : event.text + "\n";
        await this.appendLog(text);
        const verdicts = await this.loadVerdicts();
        verdicts.push(event.text);
        await this.state.storage.put("verdicts", verdicts);
        break;
      }
      case "done": {
        await this.appendLog("=== demo complete, deallocating VM ===\n");
        await this.patchMeta({
          state: "deallocating",
          outcome: "success",
          doneSummary: event.summary ?? null,
        });
        const dealloc = this.attemptDeallocate();
        this.state.waitUntil?.(dealloc);
        dealloc.catch((err) => console.error("deallocate after done failed", err));
        break;
      }
      case "failed": {
        await this.appendLog(`=== demo failed: ${event.reason} ===\n`);
        await this.patchMeta({
          state: "deallocating",
          outcome: "failure",
          failureReason: event.reason,
        });
        const dealloc = this.attemptDeallocate();
        this.state.waitUntil?.(dealloc);
        dealloc.catch((err) => console.error("deallocate after failed failed", err));
        break;
      }
      default: {
        const _exhaustive: never = event;
        void _exhaustive;
        return jsonResponse({ error: "unknown_event_type" }, 400);
      }
    }

    return jsonResponse({ ok: true });
  }

  // ---------------------------------------------------------------------
  // Deallocation + confirmation. Called from the ingest done/failed paths,
  // from the startup-failure cleanup path, and repeatedly from alarm()
  // while state === 'deallocating' until instanceView confirms
  // PowerState/deallocated.
  // ---------------------------------------------------------------------

  private async attemptDeallocate(): Promise<void> {
    const cfg = azureConfigFromEnv(this.env);
    try {
      const token = await this.getAadTokenCached();
      await deallocateVm(cfg, token.access_token);
    } catch (err) {
      console.error("deallocate call failed, alarm will retry", err);
      return; // leave state as 'deallocating'; alarm loop will retry
    }

    const confirmed = await this.pollForPowerState(
      cfg,
      () => this.getAadTokenCached(),
      "PowerState/deallocated",
      VM_DEALLOC_POLL_TIMEOUT_MS,
      VM_DEALLOC_POLL_INTERVAL_MS,
    );

    if (!confirmed) {
      // Alarm loop will keep calling attemptDeallocate every tick until
      // instanceView confirms - see alarm() below.
      return;
    }

    const meta = await this.loadMeta();
    const finalState: OrchestratorState = meta.outcome === "failure" ? "failed" : "done";
    await this.appendLog(`=== VM deallocation confirmed, run ${finalState} ===\n`);
    await this.patchMeta({ state: finalState });

    if (finalState === "done") {
      try {
        const log = await this.loadLog();
        const verdicts = await this.loadVerdicts();
        await this.env.LAST_RUN_KV.put(
          "last-run",
          JSON.stringify({
            sessionId: meta.sessionId,
            finishedAt: Date.now(),
            doneSummary: meta.doneSummary,
            verdicts,
            log,
          }),
        );
      } catch (err) {
        // Non-fatal: the KV snapshot is a nice-to-have, not load-bearing.
        console.warn("failed to write last-run KV snapshot", err);
      }
    }
  }

  // ---------------------------------------------------------------------
  // alarm() - the ONE alarm. Re-arms itself every DEMO_ALARM_INTERVAL_SECONDS
  // (30-60s band) while a session is active, checking both the hard-cap and
  // silence-timeout conditions on every fire, and keeps re-arming through
  // the deallocating phase until instanceView confirms deallocated.
  // ---------------------------------------------------------------------

  async alarm(): Promise<void> {
    const meta = await this.loadMeta();

    if (meta.state === "idle" || meta.state === "done" || meta.state === "failed") {
      // Terminal/idle - nothing to watch, don't rearm.
      return;
    }

    if (meta.state === "deallocating") {
      // Already decided to shut down; keep retrying confirmation.
      await this.attemptDeallocate();
    } else {
      const decision = decideAlarmAction({
        now: Date.now(),
        state: meta.state,
        sessionStartedAt: meta.sessionStartedAt,
        lastIngestAt: meta.lastIngestAt,
        hardCapMs: this.hardCapMs(),
        silenceMs: this.silenceMs(),
      });
      if (decision.shouldDeallocate) {
        const reasonText =
          decision.reason === "hard_cap"
            ? "timeout: 12-minute hard cap exceeded"
            : "timeout: no ingest activity for 3+ minutes";
        await this.appendLog(`=== ${reasonText}, deallocating VM ===\n`);
        await this.patchMeta({
          state: "deallocating",
          outcome: "failure",
          failureReason: reasonText,
        });
        await this.attemptDeallocate();
      }
    }

    const after = await this.loadMeta();
    if (after.state !== "idle" && after.state !== "done" && after.state !== "failed") {
      await this.state.storage.setAlarm(Date.now() + this.alarmIntervalMs());
    }
  }

  // ---------------------------------------------------------------------
  // Internal endpoint for the cron dead-man's-switch (scheduled() in
  // worker.ts) - best-effort introspection only, never authoritative; the
  // cron must still work if this call fails or this DO is broken.
  // ---------------------------------------------------------------------

  private async handleCronCheck(): Promise<Response> {
    const meta = await this.loadMeta();
    return jsonResponse({
      state: meta.state,
      sessionId: meta.sessionId,
      sessionStartedAt: meta.sessionStartedAt,
      lastIngestAt: meta.lastIngestAt,
    });
  }
}
