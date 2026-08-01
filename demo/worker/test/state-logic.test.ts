import { describe, expect, it } from "vitest";
import {
  decideAlarmAction,
  initialStages,
  isUnderCap,
  canStartNewSession,
} from "../src/state-logic";
import { STAGE_ORDER } from "../src/types";

describe("initialStages", () => {
  it("creates all stages pending in the documented order", () => {
    const stages = initialStages();
    expect(Object.keys(stages)).toEqual(STAGE_ORDER);
    for (const s of STAGE_ORDER) {
      expect(stages[s]).toEqual({
        status: "pending",
        startedAt: null,
        endedAt: null,
        exitCode: null,
      });
    }
  });
});

describe("decideAlarmAction", () => {
  const base = {
    now: 1_000_000,
    hardCapMs: 720_000, // 12min
    silenceMs: 180_000, // 3min
  };

  it("is inactive for idle/done/failed states", () => {
    for (const state of ["idle", "done", "failed"] as const) {
      const d = decideAlarmAction({
        ...base,
        state,
        sessionStartedAt: base.now - 10,
        lastIngestAt: base.now - 10,
      });
      expect(d.isActive).toBe(false);
      expect(d.shouldDeallocate).toBe(false);
    }
  });

  it("does not fire when well within both budgets", () => {
    const d = decideAlarmAction({
      ...base,
      state: "running_stages",
      sessionStartedAt: base.now - 60_000,
      lastIngestAt: base.now - 5_000,
    });
    expect(d.isActive).toBe(true);
    expect(d.shouldDeallocate).toBe(false);
    expect(d.reason).toBeNull();
  });

  it("fires on hard-cap exceeded even with fresh ingest", () => {
    const d = decideAlarmAction({
      ...base,
      state: "running_stages",
      sessionStartedAt: base.now - 800_000, // > 720_000
      lastIngestAt: base.now - 1_000, // fresh
    });
    expect(d.shouldDeallocate).toBe(true);
    expect(d.reason).toBe("hard_cap");
  });

  it("fires on silence timeout even with a recent session start", () => {
    const d = decideAlarmAction({
      ...base,
      state: "vm_starting",
      sessionStartedAt: base.now - 10_000,
      lastIngestAt: base.now - 200_000, // > 180_000
    });
    expect(d.shouldDeallocate).toBe(true);
    expect(d.reason).toBe("silence_timeout");
  });

  it("treats null sessionStartedAt/lastIngestAt as not-yet-exceeded", () => {
    const d = decideAlarmAction({
      ...base,
      state: "vm_starting",
      sessionStartedAt: null,
      lastIngestAt: null,
    });
    expect(d.shouldDeallocate).toBe(false);
  });

  it("is active (still watches) during deallocating", () => {
    const d = decideAlarmAction({
      ...base,
      state: "deallocating",
      sessionStartedAt: base.now - 10_000,
      lastIngestAt: base.now - 10_000,
    });
    expect(d.isActive).toBe(true);
  });

  it("prefers hard_cap reason when both conditions are true simultaneously", () => {
    const d = decideAlarmAction({
      ...base,
      state: "running_stages",
      sessionStartedAt: base.now - 900_000,
      lastIngestAt: base.now - 900_000,
    });
    expect(d.reason).toBe("hard_cap");
  });
});

describe("isUnderCap", () => {
  it("allows counts strictly below the cap", () => {
    expect(isUnderCap(0, 3)).toBe(true);
    expect(isUnderCap(2, 3)).toBe(true);
  });
  it("denies counts at or above the cap", () => {
    expect(isUnderCap(3, 3)).toBe(false);
    expect(isUnderCap(4, 3)).toBe(false);
  });
});

describe("canStartNewSession", () => {
  it("allows only idle/done/failed", () => {
    expect(canStartNewSession("idle")).toBe(true);
    expect(canStartNewSession("done")).toBe(true);
    expect(canStartNewSession("failed")).toBe(true);
    expect(canStartNewSession("vm_starting")).toBe(false);
    expect(canStartNewSession("running_stages")).toBe(false);
    expect(canStartNewSession("deallocating")).toBe(false);
  });
});
