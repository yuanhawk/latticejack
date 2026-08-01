// Shared types for the Latticejack demo orchestrator.
//
// The /api/ingest wire shape is documented prominently in demo/worker/README.md
// (search "INGEST CONTRACT") - keep that doc and this file in sync. It was
// authored here because demo/run-demo.sh (built by a parallel task) did not
// exist yet at the time this Worker was written; if it now sends something
// different, reconcile the two rather than silently diverging.

export type StageName = "before" | "after" | "nativekem" | "ai";

export const STAGE_ORDER: readonly StageName[] = ["before", "after", "nativekem", "ai"];

export type StageStatus = "pending" | "running" | "done" | "failed";

export interface StageState {
  status: StageStatus;
  startedAt: number | null;
  endedAt: number | null;
  exitCode: number | null;
}

export type OrchestratorState =
  | "idle"
  | "vm_starting"
  | "running_stages"
  | "deallocating"
  | "done"
  | "failed";

// --- /api/ingest event shapes -----------------------------------------------
//
// The VM-side wrapper script (demo/run-demo.sh) POSTs one JSON object per
// event to POST /api/ingest, with header:
//   Authorization: Bearer <SESSION_TOKEN>   (the token minted at /api/start)
// Every event carries `session_id` (must match the active session, else
// 409) and `seq`, a per-session monotonically increasing integer starting
// at 0 (used only to detect gaps/out-of-order delivery for diagnostics -
// the DO does not currently reject out-of-order events, it just logs them
// in arrival order, since HTTP POSTs from the VM are not guaranteed ordered
// under retry).

interface IngestEventBase {
  session_id: string;
  seq: number;
  /** Producer-side ms-epoch timestamp; optional, the server also stamps receipt time. */
  ts?: number;
}

export interface StageStartEvent extends IngestEventBase {
  type: "stage_start";
  stage: StageName;
}

export interface StageEndEvent extends IngestEventBase {
  type: "stage_end";
  stage: StageName;
  status: "done" | "failed";
  exit_code?: number;
}

export interface LogChunkEvent extends IngestEventBase {
  type: "log_chunk";
  stage?: StageName;
  /** Raw text, may contain embedded newlines; appended verbatim to the live log. */
  text: string;
}

export interface VerificationEvent extends IngestEventBase {
  type: "verification";
  stage?: StageName;
  /**
   * MUST be the literal line the demo script printed, starting with
   * "VERIFIED:" or "VERIFICATION FAILED:" - the frontend's verdict panel
   * only ever quotes these literal lines verbatim, never synthesizes one.
   */
  text: string;
}

export interface DoneEvent extends IngestEventBase {
  type: "done";
  summary?: {
    handshake_ms?: number;
    request_to_response_ms?: number;
    ratio?: number;
  };
}

export interface FailedEvent extends IngestEventBase {
  type: "failed";
  reason: string;
  stage?: StageName;
}

export type IngestEvent =
  | StageStartEvent
  | StageEndEvent
  | LogChunkEvent
  | VerificationEvent
  | DoneEvent
  | FailedEvent;

// --- Azure config ------------------------------------------------------------

export interface AzureConfig {
  tenantId: string;
  clientId: string;
  clientSecret: string;
  subscriptionId: string;
  resourceGroup: string;
  vmName: string;
  apiVersion: string;
}

export interface AadTokenCache {
  access_token: string;
  /** ms-epoch when this token expires. */
  expiresAt: number;
}

export type AzurePowerState =
  | "PowerState/starting"
  | "PowerState/running"
  | "PowerState/stopping"
  | "PowerState/stopped"
  | "PowerState/deallocating"
  | "PowerState/deallocated"
  | "PowerState/unknown";
