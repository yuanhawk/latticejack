// Single Worker: routes /api/* to the DemoOrchestrator Durable Object,
// falls through to the static frontend (assets binding) for everything
// else, and runs the independent cron dead-man's-switch. See wrangler.toml
// for why this is one Worker rather than a Pages+Worker split.

import { azureConfigFromEnv, type Env } from "./env";
import { fetchAadToken, getInstanceViewPowerState, deallocateVm, AzureApiError } from "./azure";

export { DemoOrchestrator } from "./orchestrator";

const DO_SINGLETON_NAME = "singleton";

function getOrchestratorStub(env: Env): DurableObjectStub {
  const id = env.DEMO_ORCHESTRATOR.idFromName(DO_SINGLETON_NAME);
  return env.DEMO_ORCHESTRATOR.get(id);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname.startsWith("/api/")) {
      const stub = getOrchestratorStub(env);
      return stub.fetch(request);
    }

    // Normally unreachable when wrangler.toml's [assets] run_worker_first
    // is honored (non-/api/* requests go straight to the asset server) -
    // kept as a defensive fallback so this still behaves correctly if
    // that config option changes or isn't respected in some environment.
    return env.ASSETS.fetch(request);
  },

  /**
   * Cron dead-man's-switch, deliberately independent of the Durable
   * Object - must still deallocate a stray running VM even if the DO is
   * completely broken/unreachable. Mints its OWN AAD token (does not
   * assume it can share the DO's cached one - it runs in a different
   * isolate).
   */
  async scheduled(_event: ScheduledEvent, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(runCronSweep(env));
  },
};

async function runCronSweep(env: Env): Promise<void> {
  const cfg = azureConfigFromEnv(env);

  let powerState: string;
  let accessToken: string;
  try {
    const token = await fetchAadToken(cfg);
    accessToken = token.access_token;
    powerState = await getInstanceViewPowerState(cfg, accessToken);
  } catch (err) {
    console.error("cron: failed to check instanceView, cannot act this cycle", err);
    return;
  }

  const vmIsUp = powerState === "PowerState/running" || powerState === "PowerState/starting";
  if (!vmIsUp) {
    // Nothing to do - deallocated/stopped/unknown.
    return;
  }

  const doStatus = await bestEffortDoStatus(env);
  const hardCapMs = (Number(env.DEMO_HARD_CAP_SECONDS) || 720) * 1000;
  // Generous grace window above the DO's own hard cap: the DO's alarm
  // should already have caught anything past DEMO_HARD_CAP_SECONDS, so if
  // cron independently sees the VM up well past that (or can't confirm a
  // legitimate session at all), it's an anomaly.
  const sanityBoundMs = hardCapMs + 5 * 60 * 1000;

  const activeStates = new Set(["vm_starting", "running_stages", "deallocating"]);
  const now = Date.now();
  const legitimateActiveSession =
    doStatus !== null &&
    activeStates.has(doStatus.state) &&
    doStatus.sessionStartedAt !== null &&
    now - doStatus.sessionStartedAt < sanityBoundMs;

  if (legitimateActiveSession) {
    return; // DO appears to have this under control.
  }

  console.error(
    "cron anomaly: VM is up with no evidence of a legitimate active session - deallocating",
    { powerState, doStatus },
  );

  try {
    await deallocateVm(cfg, accessToken);
  } catch (err) {
    console.error("cron: deallocate call itself failed", err);
  }
}

interface DoCronStatus {
  state: string;
  sessionId: string | null;
  sessionStartedAt: number | null;
  lastIngestAt: number | null;
}

/** Best-effort, short-timeout introspection of the DO - failure here must NOT stop the cron sweep from acting. */
async function bestEffortDoStatus(env: Env): Promise<DoCronStatus | null> {
  try {
    const stub = getOrchestratorStub(env);
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 5000);
    try {
      const res = await stub.fetch("https://demo-orchestrator.internal/internal/cron-check", {
        signal: controller.signal,
      });
      if (!res.ok) return null;
      return (await res.json()) as DoCronStatus;
    } finally {
      clearTimeout(timeout);
    }
  } catch (err) {
    console.warn("cron: DO status check failed (treating as unreachable)", err);
    return null;
  }
}

// Re-exported so azure.ts's AzureApiError is discoverable from a single
// module for anything importing worker.ts's public surface (e.g. tests).
export { AzureApiError };
