# Latticejack — Submission Write-up

Arm AI Optimization Challenge, Track 2 (Migration/Adoption). This is the
formal submission write-up (arm-hackathon-plan.md §6); for the developer-facing
quick reference see [README.md](README.md).

## Project Overview

Java shops migrating off classical crypto (RSA/ECDSA/X25519) to
post-quantum crypto have no real playbook — the migration path is
undocumented, the Arm64 cost of running PQC in production is unmeasured,
and the handful of public benchmarks that exist don't survive contact with
real target hardware. Latticejack is a working migration reference (a Java
mTLS service running classical *and* hybrid post-quantum TLS 1.3, verified
negotiating for real, not just "compiles") built and benchmarked entirely
on real Arm64 server silicon (Azure Cobalt 100/Neoverse-N2, provisioned
during this project, not simulated or estimated), with an Arm64
optimization investigation (Component B2) that is the actual technical
core of this submission.

**Why this should win:** every claim in this project is backed by a
measurement taken on real target hardware, and every place a measurement
turned out wrong or misleading, that's documented too, not quietly
corrected. Across this project we found and fixed real bugs in our own
crypto-migration script (a certificate signature-algorithm mismatch that
looked exactly like a BouncyCastle bug and was initially misdiagnosed as
one — caught by an independent adversarial review before it became a false
public bug report), our own benchmark harness (a debug-logging flag that
silently inflated PQC latency numbers by up to 24x depending on hardware),
and our own optimization experiments (a session-resumption "win" that
looked like an 88% improvement and was actually three compounding
measurement bugs — a session ticket never being read so "resumption" was
silently falling back to full handshakes, JIT cold-start masquerading as a
resumption benefit, and asymmetric instrumentation between the two timed
code paths — with a true, fixed effect of under 2%).

**Eight optimization levers, not one or two** — and the two biggest wins
are backed by hardware evidence, not just correlation. GraalVM
native-image eliminates JVM startup entirely and measures **~7.9x faster
cold-start**; a real Java FFM integration of hand-tuned NEON ML-KEM
assembly (mlkem-native) measures **~4.0x faster end-to-end**, ~85% of its
standalone ceiling surviving the FFI crossing cost. Neither is a guess:
**Arm's own hardware-level profiling tool (Arm Performix)**, pointed at
the exact benchmark producing this project's headline 88ms handshake
number, found directly that ~73% of all CPU time is JVM/JIT-compiler
overhead and only ~5.55% is BouncyCastle's own crypto and protocol code
combined — hard evidence for exactly the mechanism native-image attacks,
not inference. A parallel investigation (RustCrypto vs. mlkem-native vs.
`rustpq/pqcrypto`) answered a real, separate question with real data too:
Rust's memory safety costs nothing relative to Java, but the ~4x gap to
mlkem-native was always about hand-tuned, chip-specific assembly
investment, not which language calls it — confirmed with both a negative
result (portable Rust: ~3x slower) and a positive control (Rust wrapping
the same hand-tuned C: within 3.3% of native).

That same discipline extends past the optimization core: **Component C**
(the `pqc-authoring` guardrail skill and a CycloneDX CBOM) is built, not
just planned, and each carries its own verification — the guardrail's
checklist is proven against a real regression this project hit once, not
just plausible-sounding; the CBOM is validated against the actual
published CycloneDX 1.6 JSON schema, and it stays honest even when that's
inconvenient (the "after" CBOM still lists the certificate-signature
algorithm as unmigrated, because it genuinely is). Provisioning real Arm64
infrastructure, being willing to re-measure three times when a promising
single-run number looked too good to trust, and reaching for Arm's own
tooling to settle an open question with data rather than leaving it
open indefinitely — that discipline, applied consistently across eight
levers and two components, is the actual differentiator here.

## Functionality / Output

### Component A — the migration reference (working)

A raw-JSSE Java mTLS service in two configurations, verified on real
Arm64 hardware, not just locally:

- **`./run before`** — classical TLS 1.3 (ECDSA P-256 + X25519)
- **`./run after`** — hybrid post-quantum TLS 1.3 (`X25519MLKEM768` key
  exchange via BouncyCastle 1.85's BCJSSE provider), **self-verifying**:
  the script asserts a HelloRetryRequest occurred, proving the hybrid
  group actually negotiated rather than trusting a "handshake complete"
  log line that could be silently classical (arm-hackathon-plan.md §8's
  explicit correctness bar)

ML-DSA (post-quantum certificate authentication) is deliberately deferred
to a stretch goal, not implemented — see "Scope" in
[MIGRATION.md](MIGRATION.md) for the reasoning (ML-DSA-over-TLS is
experimental upstream in BouncyCastle with no documented enable path;
hybrid key exchange is the quantum-relevant half of this migration
regardless, since it defends harvest-now-decrypt-later, which doesn't
require authentication to also be post-quantum yet).

Getting to a working "after" required real debugging, documented in full
in [docs/bouncycastle-pqc-notes.md](docs/bouncycastle-pqc-notes.md) §3a:
`SSLContext.getDefault()` silently breaks BC credential resolution for
*every* cipher suite, not just PQC ones (found and fixed); and a
long, initially-wrong investigation into "why doesn't the hybrid group
negotiate" that an independent Opus audit correctly root-caused to our own
test-certificate generation script (a signature-algorithm/CA-key mismatch),
not BouncyCastle — a mistake we caught before it became a false public bug
report, which is exactly the kind of check this project tries to model
throughout.

### Component B1 — Arm64 characterization (working, on real hardware)

`./run-benchmark.sh {before|after}` measures handshake latency
(p50/p95/p99), throughput under concurrency, bytes-on-wire, and
session-resumption behavior — a from-scratch, dependency-free harness
(no HdrHistogram, no benchmarking framework), reused directly for B2.

**Real Azure Cobalt 100 (Neoverse-N2) results**, corrected after finding
and fixing a debug-logging bug that had contaminated the first
measurement pass (full story in
[benchmarks/samples/azure-cobalt100-2vcpu/README.md](benchmarks/samples/azure-cobalt100-2vcpu/README.md)):

| Metric | Before (classical) | After (hybrid PQC) | Delta |
|---|---|---|---|
| Latency p50 | 45.5 ms | 88.7 ms | +94.9% |
| Throughput (concurrency 8) | 160.2 handshakes/sec | 79.1 handshakes/sec | −50.6% |
| Bytes on wire, client→server | 1647 | 2936 | +78.3% |

### Component B2 — Arm64 optimization (the technical core of this submission)

The plan's 40-point criterion rewards genuinely leveraging Arm-powered
platforms with efficiency-minded design, not just deploying on Arm64. This
project tried eight concrete optimization levers, measured every one on
real hardware (not a laptop), and reports what actually happened —
including two honest null results, one precisely root-caused, non-obvious
finding, and three confirmed positive results (one end-to-end, one
measuring the ceiling for a not-yet-integrated one, and one modest
exploratory pure-Java result), all cross-validated with independent-model
review (Opus and Fable, given the same question, converged independently)
or 3-run real-hardware averaging before being trusted.

**Lever 1 — session resumption.** The plan's own risk register called this
a "near-guaranteed win." It measurably was not: **1.8% (classical) / 0.3%
(PQC)** benefit, after finding and fixing three real bugs in the benchmark
itself (a `Thread.sleep()` that never actually triggered JSSE to process
the post-handshake session ticket, so "resumption" was silently falling
back to full handshakes; a missing warmup phase that let JIT cold-start
masquerade as a resumption speedup; and asymmetric instrumentation between
the timed "full" and "resumed" code paths). Full writeup, including why
the *fixed* PQC result is flagged as an open question rather than a closed
one: [benchmarks/samples/azure-cobalt100-2vcpu/README.md](benchmarks/samples/azure-cobalt100-2vcpu/README.md).

**Lever 2 — JVM tuning (GC choice, heap sizing, tiered-compilation
level).** A promising local signal on a fast dev machine
(`-XX:TieredStopAtLevel=1` showing −15.5% latency) **completely evaporated
on real Arm64 hardware** — every variant landed within ~1% of baseline.
The explanation is coherent, not a shrug: on a fast machine, real crypto
compute is a few milliseconds, so JIT-strategy differences are a large
fraction of a tiny total; on the real target, none of the factors JVM
tuning flags can influence (GC, JIT tier) come close to mattering next to
the rest of the handshake cost, so secondary JVM effects become rounding
error. (An earlier version of this explanation said crypto compute
"dominates so completely (~88ms)," read literally as ML-KEM math itself
explaining the full 88ms handshake latency — lever 5 below shows that's not
supportable: even BC's raw ML-KEM operations only total ~190µs, three
orders of magnitude smaller. Corrected there rather than left standing now
that contradicting data exists.)

**Lever 3 — is JDK 25's built-in ML-KEM actually faster, and by how much
on real Arm64?** JDK 25 (JEP 496) ships hand-written AArch64 intrinsics
for its own built-in ML-KEM, reported elsewhere at roughly 2x, "on par
with OpenSSL." Measured directly against BouncyCastle on real Cobalt 100
hardware (same JCA API, same JVM, only the provider differs — isolating
"which implementation" from "which JDK"), averaged across 3 independent
runs after an initial single-run number proved unreliable:

| Operation | BC | JDK 25 | Advantage |
|---|---|---|---|
| keygen | 72.66 µs | 65.35 µs | ~11.2% |
| encaps | 58.83 µs | 56.02 µs | ~5.0% |
| decaps | 58.33 µs | 54.61 µs | ~6.8% |

Not 2x. And at realistic cold-start conditions (5 warmup iterations,
closer to what one real TLS connection experiences than a fully-warmed
loop), **BC was consistently faster** than JDK 25's own "optimized"
implementation across all 3 runs for encaps/decaps.

This was then root-caused, not just measured, by isolating the actual
HotSpot mechanism: `-XX:+UnlockDiagnosticVMOptions -XX:+PrintFlagsFinal`
exposes a real, default-on `UseKyberIntrinsics` flag. A direct A/B toggle
(same JVM, same hardware, only the flag differs) shows the intrinsic
contributes **~8.7% on Neoverse-N2 vs. ~51% on Apple Silicon** — confirmed
active on both, just ~6x weaker on our actual target chip. A second,
more specific flag (`UseSHA3Intrinsics`, since Keccak/SHA3 hashing —
not the NTT — is ML-KEM's dominant cost, per independent Opus/Fable
analysis of BouncyCastle's actual source) turned out to **default to
`false` on Neoverse-N2 but `true` on Apple Silicon**. Forcing it on only
yielded ~3.9%, inconsistent in direction across runs — evidence that
HotSpot's Neoverse default is already empirically correct, not an
oversight. A specific, plausible fix for this exact gap exists upstream
(JDK-8359256, backed by OpenJDK's own Graviton 3 measurements) — checked
directly by installing JDK 26.0.2 on the same hardware rather than trusting
secondhand sources: **the fix has not shipped**, flag defaults are
identical to JDK 25. Full mechanism-level writeup:
[benchmarks/mlkem-microbench/README.md](benchmarks/mlkem-microbench/README.md).

**Levers 1–3 share a throughline:** ML-KEM's own cryptographic computation
(dominated by Keccak/SHA3 hashing, not the NTT) is the real, hard-to-avoid
cost *once a connection is being negotiated*, and every secondary lever
tried against that — session caching, JVM flags, even switching to a
"faster" JDK-native implementation — is noise or single-digit-percent by
comparison. That's a defensible, three-times-independently-confirmed
finding for that specific cost.

**Lever 4 — GraalVM native-image — targets a different cost entirely, and
wins decisively.** All three levers above assume a JVM is already running
and warmed; they attack the *handshake's* crypto cost. Native-image instead
eliminates the *JVM startup* cost (class loading, classpath jar scanning,
tiered-compilation JIT warmup) via ahead-of-time compilation to a native
Arm64 executable. Measured as full cold-start latency — process launch to
a completed hybrid-PQC handshake, server restarted fresh each iteration —
against the same pinned-JDK21 regular JVM, 3-run averaged (60/60 runs
succeeded, real Azure Cobalt 100 hardware):

| Config | Mean cold-start (30 runs) |
|---|---|
| native-image | 290.3 ms |
| Regular JVM (JDK 21) | 2292.2 ms |

**~7.9x faster, an 87.3% reduction.** Unlike every other lever tried this
session, the real-hardware gap here is *larger* than the local (Apple
Silicon) signal that motivated trying it (~6x locally), not smaller —
the opposite of the local-overstates-real pattern lever 2 and the JDK 25
comparison both showed. Two GraalVM/BouncyCastle build-time
incompatibilities had to be found and fixed to get a working binary at all
(BC's JCE provider isn't recognized as build-time-verified by default, and
forcing it to be breaks differently via BC's DRBG `SecureRandom` classes) —
full mechanism: `native-image/README.md`. Correctness was verified the same
way as the regular JVM build (HelloRetryRequest observed, confirming real
hybrid negotiation, not a silent classical fallback).

**This is a cold-start metric, not warm steady-state throughput** — it's
the metric that matches a serverless / per-request-process / CLI-tool
deployment shape, not necessarily a long-running server handling many
connections (HotSpot's C2 JIT can out-optimize GraalVM's default,
non-PGO AOT compilation once fully warmed; not re-measured here).

**Unlike lever 3, this is not an Arm-specific mechanism, and that's stated
plainly rather than blurred.** The AOT-vs-JIT tradeoff native-image exploits
(no classpath jar scanning, no bytecode verification pass, no tiered-JIT
warmup at every process launch) is a general HotSpot/GraalVM property,
identical on x86-64 — not an Arm instruction or Arm-specific compiler pass,
the way lever 3's hand-written AArch64 intrinsics explicitly were shown to
be (directly measured against the same flags on Apple Silicon to quantify
how the effect's magnitude differs by chip). No x86 baseline was run here,
so the ~7.9x gap cannot be claimed as Arm-*amplified* the way lever 3's
was. What *is* established: the binary builds and runs correctly on Arm64
specifically (two independent toolchains — Apple Silicon locally, Linux
aarch64/Neoverse-N2 on the actual target hardware — each surfacing its own
build-time incompatibilities to work through), and the win is real and
large on the hardware this submission actually targets, which is what
Track 2's "genuinely leveraging Arm-powered platforms" criterion asks a
deployment to demonstrate. One plausible (not measured) connection worth
naming honestly: this VM's 2 vCPUs are typical of how many Arm cloud SKUs
are priced relative to x86 — a fixed per-process JVM startup tax is
relatively more expensive on a smaller instance regardless of architecture,
so this lever plausibly matters more in a typical Arm cloud deployment
shape even though the underlying mechanism isn't Arm-specific. Full
results: [benchmarks/graalvm-native-image/README.md](benchmarks/graalvm-native-image/README.md).

**Lever 5 — mlkem-native, the primitive-level lever levers 1-3 pointed at,
measured both as a ceiling and as a real integration.**
[`pq-code-package/mlkem-native`](https://github.com/pq-code-package/mlkem-native)
is a formally-verified C implementation with hand-optimized AArch64 NEON
assembly. First run standalone (the *ceiling* - confirmed compiling and
running the actual NEON backend, not a portable C fallback), then actually
called from Java via the Foreign Function & Memory API (`java.lang.foreign`
- no JNI, no native-image required) to measure what survives the real FFI
crossing cost. Both 3-run averaged on the same real Cobalt 100 hardware:

| Operation | Raw C ceiling | **Via FFM (real)** | BC (warm) | JDK 25 (warm) | vs BC | vs JDK 25 |
|---|---|---|---|---|---|---|
| keygen | 11.85 µs | 15.82 µs | 72.66 µs | 65.35 µs | 4.6x | 4.1x |
| encaps | 13.05 µs | 15.11 µs | 58.83 µs | 56.02 µs | 3.9x | 3.7x |
| decaps | 16.22 µs | 16.39 µs | 58.33 µs | 54.61 µs | 3.6x | 3.3x |
| **total** | **41.12 µs** | **47.32 µs** | **189.82 µs** | **175.98 µs** | **4.0x** | **3.7x** |

**~85% of the raw ceiling survives real integration** (total FFI overhead
+15.1%, but it shrinks the longer the native call runs - keygen absorbs
+33.5% relative overhead, decaps only +1.0%), landing at **~3.7-4.0x
faster end-to-end** rather than the ceiling's ~4.3-4.6x — still the
largest *realized* per-operation gap this project found, dwarfing lever
3's ~8.7% JDK 25 intrinsic gain and lever 6's ~6.7% exploratory Vector API
result by roughly two orders of magnitude. Correctness verified via
shared-secret agreement across the real FFM boundary (encaps/decaps
results match after a full keypair→encaps→decaps round trip, 50 trials),
not a reimplementation-correctness check like lever 6 needed, since this
calls an already-correct library rather than re-deriving the crypto.
Not yet a full end-to-end *handshake* integration - this calls the three
KEM operations directly, not through a JCA `KEMSpi`/BCJSSE wiring into the
actual mTLS reference service, which remains the real next step if pursued
further. Full mechanism, the wall-clock-vs-cycle-counter methodology note
from the ceiling measurement, and the shared-library build details:
[benchmarks/mlkem-native-bench/README.md](benchmarks/mlkem-native-bench/README.md)
and [benchmarks/mlkem-ffm-bench/README.md](benchmarks/mlkem-ffm-bench/README.md).

**The "what actually consumes the ~88ms?" open question those two
documents flagged is now resolved**, not just corrected-and-left-open, via
[Arm Performix](https://developer.arm.com/servers-and-cloud-computing/arm-performix)
(Arm's own hardware-level profiling toolkit for Neoverse platforms) - real
CPU sampling data from the exact benchmark command that produces the 88ms
number, 1.15M samples, on the real Cobalt 100 target, with Java stack
symbolication enabled. **~73% of all CPU time is JVM overhead**:
`libjvm.so` (62.96% - C2 JIT compiler passes `PhaseIdealLoop`,
`PhaseChaitin`, `PhaseIterGVN` dominate, plus GC and class loading) and the
bytecode `Interpreter` running not-yet-compiled code (10.04%). **All of
BouncyCastle's code combined - TLS engine, ASN.1, ML-KEM, classical crypto
- is 5.55%.** This directly explains, with evidence rather than inference,
why lever 4 (native-image, which eliminates JIT entirely) found this
project's largest B2 win: JIT compilation is the dominant real cost on this
hardware, precisely the mechanism lever 4 attacks. Full profiling
methodology, an honest caveat about a small amount of profiler-induced
overhead in the raw numbers, and reproduction steps:
[benchmarks/arm-performix-profile/README.md](benchmarks/arm-performix-profile/README.md).

**Levers 4 and 5 are complementary, not competing** — they save different
kinds of time, quantifiably: lever 4 saves ~2ms once per process launch;
lever 5, now confirmed via real FFM integration (not just a ceiling), would
save ~0.14ms per handshake vs. BC, every handshake. For the fresh-process
-per-handshake shape lever 4 targets, lever 4's one-time saving is ~14,050x
larger, making it the clear choice. For a long-running server handling many
connections on one process — the shape lever 4 explicitly doesn't cover —
lever 5's saving compounds linearly and would overtake lever 4's fixed
saving after roughly 14,050 handshakes, continuing to grow afterward while
lever 4's contribution stays flat. Which lever matters more is a question
about process lifetime, not which technique is objectively better.

**Lever 6 (exploratory) — does pure Java have any real headroom left, or
is native the only way to close the gap to lever 5?** Java's Vector API
(`jdk.incubator.vector` - pure Java SIMD, no JNI, no FFM, no native-image,
works on any standard JVM) tested against a representative Cooley-Tukey
NTT kernel (not FIPS 203's exact incomplete NTT - a standard, complete,
textbook transform over the same size/shape of workload; see
`benchmarks/vector-api-ntt/README.md` for exactly what was and wasn't
replicated). Correctness verified before any timing was trusted: the
vector implementation's output checked bit-identical to the scalar
implementation's, not just plausible-looking, across 200 random trials.

Local (Apple Silicon) result: vector was *slower* than scalar, 0.949x.
**Real Cobalt 100 hardware, 3-run averaged, showed the opposite direction:
vector ~6.7% faster than scalar** (2848ns → 2669ns) - consistent across
all 3 runs. A third distinct local-vs-real divergence pattern this project
has now hit (lever 2: promising local signal, nothing real; lever 4: real
gap larger than local; lever 6: local regression, real modest gain) -
reinforcing the same underlying lesson each time: don't trust a local
signal in *either* direction without checking real target hardware.

**What this answers:** pure Java is not a dead end - there is real,
measured, correctness-verified headroom (~6.7%) without leaving the JVM at
all, using a conservative, disclosed-as-conservative implementation (2-lane
`LongVector`, not a tuned 16-bit-lane design). It's a small fraction of
lever 5's ~4.3-4.6x, so it doesn't change this project's lever ranking -
but it demonstrates the ceiling-vs-floor distinction concretely: JDK 25's
own intrinsics (lever 3, ~8.7%) and this exploratory pure-Java SIMD attempt
(lever 6, ~6.7%) land in the same modest range, while a fully-dedicated
native implementation (lever 5) is 40-60x further out - the gap between
"shallow JVM-hosted acceleration" and "dedicated native code" is real and
large, not something a bit more Java-side effort closes.

**Lever 7 (exploratory) — is mlkem-native's ~4x win a C-vs-Rust language
story, or something else?** Prompted directly by a question about whether
Rust's memory safety (ownership/borrow-checking, vs. C's none) comes with
a measured performance cost. Benchmarked RustCrypto's `ml-kem` crate the
same two-stage way as lever 5 - raw standalone, then a real Java FFM
integration - on the same real Cobalt 100 hardware, 3-run averaged,
correctness-verified (shared-secret agreement):

| Operation | RustCrypto (FFM) | mlkem-native, C (FFM) | vs BC | vs JDK 25 | vs mlkem-native |
|---|---|---|---|---|---|
| **total** | **142.11 µs** | **47.32 µs** | **1.34x faster** | **1.24x faster** | **3.0x slower** |

**No - memory safety isn't the cost.** FFM-integrated RustCrypto beats pure
Java (~1.3x over BC), so a memory-safe systems language is a real, if
modest, net win over managed Java on its own. But it's ~3x *slower* than
mlkem-native's C - a gap explained by what each library actually is, not
by which language it's written in: mlkem-native is hand-written,
formally-proven AArch64 NEON assembly; RustCrypto's crate is portable,
generic, safe Rust with no chip-specific tuning at all. Rust can write the
same NEON intrinsics C can (`std::arch::aarch64` exists precisely for
this) - nobody has invested that engineering effort into this particular
crate. The axis that actually matters is "hand-tuned for this chip" vs.
"portable," not "which language." One concrete, load-bearing illustration
of Rust's *type-level* safety surfaced while building this: the
implementation originally tried `SmallRng` (explicitly non-cryptographic,
chosen to match methodology with mlkem-native's own non-secure test RNG)
and **it failed to compile** - `ml-kem`'s API requires
`rand_core::CryptoRngCore`, which `SmallRng` deliberately doesn't
implement. C has no equivalent compile-time guard; mlkem-native's own
official benchmark binary links a non-secure RNG precisely because nothing
stops it. Full detail:
[benchmarks/mlkem-rust-ffm-bench/README.md](benchmarks/mlkem-rust-ffm-bench/README.md).

**Lever 8 (exploratory) — a positive control for lever 7's conclusion.**
If lever 7's gap was really about assembly investment, not language, a
Rust wrapper *around* hand-tuned assembly should close it.
[`rustpq/pqcrypto`](https://github.com/rustpq/pqcrypto) is exactly that -
a Rust FFI wrapper around PQClean's C, whose `ml-kem-768/aarch64` variant
has real hand-written NEON assembly (confirmed via source and via this
build's own compiled `.a`/`.o` artifacts). Same methodology, same real
hardware, 3-run averaged, correctness-verified:

| | pqcrypto (FFM) | mlkem-native (FFM, C) | RustCrypto (FFM, lever 7) |
|---|---|---|---|
| **total** | **48.90 µs** | **47.32 µs** | **142.11 µs** |

**Within 3.3% of mlkem-native's own performance - and ~2.9x faster than
lever 7's pure-Rust crate, using the same host language.** This confirms
lever 7's conclusion with a positive result, not just a negative one: the
axis was never "Rust vs. C," it's "does this specific implementation have
chip-specific hand-tuning." But the safety picture is correspondingly
different from lever 7 too, and worth stating precisely rather than
conflating: pqcrypto's Rust layer wraps the API surface only - the hot
NTT/multiplication loops still run in C and assembly, with the same safety
profile as mlkem-native's hot path (none). Lever 7 (safe, ~3x slower) and
lever 8 (fast, no safer than C in the hot path) each demonstrate one side
of a real tradeoff; no lever tested here gets both at once. Full detail:
[benchmarks/pqcrypto-ffm-bench/README.md](benchmarks/pqcrypto-ffm-bench/README.md).

Together, the eight levers point at a coherent picture of what
"efficiency-minded design" on Arm64 actually requires for this workload:
attacking handshake crypto cost directly via hand-tuned, chip-specific
native code (levers 5 and 8, both landing within a few percent of each
other via C and Rust-wrapping-C respectively) for the largest possible
win, a win driven by architecture-specific tuning investment, not by which
language calls it or by leaving Java per se (lever 7's portable, un-tuned
Rust only closes a fraction of that gap on its own); incremental,
still-worthwhile pure-Java gains are available via the Vector API (lever 6)
or deeper JDK intrinsic investment (lever 3) without leaving the JVM at
all; and ahead-of-time compilation (lever 4) for anything that pays JVM
startup cost per unit of work - three different costs, three different
levers, not one optimization problem with one answer.

### Infrastructure — real, not simulated

- **Arm64 hardware:** Azure `Standard_D2pls_v6` (2 vCPU, Cobalt 100/Neoverse-N2),
  provisioned during this project (not a pre-existing resource) — see
  [docs/arm64-instance-setup.md](docs/arm64-instance-setup.md) for the
  exact commands, including real gotchas hit along the way (a subscription
  quota that was `0` for the expected VM family but already approved for
  a newer one; a capacity restriction in one region resolved by trying an
  adjacent one; an image alias that silently resolves to x86_64 instead of
  the Arm64 variant intended).
- **CI:** GitHub Actions, with the Arm64 leg running on the same
  provisioned VM registered as a self-hosted runner — real target
  hardware in CI, not a generic hosted Arm64 runner, and it sidesteps a
  GitHub-hosted Actions billing block that affected the primary x86 leg.
  See `.github/workflows/ci.yml`.

### Component C — authoring guardrail + CBOM (done)

Built after B2 was already far past the plan's own "1-2 levers you can
land solidly" target (eight, at that point) - the plan's fallback ladder
explicitly protects B2 first, so this was deliberately sequenced after,
not skipped.

**`skills/pqc-authoring/`** — a Claude Code Skill reviewing new/changed
Java TLS code for regressions back toward classical-only crypto. Proven,
not just asserted: its worked example
(`skills/pqc-authoring/examples/worked-example.md`) walks through a real
regression this project hit once (`SSLContext.getDefault()` silently
negotiating classical instead of the hybrid group, with zero runtime
error - see `docs/bouncycastle-pqc-notes.md`), showing the skill's
checklist actually catches it. Deliberately scoped narrow - flags TLS
-handshake-context classical crypto only, not general application crypto
elsewhere, and doesn't demand PQC certificate signatures since that's
honestly out of this project's own migration scope (see below).

**`component-c/cbom/`** — `./run cbom {before|after}` emits a CycloneDX
1.6 CBOM, **validated against the real published JSON schema** (downloaded
from `CycloneDX/specification`, not assumed) rather than just
plausible-looking JSON. Honest by construction: the "after" CBOM still
lists `ECDSA-P256` as a classical, unmigrated signature asset
(`nistQuantumSecurityLevel: 0`) alongside the new `X25519MLKEM768` hybrid
KEM, because certificate authentication genuinely hasn't migrated in this
project (ML-DSA cert auth deferred, upstream not stable - see "Scope"
below). A CBOM that dropped that asset the moment the KEX went PQC would
misrepresent the actual state, exactly where an auditable record can't
afford to. Every asset cites the exact file/line its fact comes from.

Framed for the audience the plan's own rationale names for Track 2
(migration/adoption value): regulated Java shops - finance, insurance -
where a full rewrite is never realistic and a migration has to be
incremental, auditable, and defensible to a compliance review. Full
detail: [component-c/README.md](component-c/README.md).
[docs/regulated-deployment-guide.md](docs/regulated-deployment-guide.md)
extends this into a concrete deployment recommendation for that audience:
why staying in Java (not a native rewrite) is the right call given what
this project's own evidence shows about where PQC's real cost actually
lives, which of the eight B2 levers to reach for at three different
deployment shapes, and a regulatory-context matrix distinguishing FIPS
-140 module validation from algorithm standardization and from
`mlkem-native`'s formal verification - three genuinely different things
this space often conflates - with explicit hedging everywhere current
CMVP/FedRAMP status needs live verification rather than being assumed
from this project's own (non-FIPS) testing.

### What's not done (stated plainly)

Native Arm64 acceleration via
`mlkem-native` (lever 5 above) had its *ceiling* measured directly on real
hardware (~4.3-4.6x on the primitive, exceeding the ~2-5x expected from
independent literature), the specific reason OpenJDK didn't take this
route for JDK 25 itself was researched (a portability/trusted-computing
-base design choice, not a technical blocker, per JEP 496's own stated
rationale), and a real FFM integration (calling the native library's
keypair/encaps/decaps directly from Java via `java.lang.foreign`, no JNI,
correctness-verified via shared-secret agreement across the FFI boundary)
confirmed ~85% of that ceiling survives real integration (~3.7-4.0x, not
just the ~4.3-4.6x ceiling) - see
[benchmarks/mlkem-ffm-bench/README.md](benchmarks/mlkem-ffm-bench/README.md).
**What's still not done:** wiring this into `ProviderBootstrap`/BCJSSE
itself, replacing BC's pure-Java ML-KEM path with calls into the native
library for a real end-to-end handshake, so no full-handshake number exists
for this lever the way levers 1-4 have - the FFM binding calls the three
KEM operations directly, not through the actual mTLS reference service.
The same gap applies to lever 7 (RustCrypto) and lever 8 (pqcrypto).

**An x86-vs-Arm64 cross-reference has not been done.** `arm-hackathon-plan.md`
§3 asks for one explicitly as a B2 deliverable ("an x86-vs-Arm64
cross-reference showing the Arm efficiency angle"). Every "local" signal
compared against real-hardware results in this write-up (Apple Silicon vs.
Cobalt 100) is ARM-vs-ARM, not x86-vs-Arm - a real, unmet piece of the
plan's own ask, not a style choice. What exists instead, and is arguably
stronger evidence for "clearly leverages Arm-powered platforms" (the
rubric's actual wording, per the official rules) even without an x86
baseline: real Arm64 hardware for every finding in this document, and Arm
Performix (Arm's own profiling tool) used directly to resolve an open
question with real Neoverse-N2 microarchitecture data.

**No demo video.** Optional per the official rules, but explicitly called
out as "high-leverage" - front-loaded, it would show `./run before` /
`./run after` succeeding on real Arm64, the before/naive/tuned benchmark
story, and the two Component C artifacts. Not made this session.

**The repository is currently private.** Checked directly via `gh repo
view` (not assumed): GitHub correctly detects and would display the
Apache-2.0 license in the About section - that specific concern is
resolved - but `isPrivate: true`. The official rules require "the
repository must be public and open source" as a submission requirement,
not an optional nicety - judges cannot access a private repo at all. This
needs to change before submission; not done automatically here since
making a repo public is the kind of action a user should decide the
timing of, not something to flip silently.

## Setup Instructions

**Requires:** JDK 21 (pinned automatically by the run scripts via
`scripts/require-jdk21.sh`, even if `java`/`mvn` on `PATH` resolve to
something else) and Maven. The `benchmarks/mlkem-microbench/` comparison
additionally requires JDK 25+ (not part of the main build).

### Quick start (any machine, Arm64 or not)

```bash
git clone https://github.com/yuanhawk/latticejack.git && cd latticejack
./run before   # classical mTLS handshake
./run after    # hybrid PQC key exchange — verified negotiating, not silently classical
./run-benchmark.sh before   # B1/B2 characterization: latency, throughput, bytes-on-wire, resumption
./run-benchmark.sh after
```

Both `run` commands generate their own throwaway test keystores on first
run (`scripts/gen-classical-keys.sh`, ECDSA P-256, 30-day validity — never
committed, already gitignored).

### Running on real Arm64 (what this project's numbers were actually measured on)

Full step-by-step, including exact commands and the gotchas hit along the
way, in [docs/arm64-instance-setup.md](docs/arm64-instance-setup.md)
Option C (Azure) — Options A/B cover AWS Graviton and Oracle Ampere if
preferred. Summary:

1. Provision an Arm64 instance. This project used Azure
   `Standard_D2pls_v6` (2 vCPU, Cobalt 100/Neoverse-N2), Ubuntu 24.04 LTS
   arm64, ~$0.062/hr in `eastus2` at time of measurement.
2. Verify it's actually Arm64: `uname -m` → `aarch64`; check for
   Neoverse-N2/SVE2 via `lscpu`.
3. Install JDK 21 (`sudo apt install openjdk-21-jdk maven git` on
   Ubuntu/Debian; adjust for other distros — see the setup doc).
4. Clone this repo and run the same four commands as the quick start
   above. If `./run before` doesn't complete cleanly, stop and fix that
   first — a broken classical baseline invalidates every delta measured
   on top of it.
5. For the JDK 25 built-in ML-KEM comparison specifically:
   `sudo apt install openjdk-25-jdk`, then
   `benchmarks/mlkem-microbench/run.sh /usr/lib/jvm/java-25-openjdk-arm64`
   (adjust path per distro).

### Verifying the headline claims yourself

- **The hybrid group actually negotiates** (not a silent classical
  fallback): `LATTICEJACK_DEBUG=1 ./run after` — the script itself asserts
  a HelloRetryRequest occurred and fails loudly if not.
- **The B2 findings are reproducible, not cherry-picked**: every raw
  benchmark run referenced in this write-up is committed alongside its
  analysis — see the `.txt`/`.csv` files in
  `benchmarks/samples/azure-cobalt100-2vcpu/` and
  `benchmarks/mlkem-microbench/`, not just the summarized tables above.
