// All calls to the real Azure ARM / AAD APIs live here, and ONLY here - kept
// deliberately separable from the Durable Object and router so this module
// can be unit-tested with a fake `fetch` (see test/azure.test.ts) without
// ever touching a real Azure account.

import type { AzureConfig, AzurePowerState } from "./types";

export type FetchLike = typeof fetch;

const ARM_BASE = "https://management.azure.com";
const AAD_BASE = "https://login.microsoftonline.com";

function vmUrl(cfg: AzureConfig, suffix: string): string {
  return (
    `${ARM_BASE}/subscriptions/${cfg.subscriptionId}` +
    `/resourceGroups/${cfg.resourceGroup}` +
    `/providers/Microsoft.Compute/virtualMachines/${cfg.vmName}${suffix}` +
    `?api-version=${cfg.apiVersion}`
  );
}

export class AzureApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly body: string,
  ) {
    super(message);
    this.name = "AzureApiError";
  }
}

/**
 * OAuth2 client-credentials grant against AAD. Callers are responsible for
 * caching the result (see orchestrator.ts's DO-storage-backed cache - NOT
 * Worker isolate memory, isolates are ephemeral and the cron handler runs
 * in a different isolate than the DO).
 */
export async function fetchAadToken(
  cfg: AzureConfig,
  fetchImpl: FetchLike = fetch,
): Promise<{ access_token: string; expires_in: number }> {
  const url = `${AAD_BASE}/${cfg.tenantId}/oauth2/v2.0/token`;
  const body = new URLSearchParams({
    grant_type: "client_credentials",
    client_id: cfg.clientId,
    client_secret: cfg.clientSecret,
    scope: "https://management.azure.com/.default",
  });
  const res = await fetchImpl(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: body.toString(),
  });
  const text = await res.text();
  if (!res.ok) {
    throw new AzureApiError(`AAD token request failed: ${res.status}`, res.status, text);
  }
  const json = JSON.parse(text) as { access_token: string; expires_in: number };
  return json;
}

async function armRequest(
  url: string,
  accessToken: string,
  method: "GET" | "POST",
  fetchImpl: FetchLike,
): Promise<{ status: number; text: string }> {
  const res = await fetchImpl(url, {
    method,
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
  });
  const text = await res.text();
  return { status: res.status, text };
}

/** POST .../virtualMachines/{vm}/start - async operation, we don't wait for LRO completion here, poll instanceView instead. */
export async function startVm(
  cfg: AzureConfig,
  accessToken: string,
  fetchImpl: FetchLike = fetch,
): Promise<void> {
  const { status, text } = await armRequest(vmUrl(cfg, "/start"), accessToken, "POST", fetchImpl);
  // 200 = sync-completed-already (rare), 202 = accepted (normal LRO start).
  if (status !== 200 && status !== 202) {
    throw new AzureApiError(`VM start failed: ${status}`, status, text);
  }
}

/** POST .../virtualMachines/{vm}/deallocate */
export async function deallocateVm(
  cfg: AzureConfig,
  accessToken: string,
  fetchImpl: FetchLike = fetch,
): Promise<void> {
  const { status, text } = await armRequest(
    vmUrl(cfg, "/deallocate"),
    accessToken,
    "POST",
    fetchImpl,
  );
  if (status !== 200 && status !== 202) {
    throw new AzureApiError(`VM deallocate failed: ${status}`, status, text);
  }
}

/**
 * GET .../virtualMachines/{vm}/instanceView - returns the literal
 * PowerState/* code, per the architecture note to poll on the literal
 * status code rather than inferring "not running".
 */
export async function getInstanceViewPowerState(
  cfg: AzureConfig,
  accessToken: string,
  fetchImpl: FetchLike = fetch,
): Promise<AzurePowerState> {
  const { status, text } = await armRequest(
    vmUrl(cfg, "/instanceView"),
    accessToken,
    "GET",
    fetchImpl,
  );
  if (status !== 200) {
    throw new AzureApiError(`instanceView failed: ${status}`, status, text);
  }
  const json = JSON.parse(text) as { statuses?: Array<{ code?: string }> };
  const codes = json.statuses?.map((s) => s.code).filter(Boolean) as string[] | undefined;
  const powerCode = codes?.find((c) => c.startsWith("PowerState/"));
  return (powerCode as AzurePowerState) ?? "PowerState/unknown";
}

/**
 * Composes the Run Command script lines. MUST use systemd-run (preferred)
 * or setsid, NEVER `nohup ... & disown` at the top level - the plain
 * nohup+disown pattern can be silently reaped by the Azure guest agent's
 * process/session teardown when the Run Command's own top-level script
 * returns. This was flagged as the single highest-risk detail in the
 * design; do not "simplify" it back to nohup+disown.
 */
export function composeRunCommandScript(
  sessionId: string,
  sessionToken: string,
  scriptPath: string,
): string[] {
  // Single shell script: try systemd-run first, fall back to setsid+nohup
  // if systemd-run isn't available on the VM image (e.g. minimal/non-systemd
  // images). Unit name includes the session id so concurrent/duplicate
  // dispatch retries are idempotent-ish (systemd-run will fail loudly on a
  // unit name collision rather than double-launching silently).
  const unit = `latticejack-demo-${sessionId}`;
  return [
    "#!/usr/bin/env bash",
    "set -uo pipefail",
    `if command -v systemd-run >/dev/null 2>&1; then`,
    `  systemd-run --unit=${unit} --scope -- ${scriptPath} ${sessionId} ${sessionToken}`,
    `else`,
    `  setsid nohup ${scriptPath} ${sessionId} ${sessionToken} </dev/null >/opt/latticejack-demo/last-run.log 2>&1 &`,
    `  disown`,
    `fi`,
    "exit 0",
  ];
}

/**
 * Fire-and-forget dispatch via the ad-hoc Run Command REST API
 * (RunShellScript). We deliberately do NOT wait for / parse Run Command's
 * own synchronous response body for the demo's output - it truncates at
 * ~4KB and cannot stream; the VM streams real output back via /api/ingest
 * instead. We DO retry the dispatch call itself (not the demo run) up to
 * `maxAttempts` times at `delayMs` intervals, to absorb Azure guest-agent
 * warm-up lag right after VM start.
 */
export async function dispatchRunCommand(
  cfg: AzureConfig,
  accessToken: string,
  script: string[],
  fetchImpl: FetchLike = fetch,
  maxAttempts = 3,
  delayMs = 20_000,
  sleepImpl: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
): Promise<void> {
  const url = vmUrl(cfg, "/runCommand");
  let lastError: unknown;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const res = await fetchImpl(url, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          commandId: "RunShellScript",
          script,
        }),
      });
      // Fire-and-forget: any 2xx/202-style accept is success for dispatch
      // purposes. We do not await the LRO to completion.
      if (res.status === 200 || res.status === 202) {
        // Drain the body so the connection can be reused; ignore contents.
        await res.text().catch(() => undefined);
        return;
      }
      const text = await res.text().catch(() => "");
      lastError = new AzureApiError(`runCommand dispatch failed: ${res.status}`, res.status, text);
    } catch (err) {
      lastError = err;
    }
    if (attempt < maxAttempts) {
      await sleepImpl(delayMs);
    }
  }
  throw lastError instanceof Error ? lastError : new Error(String(lastError));
}
