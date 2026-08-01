// Small dependency-free helpers, kept separate so they're trivially
// unit-testable without any Cloudflare runtime.

/** 32 random bytes, hex-encoded - used for session ids and bearer tokens. */
export function randomHexToken(bytesLen = 32): string {
  const bytes = new Uint8Array(bytesLen);
  crypto.getRandomValues(bytes);
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** Constant-time-ish string compare, to avoid short-circuit timing leaks on bearer token checks. */
export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) {
    // Still do a dummy comparison of equal-ish cost to avoid an obvious
    // early-return timing signal on length, though length leakage on a
    // fixed-length hex token is low-value information anyway.
    let dummy = 0;
    for (let i = 0; i < a.length; i++) dummy |= a.charCodeAt(i);
    return false;
  }
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

/** UTC calendar-day bucket, e.g. "2026-08-01", for the daily run cap. */
export function utcDayBucket(now: number = Date.now()): string {
  return new Date(now).toISOString().slice(0, 10);
}

/** UTC hour bucket, e.g. "2026-08-01T14", for the per-IP hourly throttle. */
export function utcHourBucket(now: number = Date.now()): string {
  return new Date(now).toISOString().slice(0, 13);
}

/**
 * Caps the live log buffer to `maxLen` chars, dropping from the front (a
 * ring-buffer-ish truncation) and prefixing a note so a polling client
 * knows some earlier output was dropped. Offsets returned by /api/log are
 * relative to the CURRENT (possibly-truncated) buffer, not some absolute
 * all-time counter - see README's ingest-contract section for why that's
 * an acceptable simplification for a bounded ~12-minute demo run.
 */
export function truncateLog(log: string, maxLen: number): string {
  if (log.length <= maxLen) return log;
  const marker = "[... earlier output truncated ...]\n";
  const keep = maxLen - marker.length;
  return marker + log.slice(log.length - keep);
}
