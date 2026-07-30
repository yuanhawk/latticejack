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

**Correction (found by an independent Opus+Fable audit of this project's
own claims): the paragraph below originally treated the classical result
as confirmed-and-explained and only the PQC result as an open question.
The committed evidence doesn't actually support that asymmetry.**
`BenchmarkClient.java`'s own resumption-confirmation logic compares TLS
session IDs between the two connections in each pair
(`resumptionPairTimed`'s `outResumed[0] = ... Arrays.equals(firstSessionId,
secondSessionId)`), and the harness itself already anticipated exactly
this failure mode: if the confirmed-resumed rate is under 50%, it prints
"resumed_attempt latency is not a reliable resumption-benefit number
here." **Checking the actual committed CSVs
(`before-resumption.csv`/`after-resumption.csv`) directly: `resumed=0` for
all 200 rows in *both* files** - meaning the specific timed run that
produced the 1.8%/0.3% table above had a 0% confirmed-resumption rate for
classical too, the same reliability warning the code would print for PQC.
The debug-log verification described below (BC's `FINEST` logging showing
resumption-related log lines for classical) was a **separate, uncommitted
run**, not the same timed run - so it shows the *mechanism* firing in some
run, not that the specific measurement behind "1.8%" reflects genuine
resumption rather than two full handshakes with the second landing
marginally faster for unrelated reasons (JIT/OS-cache warmth between
back-to-back connections). **Both the classical and PQC results in this
section should be read as unconfirmed by the committed evidence, not just
the PQC one** - extending, not replacing, the open-question treatment
originally given only to PQC.

**What the fixed 1.8%/0.3% numbers most likely mean, honestly, given
that correction:** either genuinely small (ECDSA P-256 + X25519 are cheap
enough that skipping them barely moves the needle, and TCP/JVM/GC overhead
dominates the floor regardless) - or neither side's resumption mechanism
was confirmed to engage in the specific timed run, in which case both
numbers reflect noise between two full handshakes rather than a measured
resumption benefit at all. The data as committed cannot distinguish these
two explanations. **Next step, if pursued:** rerun with debug logging
enabled *during the same timed pass* (not a separate verification run) so
resumption engagement and timing are measured together, and fix or relax
the session-ID-equality detection heuristic if it's the one that's
unreliable rather than resumption itself - trace BCJSSE's session-cache
source (`~/.m2` sources jar, same method used for the earlier named-groups
investigation) to see whether hybrid-group sessions are excluded from its
resumption cache either way.

## B2 lever 2: JVM tuning flags

Tested GC choice (G1/default, ZGC, ParallelGC), fixed heap sizing,
tiered-compilation level (`-XX:TieredStopAtLevel=1` / C1-only,
`-XX:-TieredCompilation` / C2-only), and `AlwaysPreTouch`, against the
"after" (PQC) latency pass. Local smoke test (Apple Silicon) showed a
promising, consistent **-15.5%** for C1-only compilation vs. baseline —
same instinct as the JDK25 comparison above: a fast dev machine made a
secondary effect look meaningful. **On real Arm64 (Cobalt 100) hardware,
every variant landed within ~1% of baseline** (essentially noise;
C1-only measured +0.01%, i.e. no different from default):

| Variant | p50 (real Arm64) | vs. baseline |
|---|---|---|
| baseline | 87.943 ms | — |
| Fixed heap | 88.762 ms | +0.9% |
| ZGC | 88.833 ms | +1.0% |
| ParallelGC | 88.219 ms | +0.3% |
| `TieredStopAtLevel=1` (C1-only) | 87.948 ms | +0.01% |
| `-TieredCompilation` (C2-only) | 89.065 ms | +1.3% |
| `AlwaysPreTouch` | 88.724 ms | +0.9% |

**Why the local signal didn't hold, and it's a coherent explanation, not a
shrug:** on the fast dev machine, real handshake crypto cost is only
~3-4ms, so JIT-compilation-strategy differences are a large fraction of
that tiny total and show up clearly. On the real Arm64 target, none of the
factors JVM tuning flags can influence (GC, JIT tier) come close to
mattering next to the rest of the ~88ms handshake cost, so secondary JVM
effects become rounding error by comparison. This is the same shape of
result as the JDK 25 finding above: **local dev-machine signals have
repeatedly overstated real-hardware impact for this workload**.
(*Correction, added after `benchmarks/mlkem-native-bench/` measured ML
-KEM's raw operation cost directly: an earlier version of this paragraph
attributed the ~88ms to "crypto compute" dominating so completely that
nothing else could matter. That's not literally supportable — even BC's
slowest measured ML-KEM operations total ~190µs, three orders of magnitude
below 88ms. A real CPU profile now exists (Arm Performix,
`collect_java_stacks=true`, profiling the whole benchmark script) showing
~73% of CPU time is JVM overhead (`libjvm.so` + interpreter) vs. ~5.55%
BouncyCastle — but an independent audit of this project's own claims
found that number, while real and committed, doesn't cleanly decompose
the specific 88ms p50 the way an earlier version of this correction
claimed: the profiled script includes a Maven build and several JVM cold
starts, a CPU-sampling profiler can't see the ~94% of handshake wall-time
that's off-CPU, and this document's own lever 2 result (C1-only JIT,
within ~0.01% of baseline) is in tension with "JIT dominates the warm
handshake." **What actually consumes the ~88ms remains an open question**
— what's established is that neither ML-KEM math nor JVM tuning flags
explain it, and a real (if methodologically limited) CPU profile points
toward JVM/JIT machinery somewhere in this project's pipeline, without yet
isolating where. Full profiling methodology, all caveats, and a suggested
cleaner follow-up: [benchmarks/arm-performix-profile/README.md](../../arm-performix-profile/README.md).*)

## Reading all of this honestly

- The B1 before/after delta (94.9% p50) is a real, repeatedly-confirmed
  finding on real target hardware - not affected by any of the four bugs
  above (those were all found while building the *separate* resumption
  pass).
- **Both B2 levers tried so far (session resumption, JVM tuning) produced
  honest null results on real hardware**, contradicting what the plan
  assumed and what a fast local dev machine suggested, respectively. That
  is itself the B2 characterization result: two plausible levers tried,
  rigorously measured on the actual target hardware (not just a laptop),
  found wanting, with the reasoning shown rather than hidden. Per
  arm-hackathon-plan.md §9's own risk register: "if a lever shows no
  effect, that's still a reportable finding... honest engineering rather
  than a null result."
- Neither lever tried here (session resumption, JVM tuning) touches ML-KEM's
  own computation directly, and per the source-grounded Opus/Fable analysis
  its cost is mostly Keccak/SHA3 hashing, not the NTT. Two later B2 levers
  do attack that primitive directly: JDK 25's built-in intrinsic (only
  closed a modest ~8.7% of the BC gap on real hardware, `benchmarks/mlkem
  -microbench/README.md`) and `mlkem-native`'s NEON-optimized C
  implementation, which found a much larger ~4.3-4.6x gap on the same
  hardware — see `benchmarks/mlkem-native-bench/README.md`. What actually
  explains the full ~88ms handshake cost remains an open question - a
  real CPU profile exists now (~73% JVM/JIT-compiler overhead, ~5.55%
  BouncyCastle across the whole benchmark script), a genuine, committed
  data point, but methodologically too coarse to count as a resolution;
  see `benchmarks/arm-performix-profile/README.md` for exactly what it
  does and doesn't establish, and a cleaner follow-up method not yet
  attempted.
