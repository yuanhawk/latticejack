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
| Arm64 optimization (B2) | **Three levers tried on real hardware: two honest null results, one confirmed positive finding.** Lever 1 (session resumption): ~2%/~0% benefit, not the plan's assumed "near-guaranteed win." Lever 2 (JVM flags): every variant landed within ~1% of baseline on real Arm64 — a promising -15.5% seen locally (Apple Silicon) didn't hold. Bonus finding, precisely explained via direct intrinsic isolation (`-XX:±UseKyberIntrinsics` A/B test, 3-run averaged): JDK 25's built-in ML-KEM (marketed at ~2x BC's speed) only gets ~8.7% from its AArch64 intrinsic on real Arm64 server hardware vs. ~51% on Apple Silicon for the identical flag. **Lever 4 (GraalVM native-image): ~7.9x faster cold-start (290ms vs 2292ms, 3-run averaged, 60/60 runs succeeded) for launching a fresh instance and completing one hybrid-PQC handshake** — the first positive B2 result, and the real-hardware gap is *larger* than the local signal that motivated trying it, the opposite of every other lever's local-vs-real pattern. This measures cold-start, not warm steady-state throughput — see [benchmarks/graalvm-native-image/README.md](benchmarks/graalvm-native-image/README.md) for that caveat and the two GraalVM/BouncyCastle build-time incompatibilities found and fixed along the way. See also [benchmarks/samples/azure-cobalt100-2vcpu/README.md](benchmarks/samples/azure-cobalt100-2vcpu/README.md) and [benchmarks/mlkem-microbench/README.md](benchmarks/mlkem-microbench/README.md). |
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
MIGRATION.md                         the step-by-step migration procedure + gotchas
docs/bouncycastle-pqc-notes.md       BouncyCastle PQC/JSSE research + full debugging log
docs/arm64-instance-setup.md         Arm64 provisioning guidance
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
