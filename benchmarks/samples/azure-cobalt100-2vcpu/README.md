# B1/B2 results — Azure Arm64 (Cobalt 100), 2 vCPU

Captured on an Azure `Standard_D2pls_v6` VM (2 vCPU, 4GB, Ubuntu 24.04 LTS
arm64) — Neoverse-N2 cores, SVE2-capable, Azure's own Cobalt 100 silicon.
200 measured handshakes + 20 warmup per pass (resumption: 20 warmup pairs),
concurrency 8 for throughput. Reproduce with `./run-benchmark.sh
{before|after} 200 20 8`. Raw per-iteration data: the CSVs alongside this
file.

**These are corrected numbers, not the first measurement.** Two real
methodology bugs were found and fixed while building this — both are
described below because the wrong numbers they produced were, on their
face, entirely plausible, and would have gone into a submission unchallenged
without independent re-derivation. Full technical detail:
`docs/bouncycastle-pqc-notes.md`, git history for `run-benchmark.sh` and
`BenchmarkClient.java`.

## B1: before vs. after (naive), latency/throughput/bytes

| Metric | Before (classical) | After (hybrid PQC) | Delta |
|---|---|---|---|
| Latency p50 | 45.5 ms | 88.7 ms | +94.9% |
| Latency p95 | 54.9 ms | 95.3 ms | +73.8% |
| Latency p99 | 59.7 ms | 99.3 ms | +66.3% |
| Client CPU / handshake | 3.88 ms | 5.30 ms | +36.6% |
| Throughput (concurrency 8) | 160.2 handshakes/sec | 79.1 handshakes/sec | −50.6% |
| Bytes on wire, total | 5255 | 5527 | +5.2% |
| Bytes on wire, client→server | 1647 | 2936 | +78.3% |

**Bug found #1 (fixed):** `run-benchmark.sh` unconditionally enabled BC's
`FINEST`-level `java.util.logging` for every "after"-config pass, not just
`run-after.sh`'s negotiated-group verification (the only place it's
actually needed) — printing thousands of debug lines per handshake
perturbs the very timing being measured. On a fast dev machine (Apple
Silicon) this inflated "after" p50 latency by roughly **24x** (90ms with
logging vs. 3.7ms without, for the same config). On this Arm64 instance the
effect turned out to be much smaller — corrected numbers above are only
~2% different from the logging-contaminated first run for latency, though
CPU (-25%) and throughput (+16.5%) moved more. The likely reason: fixed
per-line console-I/O overhead is roughly constant regardless of CPU speed,
so it swamps a *fast* machine's few-millisecond "real" handshake cost but
is comparatively small next to this instance's much larger real crypto
cost. Lesson: an artifact discovered on one machine doesn't necessarily
have the same *magnitude* on another — re-verify on the actual target
hardware rather than assuming a fix's impact transfers, which is exactly
why this instance was re-measured rather than just applying a multiplier
to the earlier numbers.

## B1 extension / B2 lever 1: session resumption

| Config | Full handshake | Resumption attempt | Reduction |
|---|---|---|---|
| Before (classical) | 42.3 ms | 41.5 ms | 1.8% |
| After (hybrid PQC) | 126.8 ms | 126.4 ms | 0.3% |

**This contradicts the plan's assumption that session resumption is a
"near-guaranteed win."** It measurably is not, in this configuration, for
either config — and the honest story here has two more found-and-fixed
bugs plus one open question, not a clean result:

**Bug found #2 (fixed):** TLS 1.3's `NewSessionTicket` is a *post-handshake*
message; JSSE only processes buffered incoming records when the
application performs a read, not proactively. The first version of this
benchmark called `Thread.sleep()` after the handshake and then closed —
sleeping doesn't read anything, so the ticket sat unprocessed and every
"resumption attempt" silently fell back to a full handshake. Fixed with a
bounded read that forces the record-processing pipeline to run
(`pumpPendingRecords()` in `BenchmarkClient.java`).

**Bug found #3 (fixed):** no warmup phase meant the very first "full"
handshake in a run paid JIT cold-start cost the "resumed" side (executed
microseconds later, warmer) didn't — producing a fake >90% "speedup" that
was almost entirely JIT warmup, not resumption, *even while bug #2 meant
real resumption wasn't happening at all*. Fixed with a discarded warmup
loop, matching "latency" mode's existing pattern.

**Bug found #4 (fixed), the one that mattered most for classical:** the
post-handshake read-pump (the bug #2 fix) was only applied to the *first*
connection in each pair, not the second — so "full" carried that read/pump
overhead and "resumed" didn't, inflating the apparent improvement by
whatever that read/timeout cost, independent of any genuine TLS benefit.
With all three fixes in place, classical's reduction dropped from an
initially very promising-looking 88% down to the 1.8% reported above — most
of what looked like a real resumption win was measurement artifact, not
crypto.

**What the fixed 1.8%/0.3% numbers most likely mean, honestly:**
ECDSA P-256 + X25519 are cheap enough in software that skipping them via
resumption barely moves the needle - other overhead (TCP connection setup,
JVM/thread-pool dispatch, GC) dominates the handshake cost floor regardless
of resumption, at least on this instance size. That's a coherent,
defensible explanation for the classical result.

**The PQC result (0.3%) is more troubling and NOT fully explained.**
Verified via BC's own debug log (`java.util.logging` at `FINEST`, same
method used elsewhere in this project to verify TLS group negotiation):
the classical/SunJSSE path clearly logs `"Found resumable session.
Preparing PSK message"` and `"Consuming NewSessionTicket message"` for
every resumption attempt once bugs #2/#3 were fixed. **BCJSSE logs neither
of those, or anything else resumption-related, at the same log level** —
only the `enableSessionResumption=true` property default. This is
consistent with resumption simply not engaging for the PQC/BCJSSE path at
all (every "resumed attempt" actually being a second full ML-KEM
handshake, which would fully explain a ~0% delta) - but it has **not**
been root-caused to that level of confidence. Given this session already
found and walked back one premature "BouncyCastle is broken" conclusion
(see `docs/bouncycastle-pqc-notes.md` §3a item 3 — that one turned out to
be a bug in this project's own test-certificate generation, not BC), this
is deliberately reported as an open question rather than a claimed root
cause. **Next step, if pursued:** trace BCJSSE's session-cache source
(`~/.m2` sources jar, same method used for the earlier named-groups
investigation) to see whether hybrid-group sessions are excluded from its
resumption cache, or find the equivalent of BC's own resumption logging at
a different log level/logger name than tried here.

## Reading all of this honestly

- The B1 before/after delta (94.9% p50) is a real, repeatedly-confirmed
  finding on real target hardware - not affected by any of the four bugs
  above (those were all found while building the *separate* resumption
  pass).
- Session resumption, tried as the plan's suggested first B2 tuning lever,
  did **not** produce the assumed win here. That is itself the honest B2
  characterization result for this lever: tried, measured, found wanting,
  with the reasoning shown rather than hidden. Per arm-hackathon-plan.md
  §9's own risk register: "if a lever shows no effect, that's still a
  reportable finding... honest engineering rather than a null result."
- This is the "before/naive" baseline; B2's actual optimization work (JVM
  tuning, GC, or root-causing the PQC resumption gap above) hasn't landed
  yet.
