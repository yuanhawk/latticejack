# B2 lever 4: GraalVM native-image — real-hardware results

**First positive B2 finding this project has produced.** Session resumption
and JVM tuning flags (see `benchmarks/samples/azure-cobalt100-2vcpu/README.md`)
both landed as honest null results on real hardware despite one of them
looking promising locally. This lever is the opposite: it was promising
locally (~145ms native vs ~826ms JVM, ~6x) *and* held up on real Arm64
hardware - in fact the real-hardware gap is larger than the local one.

## What's measured

Full cold-start latency: wall-clock time from launching the server process to
a client successfully completing a hybrid X25519MLKEM768 handshake and
receiving the echo reply, for the "after" (PQC) config. The server is
restarted fresh for every iteration (`EchoTlsServer` accepts exactly one
connection then exits, by design) - see `scripts/bench-native-image.sh`.

**This is a cold-start / "spin up one instance, handle one request" metric,
not steady-state warm-JVM throughput.** It's the metric GraalVM native-image's
core value proposition (AOT compilation, no JIT warmup, no classpath
jar-scanning at startup) directly targets, and the metric a serverless /
per-request-process / CLI-tool deployment shape would actually pay for. Warm,
long-running-server throughput (what `benchmarks/samples/azure-cobalt100-2vcpu/README.md`'s
B1 numbers measure) is a different question this session did not re-measure
for native-image - HotSpot's C2 JIT can out-optimize GraalVM's default
(non-PGO) AOT compilation once fully warmed, so a native-image server held
open for many requests is not guaranteed to win by the same margin, or at
all. Build once with `--pgo` and re-measure warm throughput if that shape
matters for a given deployment - not done here.

## Real Arm64 hardware (Azure Cobalt 100, Neoverse-N2, 2 vCPU)

3 independent 10-run sets, per this project's "always average 3 runs" rule
(raw output: `cobalt100-run1.txt`, `-run2.txt`, `-run3.txt`). All 60 runs
(30 native, 30 JVM) succeeded - no failures, and notably far less run-to-run
noise than this VM's percentile-latency benchmarks elsewhere in this
project (makes sense: at these magnitudes - hundreds of ms to low seconds -
per-iteration OS scheduling jitter of a few ms doesn't move the needle much).

| Config | Mean (30 runs) | Range |
|---|---|---|
| native-image | 290.3 ms | 289–293 ms |
| Regular JVM (pinned JDK 21) | 2292.2 ms | 2183–2431 ms |

**native-image is ~7.9x faster (87.3% lower cold-start latency)** than the
regular JVM for this workload on real target hardware. The local Mac
(Apple Silicon) signal that motivated trying this on real hardware was
~145ms vs ~826ms (~6x) - the real-hardware gap turned out *larger*, not
smaller, unlike this project's other two levers (session resumption held
up as ~null on both; JVM tuning flags looked like a real win locally and
evaporated on real hardware). Mechanistically this isn't surprising: JVM
class-loading + tiered-compilation warmup is a largely fixed per-process
cost that doesn't shrink with more CPU cores, while this VM's 2 vCPUs (vs.
10 threads on the Mac used for the local check) make classpath scanning and
JIT compilation *relatively* more expensive, not less.

## Correctness

Verified via the same method `run-after.sh` uses for the regular JVM build:
BC's FINEST debug logging (wired programmatically here rather than via
`-Djava.util.logging.config.file`, which doesn't survive native-image's
build-time class initialization - see `native-image/README.md`) shows 2
`ClientHello extensions` log lines, i.e. a HelloRetryRequest occurred -
proof the hybrid X25519MLKEM768 group was actually negotiated, not a silent
fallback to the classical `secp256r1` group. Confirmed on both the local Mac
build and this real-hardware build independently.

## Two GraalVM/BouncyCastle build-time incompatibilities

Found and fixed while getting `native-image` to produce a working binary at
all (BC 1.85's JCE provider isn't recognized as build-time-verified by
default, and forcing it to be breaks a different way via its DRBG
SecureRandom classes) - full mechanism and the exact flags for each:
`native-image/README.md`.

## Reproducing

```bash
# on the target machine (native-image is architecture-specific - Linux
# aarch64 binaries built on this Azure VM won't run on Apple Silicon or
# vice versa; rebuild fresh on whatever you're benchmarking):
export GRAALVM_HOME=/path/to/graalvm-jdk-21
./scripts/build-native-image.sh
./scripts/bench-native-image.sh 10
```
