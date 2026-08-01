import { describe, expect, it } from "vitest";
import { randomHexToken, timingSafeEqual, truncateLog, utcDayBucket, utcHourBucket } from "../src/util";

describe("randomHexToken", () => {
  it("produces hex strings of the expected length and reasonable entropy", () => {
    const a = randomHexToken(16);
    const b = randomHexToken(16);
    expect(a).toMatch(/^[0-9a-f]{32}$/);
    expect(a).not.toEqual(b);
  });
});

describe("timingSafeEqual", () => {
  it("returns true for equal strings", () => {
    expect(timingSafeEqual("abc123", "abc123")).toBe(true);
  });
  it("returns false for different strings of the same length", () => {
    expect(timingSafeEqual("abc123", "abc124")).toBe(false);
  });
  it("returns false for different lengths", () => {
    expect(timingSafeEqual("abc", "abcd")).toBe(false);
  });
});

describe("utcDayBucket / utcHourBucket", () => {
  it("formats consistently", () => {
    const t = Date.UTC(2026, 6, 28, 14, 37, 0); // 2026-07-28T14:37:00Z
    expect(utcDayBucket(t)).toBe("2026-07-28");
    expect(utcHourBucket(t)).toBe("2026-07-28T14");
  });
});

describe("truncateLog", () => {
  it("leaves short logs untouched", () => {
    expect(truncateLog("hello", 100)).toBe("hello");
  });
  it("truncates from the front and adds a marker when over the limit", () => {
    const log = "a".repeat(50) + "b".repeat(50);
    const out = truncateLog(log, 60);
    expect(out.length).toBe(60);
    expect(out).toContain("truncated");
    expect(out.endsWith("b".repeat(50).slice(-10))).toBe(true);
  });
});
