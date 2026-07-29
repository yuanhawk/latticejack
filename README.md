# Latticejack — PQC Migration for Java on Arm64

Arm AI Optimization Challenge, Track 2 (Migration/Adoption). Full plan: [arm-hackathon-plan.md](arm-hackathon-plan.md).
Formal submission write-up (Project Overview / Functionality / Setup
Instructions): **[WRITEUP.md](WRITEUP.md)**.

Migrates a Java TLS/mTLS reference service from classical crypto
(ECDSA/X25519) to hybrid post-quantum crypto (ML-KEM), then tunes the
migrated path for Arm64 (Azure Cobalt 100 today; AWS Graviton / Ampere
supported too — see [docs/arm64-instance-setup.md](docs/arm64-instance-setup.md)).
Migration mechanics: see [MIGRATION.md](MIGRATION.md).

## Status

| Piece | State |
|---|---|
| **Before** (classical mTLS, JDK-only) | Working — `./run before`, verified on real Arm64 |
| **After** (hybrid X25519MLKEM768 KEX) | **Working** — `./run after`, self-verifying, verified on real Arm64. See [MIGRATION.md](MIGRATION.md) and [docs/bouncycastle-pqc-notes.md](docs/bouncycastle-pqc-notes.md) §3a. |
| ML-DSA certificate auth | **Deliberately deferred** to a stretch goal — experimental upstream in BouncyCastle, not enabled by default ([bcgit/bc-java#2102](https://github.com/bcgit/bc-java/issues/2102)). See MIGRATION.md "Scope." |
| Arm64 benchmarking (B1) | **Done on real hardware** — Azure Cobalt 100 (Neoverse-N2), `./run-benchmark.sh`. PQC hybrid costs ~95% more p50 latency, ~51% less throughput vs. classical baseline. Two methodology bugs found and fixed along the way (debug logging contaminating timings). See [benchmarks/samples/azure-cobalt100-2vcpu/README.md](benchmarks/samples/azure-cobalt100-2vcpu/README.md). |
| Arm64 optimization (B2) | **Seven levers tried on real hardware: two honest null results, one root-caused finding, four confirmed positive/informative results.** L1 (session resumption): ~2%/~0%, not the plan's assumed "near-guaranteed win." L2 (JVM flags): every variant within ~1% of baseline — a promising -15.5% seen locally didn't hold. L3 (JDK 25 built-in ML-KEM): only ~8.7% from its AArch64 intrinsic on real hardware vs. ~51% on Apple Silicon for the identical flag, precisely isolated via direct A/B toggling. **L4 (GraalVM native-image): ~7.9x faster cold-start (290ms vs 2292ms)** for launching a fresh instance and completing one handshake — not an Arm-specific mechanism (general AOT-vs-JIT property), but confirmed real and large on this hardware; see [benchmarks/graalvm-native-image/README.md](benchmarks/graalvm-native-image/README.md). **L5 (mlkem-native, NEON-accelerated): ~4.0x faster (BC) / ~3.7x faster (JDK 25) end-to-end via a real Java FFM integration** (~85% of the raw ~4.3-4.6x C ceiling survives the FFI crossing cost) — the largest *realized* per-op gap this project found, correctness-verified via shared-secret agreement across the FFM boundary; L4 and L5 are complementary (one-time startup cost vs. per-handshake crypto cost, ~14,050-handshake crossover point), not competing — see [benchmarks/mlkem-native-bench/README.md](benchmarks/mlkem-native-bench/README.md) (the ceiling) and [benchmarks/mlkem-ffm-bench/README.md](benchmarks/mlkem-ffm-bench/README.md) (the integration). **L6 (Java Vector API, pure-Java SIMD, exploratory): ~6.7% faster** than scalar for an NTT-shaped kernel on real hardware — small, but real, correctness-verified (vector output checked bit-identical to scalar), and portable (no native code at all); local Apple Silicon signal showed the *opposite* direction (a slowdown), yet another case of local-vs-real divergence in this project. See [benchmarks/vector-api-ntt/README.md](benchmarks/vector-api-ntt/README.md). **L7 (RustCrypto `ml-kem`, exploratory): ~1.3x faster than BC via FFM, but ~3.0x slower than mlkem-native's C** — answers whether Rust's memory safety costs performance (no) and shows the real driver of L5's win is hand-tuned, chip-specific NEON assembly, not "leaving Java" per se; a portable, safe, un-tuned native implementation only closes a fraction of that gap. Surfaced a concrete type-safety illustration too: the implementation wouldn't compile with a non-`CryptoRngCore` RNG, a compile-time guard C has no equivalent of. See [benchmarks/mlkem-rust-ffm-bench/README.md](benchmarks/mlkem-rust-ffm-bench/README.md). See also [benchmarks/samples/azure-cobalt100-2vcpu/README.md](benchmarks/samples/azure-cobalt100-2vcpu/README.md) and [benchmarks/mlkem-microbench/README.md](benchmarks/mlkem-microbench/README.md). |
| Authoring guardrail / CBOM (Component C) | Not started |

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
~85% of the ceiling surviving real integration. Neither is wired into the
actual mTLS reference service (a real JCA `KEMSpi` integration would be the
next step), so still not a full-handshake number the way levers 1-4 have.
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
benchmarks/vector-api-ntt/           pure-Java SIMD (Vector API) NTT-shaped kernel benchmark (B2 lever 6)
benchmarks/mlkem-rust-ffm-bench/     RustCrypto ml-kem raw + FFM benchmark - Rust vs C vs Java (B2 lever 7)
MIGRATION.md                         the step-by-step migration procedure + gotchas
docs/bouncycastle-pqc-notes.md       BouncyCastle PQC/JSSE research + full debugging log
docs/arm64-instance-setup.md         Arm64 provisioning guidance
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
