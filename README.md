# Latticejack — PQC Migration for Java on Arm64

Arm AI Optimization Challenge, Track 2 (Migration/Adoption). Full plan: [arm-hackathon-plan.md](arm-hackathon-plan.md).
Formal submission write-up (Project Overview / Functionality / Setup
Instructions): **[WRITEUP.md](WRITEUP.md)**.

Migrates a Java TLS/mTLS reference service from classical crypto
(ECDSA/X25519) to hybrid post-quantum crypto (ML-KEM), then tunes the
migrated path for Arm64 (Azure Cobalt 100 today; AWS Graviton / Ampere
supported too — see [docs/arm64-instance-setup.md](docs/arm64-instance-setup.md)).
Migration mechanics: see [MIGRATION.md](MIGRATION.md).

**Why this counts as an AI solution, not just a crypto migration on Arm:**
[`skills/pqc-authoring/`](skills/pqc-authoring/SKILL.md) is a Claude Code
Skill — a real AI-agent capability (a "prompt asset," the judging
rubric's own term), not documentation, that drives an AI coding agent to
catch a specific regression class automatically. Every claim in this
repo — every benchmark number, every "resolved" or "fixed" — was
adversarially re-verified by independent AI models (Opus and Fable, run
blind to each other), which found and fixed real bugs the same way a
second human reviewer would, applied consistently across eight
optimization levers. And the infrastructure being migrated is itself
AI-relevant: TLS is what AI model-serving endpoints and agent-to-agent
protocols run over, increasingly on the same Arm64 silicon this project
measured on. Full argument: `WRITEUP.md`'s Project Overview.

## Status

| Piece | State |
|---|---|
| **Before** (classical mTLS, JDK-only) | Working — `./run before`, verified on real Arm64 |
| **After** (hybrid X25519MLKEM768 KEX) | **Working** — `./run after`, self-verifying, verified on real Arm64. See [MIGRATION.md](MIGRATION.md) and [docs/bouncycastle-pqc-notes.md](docs/bouncycastle-pqc-notes.md) §3a. |
| ML-DSA certificate auth | **Deliberately deferred** to a stretch goal — experimental upstream in BouncyCastle, not enabled by default ([bcgit/bc-java#2102](https://github.com/bcgit/bc-java/issues/2102)). See MIGRATION.md "Scope." |
| Arm64 benchmarking (B1) | **Done on real hardware** — Azure Cobalt 100 (Neoverse-N2), `./run-benchmark.sh`. PQC hybrid costs ~95% more p50 latency, ~51% less throughput vs. classical baseline. Two methodology bugs found and fixed along the way (debug logging contaminating timings). See [benchmarks/samples/azure-cobalt100-2vcpu/README.md](benchmarks/samples/azure-cobalt100-2vcpu/README.md). |
| Arm64 optimization (B2) | **Eight levers tried on real hardware: two honest null results, one root-caused finding, five confirmed positive/informative results.** L1 (session resumption): ~2%/~0%, not the plan's assumed "near-guaranteed win." L2 (JVM flags): every variant within ~1% of baseline. L3 (JDK 25 built-in ML-KEM): only ~8.7% from its AArch64 intrinsic on real hardware vs. ~51% on Apple Silicon. **L4 (GraalVM native-image): ~7.9x faster cold-start** — largest *overall* win, general AOT-vs-JIT property (not Arm-specific), but real and large here. **L5 (mlkem-native, C+NEON): ~4.0x/~3.7x faster (BC/JDK25) end-to-end via real Java FFM integration** — largest *realized per-op* gap, ~85% of the raw ceiling survives FFI crossing; now also wired into the real mTLS handshake behind an opt-in flag (`-Dlatticejack.tls.nativekem=true`), positively verified (not just "handshake succeeded") on real Arm64 — an independent Opus audit's most significant finding on this feature (deterministic test-only RNG stub instead of a CSPRNG, making key material predictable) **is now fixed**: real `SecureRandom` coins via the library's `_derand` entry points, verified both for correctness (different keys across repeated calls and fresh processes) and, re-measured on real hardware after the fix, for performance — the small full-handshake edge originally reported here didn't survive paying for real randomness (post-fix: ~0.7-1.5% higher latency, ~2.6% lower throughput than BC, close to this VM's own run-to-run noise) — see [nativekem-e2e-bench](benchmarks/nativekem-e2e-bench/README.md) for both the pre- and post-fix numbers in full. **L6 (Java Vector API, exploratory): ~6.7% faster**, pure Java, no native code, correctness-verified bit-identical to scalar. **L7 (RustCrypto, pure Rust, exploratory): ~1.3x faster than BC but ~3.0x slower than mlkem-native** — Rust's memory safety costs nothing vs. Java, but the L5 win is from hand-tuned assembly, not language. **L8 (pqcrypto, Rust-wrapping-C-NEON, exploratory): within 3.3% of mlkem-native's own speed** — confirms L7 with a positive control: same host language as L7, real assembly investment, ~2.9x faster than L7. L4/L5 are complementary (startup cost vs. per-handshake cost, ~14,050-handshake crossover), not competing. Full detail per lever: [graalvm-native-image](benchmarks/graalvm-native-image/README.md), [mlkem-native-bench](benchmarks/mlkem-native-bench/README.md) + [mlkem-ffm-bench](benchmarks/mlkem-ffm-bench/README.md), [vector-api-ntt](benchmarks/vector-api-ntt/README.md), [mlkem-rust-ffm-bench](benchmarks/mlkem-rust-ffm-bench/README.md), [pqcrypto-ffm-bench](benchmarks/pqcrypto-ffm-bench/README.md), [azure-cobalt100-2vcpu samples](benchmarks/samples/azure-cobalt100-2vcpu/README.md), [mlkem-microbench](benchmarks/mlkem-microbench/README.md). |
| Root-cause: what's actually in the 88ms handshake? | **Still open — a real, committed CPU profile exists, corroborating but not conclusive.** [Arm Performix](https://developer.arm.com/servers-and-cloud-computing/arm-performix) profiling of the full benchmark script found ~73% JVM overhead (JIT + interpreter), only ~5.55% BouncyCastle — but an independent Opus+Fable audit found the profiled workload includes a Maven build and ~8-10 JVM cold starts (not an isolated warm handshake), a CPU-sampling profiler can't see the ~94% of handshake time that's off-CPU, and this project's own lever 2 result (C1-only JIT, ~0.01% from baseline) is in tension with "JIT dominates the warm handshake." Corroborates lever 4's mechanism; doesn't decompose the 88ms. See [benchmarks/arm-performix-profile/README.md](benchmarks/arm-performix-profile/README.md) for the full honest rewrite. |
| Authoring guardrail / CBOM (Component C) | **Done.** [`skills/pqc-authoring/`](skills/pqc-authoring/SKILL.md) — a Claude Code Skill that reviews new/changed TLS code for regressions back to classical-only crypto, demonstrated (not executed as an automated test) against a real regression this project hit once (`SSLContext.getDefault()` silently negotiating classical, no runtime error — see the [worked example](skills/pqc-authoring/examples/worked-example.md), an authored walkthrough, not a recorded transcript of the skill actually running). [`component-c/cbom/`](component-c/README.md) — `./run cbom {before\|after}` emits a CycloneDX 1.6 CBOM, validated against the real published schema with a committed, reproducible validator (`component-c/cbom/validate_cbom.py`), honestly showing ECDSA-P256 (cert auth) as still-unmigrated in the "after" BOM since that's the actual state of this project's migration scope. |

## Infrastructure

- **Arm64 instance:** Azure `Standard_D2pls_v6` (2 vCPU, Cobalt 100/Neoverse-N2), `eastus2`, resource group `latticejack-arm64-rg-2` — provisioning steps in [docs/arm64-instance-setup.md](docs/arm64-instance-setup.md) Option C. Stopped between uses to control cost (`az vm deallocate`) — start it (`az vm start`) before pushing if you need CI to actually run, or before re-benchmarking.
- **CI:** GitHub Actions, required leg on `ubuntu-latest`, bonus non-blocking leg on the Arm64 VM itself registered as a self-hosted runner — real target hardware, and sidesteps GitHub-hosted Actions minute billing. See `.github/workflows/ci.yml`.

## Quick start

Requires JDK 21 and Maven (`./run` / `run-before.sh` / `run-after.sh` pin
JDK 21 automatically via `scripts/require-jdk21.sh`, even if `java`/`mvn` on
`PATH` resolve to something else).

```bash
./run before   # classical mTLS handshake
./run after    # hybrid PQC key exchange, verified negotiating (not silently classical)
./run cbom after  # CycloneDX CBOM for the current migration state (Component C)
```

Both scripts generate their own test keystores under `keys/classical/` on
first run (`scripts/gen-classical-keys.sh`, ECDSA P-256, 30-day validity —
never commit these; `.gitignore` already excludes them).

`./run after` verifies the negotiated group is actually the hybrid one
before reporting success — see MIGRATION.md's "Gotchas" section and
`docs/bouncycastle-pqc-notes.md` §3a for why that verification exists and
how it works (BCJSSE exposes no direct API for this). Set
`LATTICEJACK_PORT` to change the port either script binds to.

## Benchmarking (Component B1/B2)

```bash
./run-benchmark.sh before   # classical baseline: latency, throughput, bytes-on-wire, resumption
./run-benchmark.sh after    # hybrid PQC: same four passes, for the before/after delta
```

See [benchmarks/README.md](benchmarks/README.md) for what's measured and how,
and [benchmarks/samples/azure-cobalt100-2vcpu/README.md](benchmarks/samples/azure-cobalt100-2vcpu/README.md)
for real-hardware results including the session-resumption (B2 lever 1) finding.

`benchmarks/mlkem-microbench/` is a separate, JDK-25-required standalone
comparison of BC's pure-Java ML-KEM against JDK 25's own built-in
implementation — not part of the main build. See its
[README](benchmarks/mlkem-microbench/README.md) for why the widely-cited
"JDK 25 makes ML-KEM ~2x faster" claim doesn't hold up on real Arm64 server
hardware the way it does on Apple Silicon.

`native-image/` + `scripts/build-native-image.sh` build a GraalVM
native-image (AOT-compiled) version of the "after" service — requires a
GraalVM JDK 21 install (`GRAALVM_HOME`), separate from the pinned JDK 21
above. `scripts/bench-native-image.sh` compares its cold-start latency
against the regular JVM; see
[benchmarks/graalvm-native-image/README.md](benchmarks/graalvm-native-image/README.md)
for the real-hardware result (B2 lever 4, ~7.9x faster cold-start).

`benchmarks/mlkem-native-bench/` benchmarks
[`pq-code-package/mlkem-native`](https://github.com/pq-code-package/mlkem-native)
(NEON-accelerated ML-KEM in C) standalone on real Arm64 hardware — the
*ceiling* (~4.3–4.6x faster than both BC and JDK 25 for the raw
primitive). `benchmarks/mlkem-ffm-bench/` closes that loop: an actual Java
integration via `java.lang.foreign` (FFM, no JNI/native-image) calling the
same library, correctness-verified via shared-secret agreement across the
FFI boundary — B2 lever 5, ~4.0x (BC) / ~3.7x (JDK 25) realized end-to-end,
~85% of the ceiling surviving real integration.

**That integration is now also wired into the actual mTLS reference
service.** `-Dlatticejack.tls.nativekem=true` (see
[`ProviderBootstrap.java`](src/main/java/com/latticejack/pqc/ProviderBootstrap.java)
and [`src/main/java/com/latticejack/pqc/nativekem/`](src/main/java/com/latticejack/pqc/nativekem/))
makes `./run after`'s real hybrid X25519MLKEM768 handshake route ML-KEM-768
through mlkem-native's FFM path instead of BC's pure-Java implementation —
default (flag unset) behavior is unchanged. `./run-nativekem.sh` verifies
this positively (a trace marker proving mlkem-native, not BC, handled the
crypto, not just "the handshake succeeded"), on both macOS and real
Linux/aarch64. A real bug was found and fixed getting there (BC's
multi-release `bctls` jar resolves different JCA service names on JDK 17+
than its base sources suggest). **An independent Opus audit's most
significant finding on this feature — the shared library backing this
path linked a deterministic test-only RNG stub instead of a CSPRNG,
making key material predictable, not secret — is fixed**: real
`SecureRandom` coins now flow through the library's `_derand` entry
points, which never call the internal randombytes() at all; verified by
observing different keys across repeated calls and fresh processes
(previously byte-identical). Timed full-handshake numbers on real Arm64
hardware, re-measured after the fix against a fresh same-session BC
baseline: p50/p95/mean run ~0.7-1.5% higher (slower) than BC and
throughput ~2.6% lower — the modest native edge originally reported here
(pre-fix: ~2-4% lower p95/p99, ~7% higher throughput) did not survive
paying for real randomness, though the post-fix delta is close enough to
this VM's own ~2-3% run-to-run noise to read as "no clear net edge either
way" rather than a confident regression. See
[nativekem-e2e-bench/README.md](benchmarks/nativekem-e2e-bench/README.md)
for the full mechanism, the bug, and both the pre-fix and post-fix
numbers in full. What's still open: GraalVM native-image + this provider together is
untested; FFI overhead isn't isolated inside a full handshake the way
`mlkem-ffm-bench` isolated it standalone.

See [mlkem-native-bench/README.md](benchmarks/mlkem-native-bench/README.md)
and [mlkem-ffm-bench/README.md](benchmarks/mlkem-ffm-bench/README.md) for
how this compares against lever 4 (complementary, not competing) and an
honest correction to an earlier claim about what dominates handshake
latency on this hardware.

`benchmarks/vector-api-ntt/` is an exploratory single-file check of whether
Java's Vector API (pure Java SIMD, `jdk.incubator.vector`, no native code
at all) narrows the gap to lever 5 for a representative NTT-shaped kernel —
B2 lever 6, ~6.7% faster than scalar on real hardware, correctness-verified
(vector output checked bit-identical to scalar before any timing is
trusted). See its [README](benchmarks/vector-api-ntt/README.md).

`benchmarks/mlkem-rust-ffm-bench/` is the Rust counterpart to lever 5 —
[RustCrypto's `ml-kem` crate](https://crates.io/crates/ml-kem), benchmarked
raw and via a real Java FFM integration the same way mlkem-native was — B2
lever 7, answering whether Rust's memory safety costs performance versus C
(no) and versus Java (still a modest win, ~1.3x). See its
[README](benchmarks/mlkem-rust-ffm-bench/README.md).

`benchmarks/pqcrypto-ffm-bench/` is a positive-control follow-up —
[`rustpq/pqcrypto`](https://github.com/rustpq/pqcrypto) wraps PQClean's C
(including real NEON assembly) in Rust, rather than being pure Rust like
lever 7. B2 lever 8, within 3.3% of mlkem-native's own speed and ~2.9x
faster than lever 7 — confirms the gap was always about assembly
investment, not language. See its [README](benchmarks/pqcrypto-ffm-bench/README.md).

## Running on Arm64

See [docs/arm64-instance-setup.md](docs/arm64-instance-setup.md) for
provisioning a Graviton or Ampere instance and getting a verified `aarch64`
JDK 21 environment. Once there, the same two commands above apply.

## Repo layout

```
src/main/java/com/latticejack/pqc/   the reference TLS/mTLS service (Component A)
scripts/gen-classical-keys.sh        classical (ECDSA P-256) test keystore generation
scripts/require-jdk21.sh             JDK 21 pinning, sourced by the run scripts
run / run-before.sh / run-after.sh   the two configurations, per arm-hackathon-plan.md §3
run-benchmark.sh, benchmarks/        B1 characterization harness (latency/throughput/bytes-on-wire)
benchmarks/samples/                  tracked real-hardware results (not gitignored, unlike benchmarks/results/)
native-image/, scripts/build-native-image.sh, scripts/bench-native-image.sh
                                      GraalVM native-image build (B2 lever 4) + cold-start benchmark
benchmarks/mlkem-native-bench/       mlkem-native (NEON) standalone C ceiling benchmark (B2 lever 5, ceiling)
benchmarks/mlkem-ffm-bench/          mlkem-native via Java FFM - real integration (B2 lever 5, realized)
benchmarks/nativekem-e2e-bench/      mlkem-native wired into the real mTLS handshake, opt-in flag (B2 lever 5, end-to-end)
src/main/java/com/latticejack/pqc/nativekem/  JCA Provider routing ML-KEM-768 through mlkem-native's FFM path
benchmarks/vector-api-ntt/           pure-Java SIMD (Vector API) NTT-shaped kernel benchmark (B2 lever 6)
benchmarks/mlkem-rust-ffm-bench/     RustCrypto ml-kem raw + FFM benchmark - Rust vs C vs Java (B2 lever 7)
benchmarks/pqcrypto-ffm-bench/       pqcrypto (Rust-wrapping-C-NEON) raw + FFM benchmark (B2 lever 8)
skills/pqc-authoring/                Claude Code Skill: guardrail against classical-crypto regressions (Component C)
component-c/cbom/                    CycloneDX CBOM generator, ./run cbom {before|after} (Component C)
MIGRATION.md                         the step-by-step migration procedure + gotchas
docs/bouncycastle-pqc-notes.md       BouncyCastle PQC/JSSE research + full debugging log
docs/arm64-instance-setup.md         Arm64 provisioning guidance
docs/regulated-deployment-guide.md   why Java (not a rewrite) for financial-sector migration, FIPS/FedRAMP guidance
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
