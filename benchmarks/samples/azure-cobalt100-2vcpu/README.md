# B1 baseline — Azure Arm64 (Cobalt 100), 2 vCPU

The first genuine Arm64 hardware run, not a dev-machine dry run. Captured
2026-07-29 on an Azure `Standard_D2pls_v6` VM (2 vCPU, 4GB, Ubuntu 24.04 LTS
arm64) — Neoverse-N2 cores, SVE2-capable, Azure's own Cobalt 100 Arm silicon.
200 measured handshakes + 20 warmup per pass, concurrency 8 for throughput.
Raw per-iteration data: the CSVs alongside this file. Reproduce with
`./run-benchmark.sh {before|after} 200 20 8`.

## Results

| Metric | Before (classical) | After (hybrid PQC) | Delta |
|---|---|---|---|
| Latency p50 | 46.0 ms | 90.4 ms | +96.5% |
| Latency p95 | 52.5 ms | 98.9 ms | +88.4% |
| Latency p99 | 55.2 ms | 105.8 ms | +91.7% |
| Latency mean | 34.9 ms | 88.4 ms | +153.4% |
| Client CPU / handshake | 4.12 ms | 7.06 ms | +71.3% |
| Throughput (concurrency 8) | 159.8 handshakes/sec | 67.9 handshakes/sec | −57.5% |
| Bytes on wire, total | 5254 | 5527 | +5.2% |
| Bytes on wire, client→server | 1647 | 2936 | +78.3% |
| Bytes on wire, server→client | 3607 | 2591 | −28.2% |

## Reading these numbers honestly

- **The overhead here is much larger than on a dev machine.** An earlier
  smoke-test on Apple Silicon (many more cores, no contention) showed only
  a ~57% p50 latency increase and ~40% throughput drop for the same
  before/after comparison. On this 2 vCPU cloud instance, both effects
  roughly double. That's a real finding, not noise: a modest-core-count
  cloud instance has far less slack to absorb ML-KEM's added compute cost,
  which is exactly the kind of thing B1 characterization is supposed to
  surface — the local dry run alone would have understated the practical
  Arm64 cost of this migration.
- **Client→server bytes-on-wire nearly doubling (1647→2936)** is ML-KEM768's
  public key + ciphertext, which are far larger than X25519's 32-byte key
  share — the expected, textbook PQC bandwidth cost.
- **Latency's min/max spread is wide** (before: 3.9-58.0ms; after:
  48.1-114.7ms) even with 20 discarded warmup iterations. On a 2 vCPU VM,
  the client and server JVMs compete for the same limited cores — this is a
  legitimate characteristic of small cloud instances, not a harness bug, but
  it does mean these absolute numbers are instance-size-dependent; the
  *relative* before/after delta is the more portable finding.
- **This is the "before/naive" baseline, not yet a "tuned" comparison.**
  Component B2 (Arm64-specific tuning — JVM/GC, session resumption, etc.)
  hasn't started; these numbers are what B2's optimization work will be
  measured against.
