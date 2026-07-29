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
The headline B2 result — that JDK 25's built-in ML-KEM, marketed
elsewhere at "roughly on par with OpenSSL," only delivers ~8.7% over
BouncyCastle's plain-Java implementation on real Arm64 server hardware
(vs. ~51% on Apple Silicon, for the *identical* JVM flag) — is not
something you'd find by reading the JEP. It required provisioning real
Arm64 infrastructure, building a rigorous benchmark, and being willing to
re-measure three times when a promising single-run number looked too good
to trust. That discipline, applied consistently, is the actual
differentiator here.

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
project tried three concrete optimization levers, measured every one on
real hardware (not a laptop), and reports what actually happened —
including two honest null results and one precisely root-caused,
non-obvious finding, all cross-validated with independent-model review
(Opus and Fable, given the same question, converged independently) before
being trusted.

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
fraction of a tiny total; on the real target, crypto compute dominates so
completely (~88ms) that secondary JVM effects become rounding error.

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
oversight. Full mechanism-level writeup:
[benchmarks/mlkem-microbench/README.md](benchmarks/mlkem-microbench/README.md).

**The consistent throughline across all three levers:** ML-KEM's own
cryptographic computation (dominated by Keccak/SHA3 hashing, not the NTT)
is the real, hard-to-avoid cost on this hardware, and every secondary
lever tried — session caching, JVM flags, even switching to a
"faster" JDK-native implementation — is noise or single-digit-percent by
comparison. That's a defensible, three-times-independently-confirmed
finding, not a cherry-picked win, and it directly answers what "efficiency-
minded design" on Arm64 actually requires for this workload: attacking the
crypto primitive itself (native NEON acceleration via `mlkem-native`,
identified but not yet attempted — the concrete next step) rather than
tuning around it.

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

### What's not done (stated plainly)

**Component C (authoring guardrail skill + CBOM crosscheck) has not been
started.** This project prioritized Component B2 (the Arm64 optimization
work, the plan's own explicitly-protected centerpiece and the 40-point
criterion) over Component C per the plan's own fallback ladder. If
pursued, it's independent of the Arm64 hardware work and could be picked
up without further infrastructure. Native Arm64 acceleration
(`mlkem-native` via Java's FFM API) was researched in depth — feasibility,
expected magnitude (~2-5x on the primitive, per independent literature),
and the specific reason OpenJDK didn't take this route for JDK 25 itself
(a portability/trusted-computing-base design choice, not a technical
blocker, per JEP 496's own stated rationale) — but not implemented.

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
