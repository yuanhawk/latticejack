// Pure, dependency-free state-transition logic for DemoOrchestrator, pulled
// out of the Durable Object class specifically so it's unit-testable without
// any Cloudflare runtime (no storage, no fetch, no alarms - see
// test/state-logic.test.ts).

import { STAGE_ORDER, type StageName, type StageState, type OrchestratorState } from "./types";

export function initialStages(): Record<StageName, StageState> {
  const out = {} as Record<StageName, StageState>;
  for (const s of STAGE_ORDER) {
    out[s] = { status: "pending", startedAt: null, endedAt: null, exitCode: null };
  }
  return out;
}

export interface AlarmDecisionInput {
  now: number;
  state: OrchestratorState;
  sessionStartedAt: number | null;
  lastIngestAt: number | null;
  hardCapMs: number;
  silenceMs: number;
}

export interface AlarmDecision {
  /** Whether the alarm's active-watch logic applies at all (idle/done/failed = no). */
  isActive: boolean;
  /** True if either the hard cap or the silence timeout has been exceeded. */
  shouldDeallocate: boolean;
  reason: "hard_cap" | "silence_timeout" | null;
}

/**
 * The ONE alarm's core decision, exactly per the architecture spec: on each
 * fire, check BOTH (now - sessionStartedAt > hardCap) OR (now - lastIngestAt
 * > silence) and deallocate if either is true. Pure function so the two
 * timeout conditions are independently testable without waiting real
 * minutes or standing up a Durable Object.
 */
export function decideAlarmAction(input: AlarmDecisionInput): AlarmDecision {
  const activeStates: OrchestratorState[] = ["vm_starting", "running_stages", "deallocating"];
  if (!activeStates.includes(input.state)) {
    return { isActive: false, shouldDeallocate: false, reason: null };
  }
  const hardCapExceeded =
    input.sessionStartedAt !== null && input.now - input.sessionStartedAt > input.hardCapMs;
  const silenceExceeded =
    input.lastIngestAt !== null && input.now - input.lastIngestAt > input.silenceMs;

  if (hardCapExceeded) {
    return { isActive: true, shouldDeallocate: true, reason: "hard_cap" };
  }
  if (silenceExceeded) {
    return { isActive: true, shouldDeallocate: true, reason: "silence_timeout" };
  }
  return { isActive: true, shouldDeallocate: false, reason: null };
}

/**
 * Rate-limit check: given the count already recorded for the current
 * bucket (day or hour) and the configured cap, is a NEW attempt allowed?
 * Caller increments the counter separately, atomically, in DO storage -
 * this function only decides allow/deny given a count.
 */
export function isUnderCap(currentCount: number, cap: number): boolean {
  return currentCount < cap;
}

/**
 * Given the orchestrator's current top-level state, should a POST /api/start
 * mint a brand-new session, or hand back the existing one ("spectator
 * mode")? Only 'idle', 'done', and 'failed' are "no active run" states that
 * allow starting fresh.
 */
export function canStartNewSession(state: OrchestratorState): boolean {
  return state === "idle" || state === "done" || state === "failed";
}
