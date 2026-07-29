# B2 lever 5: mlkem-native (NEON-accelerated ML-KEM) — real-hardware results

The plan's own risk register (and this project's write-up) flagged native
NEON acceleration via `mlkem-native` as the "concrete next step" for
attacking ML-KEM's own crypto cost directly, having previously found every
JVM-level lever (session resumption, JVM tuning, JDK 25's own intrinsics)
gets at most single-digit-percent improvements. This benchmark runs
[`pq-code-package/mlkem-native`](https://github.com/pq-code-package/mlkem-native)
(the formally-verified, hand-optimized AArch64/x86_64 C implementation) on
the same real hardware as every other B2 finding, and compares it against
BC and JDK 25 (`benchmarks/mlkem-microbench/`).

**This is a standalone C-library benchmark, not an integration.** No FFI/FFM
bridge into the Java reference service was built - this measures the
*ceiling* available if such an integration were done (a real, non-trivial
engineering task, itself the actual "next step" this points at), not an
end-to-end result the way B2 levers 1-4 are.

## Results (ML-KEM-768, real Azure Cobalt 100 / Neoverse-N2, Linux aarch64)

3-run averaged (raw: `cobalt100-3run-raw.txt`), median per-run, then
averaged across runs — extremely low run-to-run variance (<0.2%), unlike
this VM's noisier percentile-latency benchmarks elsewhere in this project.
Confirmed the AArch64 NEON assembly backend was actually compiled and
selected (`auto.mk` detected `HOST_PLATFORM=Linux-aarch64`; 12 `.S.o`
AArch64 assembly objects in the build, not the portable C90 fallback).

| Operation | mlkem-native | BC (warm) | JDK 25 (warm) | vs BC | vs JDK 25 |
|---|---|---|---|---|---|
| keygen | 11.85 µs | 72.66 µs | 65.35 µs | **6.1x** | **5.5x** |
| encaps | 13.05 µs | 58.83 µs | 56.02 µs | **4.5x** | **4.3x** |
| decaps | 16.22 µs | 58.33 µs | 54.61 µs | **3.6x** | **3.4x** |
| **total** | **41.12 µs** | **189.82 µs** | **175.98 µs** | **4.6x** | **4.3x** |

(BC/JDK25 columns from `benchmarks/mlkem-microbench/README.md`'s existing
"warm," `warmup=200 measured=500` numbers, same hardware.) This is the
largest per-operation speedup any lever in this project has found -
roughly 4-6x, vs. JDK 25's built-in intrinsic only managing ~8.7% over BC
on this same chip (`benchmarks/mlkem-microbench/README.md`). Consistent
with the plan's original intuition that attacking the primitive directly,
not the JVM around it, was where the real headroom was.

## Methodology note: the wall-clock patch

`mlkem-native`'s own benchmark harness (`test/bench/bench_mlkem.c`) reports
median/percentile **CPU cycles**, requiring `CYCLES={PMU,PERF,MAC}` - PMU
and PERF both need elevated permissions this cloud VM doesn't grant by
default (`perf_event_paranoid`, ARM PMU userspace access), and root wasn't
used to avoid changing VM configuration for a benchmark. Patched the
`CYCLES=NO` fallback (`test/hal/hal.c`, upstream: constant `0`) to use
`clock_gettime(CLOCK_MONOTONIC)` nanoseconds instead - needs no special
permission, and gives directly-comparable time units to
`benchmarks/mlkem-microbench`'s JCA-level µs numbers rather than raw
(clock-rate-dependent) cycles. Patch: `hal-walltime.patch`, applied to
upstream commit `61c831345d8fec5b2ba9d727dddb486b7cce512a`. Not vendored
into this repo (matching how GraalVM isn't vendored either) - see
"Reproducing" below.

## Compared against B2 lever 4 (GraalVM native-image): different costs, different deployment shapes

These two levers are not competing solutions to the same problem - they
attack different, non-overlapping costs, and the magnitudes make that
concrete:

| Lever | Saves | When it applies |
|---|---|---|
| Lever 4: native-image | ~2001.9 ms (2292.2 → 290.3 ms) | **once per process launch** (JVM startup: classloading, jar scanning, JIT warmup) |
| Lever 5: mlkem-native (if integrated) | ~148.7 µs vs BC / ~134.9 µs vs JDK 25 | **every single handshake**, cold or warm |

For the exact deployment shape lever 4 was measured against - a fresh
process launched per handshake (serverless / CLI-tool / one-shot) -
native-image's one-time saving is about **13,460x larger** than
mlkem-native's per-handshake saving, so native-image is unambiguously the
higher-leverage lever there; mlkem-native's saving is a rounding error next
to a ~2ms startup cost.

The relationship inverts for a **long-running server handling many
connections on one already-started process** - the exact scenario lever
4 explicitly doesn't cover (its cold-start win amortizes toward zero as
connection count grows), and where levers 1-3 already established crypto
compute, not JVM/GC tuning, is the only remaining lever with room. There,
mlkem-native's saving is recurring and compounds linearly with connection
count: at roughly 2,001,900 µs / 148.7 µs ≈ **13,460 handshakes**,
mlkem-native's cumulative saving (if integrated) would overtake native
-image's one-time saving, and keeps growing without bound afterward while
native-image's contribution stays flat. **Concretely: which lever matters
more depends entirely on process lifetime (handshakes per process launch),
not on which optimization is "better" in the abstract** - a genuinely
different, complementary-levers conclusion rather than a single winner.

## An honest correction to an earlier claim in this project

Earlier B2 findings (`benchmarks/samples/azure-cobalt100-2vcpu/README.md`,
`WRITEUP.md` lever 2) state that on this hardware "crypto compute dominates
so completely (~88ms)" that JVM tuning flags become rounding error. That
explanation for *why the JVM-tuning lever found nothing* still holds - but
taken at face value, "(~88ms)" implausibly implies ML-KEM's own computation
is on the order of the *full* B1 handshake latency. **This benchmark's own
numbers contradict that literal reading**: even BC's slowest measured
operation set (189.82 µs combined keygen+encaps+decaps) is about three
orders of magnitude smaller than the ~88ms full-handshake p50 from B1. What
"crypto compute dominates" was accurately pointing at is that *among the
factors JVM tuning flags can influence* (GC, JIT tier), none come close to
mattering - not that ML-KEM math alone explains ~88ms of wall-clock time.

**Update: resolved, not just flagged.** Arm Performix hardware-level CPU
profiling (real Cobalt 100 hardware, the exact `run-benchmark.sh after`
workload that produces the 88ms number, 1.15M samples,
`collect_java_stacks=true`) found the answer directly: **~73% of all CPU
time is `libjvm.so` (62.96%, dominated by C2 JIT compiler passes -
`PhaseIdealLoop`, `PhaseChaitin`, `PhaseIterGVN` - plus GC and class
loading) and the bytecode `Interpreter` (10.04%, code not yet
JIT-compiled). All of BouncyCastle's code combined - TLS engine, ASN.1,
ML-KEM, classical crypto - is 5.55%.** JIT compilation and JVM
warmup/runtime overhead, not crypto compute, dominates - directly
explaining why lever 4 (GraalVM native-image, which eliminates JIT
entirely) found the largest win of any B2 lever. Full profiling
methodology, an honest caveat about a small amount of profiler-induced
overhead in the raw numbers, and reproduction steps:
[benchmarks/arm-performix-profile/README.md](../arm-performix-profile/README.md).

## Reproducing

```bash
git clone https://github.com/pq-code-package/mlkem-native.git
cd mlkem-native
git checkout 61c831345d8fec5b2ba9d727dddb486b7cce512a  # commit this was run against
git apply /path/to/latticejack/benchmarks/mlkem-native-bench/hal-walltime.patch
make CYCLES=NO bench_768
./test/build/mlkem768/bin/bench_mlkem768   # values are nanoseconds, not cycles, with the patch applied
```
