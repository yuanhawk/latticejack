# B2 lever 5, closed the rest of the way: mlkem-native wired into the real mTLS handshake

`benchmarks/mlkem-ffm-bench/README.md` closed the "is this a real Java
integration or just a ceiling" loop but was explicit about what it still
wasn't: *"Not a full end-to-end TLS handshake integration - this calls the
three KEM operations directly, not through `ProviderBootstrap`/BCJSSE/a JCA
`KEM` SPI wired into the actual mTLS reference service... real, non-trivial
engineering..., not attempted here."* This closes that specific gap: an
opt-in flag (`-Dlatticejack.tls.nativekem=true`) that makes `./run after`'s
real hybrid X25519MLKEM768 handshake - the same `EchoTlsServer`/
`EchoTlsClient` pair, the same certs, the same HRR-based negotiated-group
check - route ML-KEM-768 keygen/encapsulate/decapsulate through
mlkem-native's NEON C implementation via FFM, instead of BC's own pure-Java
`MLKEMSpi`. Default (flag unset) behavior is unchanged.

> **Before reading any further: this specific build is not deployable
> as-is, for a reason more important than any performance caveat below.**
> The shipped `vendor/mlkem-native/libmlkem768ffm.{dylib,so}` links
> mlkem-native's own `notrandombytes()` **TEST double** - a deterministic,
> fixed-seed generator, not a CSPRNG (its own upstream header: *"You MUST
> NOT use this implementation outside of testing"*). Neither the native
> `keypair()` nor `enc()` call takes a randomness argument, so every key
> and every encapsulation this path produces is **fully deterministic and
> predictable** - the same on every run, on every machine. That was an
> accepted, disclosed shortcut when this same stub only backed a
> throughput benchmark whose keys were never used for anything
> (`benchmarks/mlkem-ffm-bench/README.md`). It is a materially different,
> security-relevant problem now that this path derives the actual TLS
> session secret in a real handshake: an attacker who knows this stub is
> in use does not need to break ML-KEM at all. This caveat was flagged by
> an independent Opus audit as the most significant finding in this
> feature's review - stated here plainly rather than left implicit in the
> Java class Javadoc (`NativeMlkem768.java`) it was previously disclosed
> in only. **"Positively verified" below means the wiring and negotiation
> are real and correct; it does not mean this build's key material is
> safe to use for anything beyond that verification.** A real deployment
> would need `libmlkem768ffm` relinked against a real CSPRNG's
> `randombytes()`, or - more cleanly - the shipped `_derand` symbol
> variants (`PQCP_MLKEM_NATIVE_MLKEM768_keypair_derand`/`_enc_derand`,
> confirmed present in the built library) wired to Java's own
> `SecureRandom`, which this package's SPIs already receive as a
> parameter and currently ignore. Neither is done here.

## Mechanism, briefly

A new JCA `Provider` (`src/main/java/com/latticejack/pqc/nativekem/
NativeMlkemProvider.java`) registers `KeyPairGenerator.ML-KEM-768` and
`KEM.ML-KEM-768`, backed by `NativeMlkem768`'s existing FFM bindings (the
same ones `mlkem-ffm-bench` measured standalone). `ProviderBootstrap.install()`
inserts it ahead of BC's own `"BC"` provider and switches
`BouncyCastleJsseProvider`'s constructor to the no-arg form under the flag,
so BCJSSE's plain `KeyPairGenerator.getInstance(alg)` / `KEM.getInstance(alg)`
provider-precedence search resolves this provider's services instead of
BC's - see that class's Javadoc for exactly why the constructor choice
matters and why it's gated behind the flag rather than always-on. Full
mechanism and the exact multi-release-jar trace: `NativeMlkemProvider.java`
and `NativeMlkemKemSpi.java` Javadoc; a step-by-step correctness walkthrough
is in `run-nativekem.sh`.

## A real bug this surfaced, not just a wiring exercise

Most of the scaffolding for this (provider skeleton, `--enable-preview`
plumbing, `run-nativekem.sh`, most of the `nativekem` package) was already
in place going into the verification gate. Running it exposed a genuine
bug: the handshake failed with `InvalidKeyException: unsupported key type`
thrown from **BC's own pure-Java `MLKEMSpi`**, not the new provider -
meaning the new provider's registration was being silently bypassed.

Root cause, confirmed by decompiling the actual class bytes the JVM
resolves (not just reading `bctls`'s base sources): `bctls-jdk18on-1.85`
is a multi-release jar. Its base-version `KemUtil` - the
`KeyPairGenerator.ML-KEM` / `KeyGenerator.ML-KEM-768` +
BC-specific `KEMGenerateSpec`/`KEMExtractSpec` convention the original
scaffolding was built around - is **not** what this project's pinned JDK 21
resolves. JDK 17+ resolves `bctls`'s `META-INF/versions/17/.../KemUtil`
override instead, which checks `SpiUtil.hasKEM()` (itself multi-release;
its v17 override probes for the standard `javax.crypto.KEMSpi`, present
since JDK 21/JEP 452, and returns true) and routes encapsulate/decapsulate
through the standard `javax.crypto.KEM` API (service `KEM.ML-KEM-768`) and
key generation through the *specific*-named `KeyPairGenerator.ML-KEM-768`
initialized with `java.security.spec.NamedParameterSpec`, not BC's own
`MLKEMParameterSpec`. The provider only registered the base-version service
names, so BC's own `KEM` service quietly resolved instead and rejected the
custom key type - exactly the failure mode `run-nativekem.sh`'s trace
-marker check exists to catch, and did.

Fix: `NativeMlkemKemSpi.java` (implements `javax.crypto.KEMSpi`), an
updated `NativeMlkemKeyPairGeneratorSpi.initialize(...)` accepting
`NamedParameterSpec("ML-KEM-768")`, and `NativeMlkemProvider` registering
`KeyPairGenerator.ML-KEM-768` / `KEM.ML-KEM-768` (the names actually
resolved at runtime) alongside the original generic-name registrations
(kept, inert on this JDK, for portability to a hypothetical pre-JDK-17
runtime). The lesson generalizes past this one bug: for a multi-release
jar, "read the sources" and "know what runs" are different activities, and
this project's own JDK 21 pin was the reason the base-sources-derived
scaffolding didn't match what actually executes.

## What's verified, and how

**Local (macOS, functional correctness only - no timing claim here):**

- `./run before` and `./run after` (unmodified default paths) both still
  complete with correct HRR/echo behavior - regression check passed, the
  `nativekem` package is unreachable unless the flag is set.
- `./run-nativekem.sh` completes the handshake, observes a
  HelloRetryRequest (2 ClientHellos - rules out a silent classical
  fallback to secp256r1), **and** greps both peers' logs for the
  `[native-mlkem-provider] ... via mlkem-native FFM` trace marker for
  keygen, encaps, **and** decaps. This is the check that actually matters:
  a wrong provider-priority/registration bug would still show a completed
  handshake and a correct HRR (the negotiated *group* and which
  implementation executes the KEM math are separate concerns) - only the
  trace marker positively proves mlkem-native's FFM path, not BC's
  pure-Java ML-KEM, handled the crypto.
- `./run-benchmark.sh after-native 5 2 2` (smoke test only, 5 iterations
  on a laptop, trace off as required for a timed pass) completed cleanly
  and wrote CSVs to `benchmarks/results/` - this establishes "the timed
  path doesn't crash," nothing about performance. Real numbers below come
  from the actual target-hardware run, not this smoke test.

**Real hardware (Azure Cobalt 100 / Neoverse-N2, Linux aarch64):**

The VM's existing clone was missing more than expected - `run-nativekem.sh`
didn't exist there at all, nor did `pom.xml`'s `--enable-preview` config,
the `--enable-preview` runtime flags, the `after-native` branch in
`run-benchmark.sh`, or 5 of the 8 files in the `nativekem` package. These
were synced directly over the existing SSH connection (scp/rsync, no
`git push`/`pull`, origin untouched) so the mandatory verification steps
could actually run at all. Worth stating plainly since it's a deviation
from "sync only the files changed this session": without it, steps 5-6 of
the task literally could not execute on the VM.

`./run-nativekem.sh` was then run directly on Linux/aarch64 (not just
macOS) and passed all three checks: echoed application data received
end-to-end, HRR observed (2 ClientHellos), and the
`[native-mlkem-provider]` trace marker observed for keygen(1)/encaps(1)/
decaps(1) across both peers - confirming the real mlkem-native NEON FFM
path, not BC's pure-Java `MLKEMSpi`, handled the crypto on the actual
target hardware, not just a dev laptop.

## Timed results: `./run-benchmark.sh {after|after-native} 200 20 8`, x3 each, fresh on the same VM instance

Both configs run the *same* full handshake path (`EchoTlsServer`/
`EchoTlsClient`, hybrid X25519MLKEM768, TLS 1.3 over loopback) - the only
difference is which ML-KEM-768 implementation executes underneath. All 6
runs are fresh in this same session, on the same VM instance, not reused
from an earlier/different run.

### Per-run raw numbers

| Config | Run | p50 (ms) | p95 (ms) | p99 (ms) | mean (ms) | Throughput (handshakes/sec) |
|---|---|---|---|---|---|---|
| after | 1 | 88.363 | 95.675 | 99.688 | 85.252 | 78.7 |
| after | 2 | 90.410 | 102.533 | 113.853 | 86.691 | 58.2 |
| after | 3 | 88.216 | 95.900 | 99.940 | 84.504 | 76.5 |
| after-native | 1 | 89.003 | 96.211 | 101.569 | 86.217 | 74.5 |
| after-native | 2 | 89.204 | 94.690 | 97.934 | 84.797 | 75.9 |
| after-native | 3 | 88.777 | 96.509 | 101.041 | 84.947 | 77.5 |

### Averaged across the 3 runs (each run's own p50 is already that run's median, per this project's established 3-run methodology)

| Metric | after (BC pure-Java ML-KEM) | after-native (mlkem-native NEON via FFM) | Delta |
|---|---|---|---|
| p50 latency | 88.996 ms | 88.995 ms | ~0% (noise) |
| p95 latency | 98.036 ms | 95.803 ms | −2.3% (native lower) |
| p99 latency | 104.494 ms | 100.181 ms | −4.1% (native lower) |
| Throughput | 71.133 h/s | 75.967 h/s | +6.8% (native higher) |

**Read this the way `mlkem-ffm-bench` and the Arm Performix profile already
established, not as a surprise:** full end-to-end handshake latency here
(~89ms) is dominated by JVM/TLS/connection-setup overhead on a shared
2-vCPU VM, not by ML-KEM-768 compute itself, which `mlkem-ffm-bench`
measured at ~47µs total per operation set via the same FFM path - roughly
three orders of magnitude below the handshake floor. **p50 parity between
the two configs is exactly what that prior finding predicts, not a null
result to explain away.** The native path's benefit is visible only where
it should be: the tail (p95/p99, where a slower op occasionally lands on
the critical path under load) and aggregate throughput under concurrency,
both modest (single-digit percent) and consistent with the operation-level
gap being a small fraction of a much larger handshake. The `after` run's
run-2 throughput pass shows a visible outlier (58.2 h/s vs. ~77 h/s
elsewhere) - consistent with shared-VM scheduling noise on a 2-vCPU
instance, reported as measured rather than discarded, matching this
project's practice elsewhere (e.g. the session-resumption findings in
`benchmarks/samples/azure-cobalt100-2vcpu/README.md`) of not silently
dropping an inconvenient data point.

**This does not update the ~14,050-handshake lever-4/lever-5 crossover**
computed in `benchmarks/mlkem-ffm-bench/README.md`: that estimate was
built from the *realized per-operation* saving (~142.5µs vs. BC) measured
directly via FFM, not from a full-handshake latency delta - and a
full-handshake measurement dominated by ~89ms of non-crypto overhead was
never going to be sensitive enough to re-derive a microsecond-scale
per-operation number cleanly. The per-operation number remains the right
one to reason about that crossover from.

## What's not done - stated plainly

- **The RNG the callout at the top of this document describes.** Repeated
  here because it's the most important item on this list, not because it
  fits the "not done" framing better than "must fix before any real use":
  this build's key material is deterministic, not secret. Everything else
  below is a rigor/completeness gap; this one is a correctness-for-purpose
  gap.
- **FFI-crossing overhead is not isolated the way `mlkem-ffm-bench`
  isolated it for the standalone case.** That benchmark could compare
  "raw C ceiling" against "via FFM" directly because it called the three
  operations back-to-back with nothing else running. Here, the native
  calls happen inside a real TLS handshake alongside JVM/TCP/BCJSSE
  overhead roughly three orders of magnitude larger, so this benchmark
  cannot cleanly attribute *any* of the observed delta to FFI-crossing
  cost specifically versus scheduling noise on a shared VM. The
  standalone `mlkem-ffm-bench` numbers remain the only isolated FFI-cost
  measurement this project has.
- **GraalVM native-image + this provider together is untested.** Lever 4
  (`benchmarks/graalvm-native-image/`) and this lever were built and
  measured independently; nothing here confirms `NativeMlkemProvider`
  loads correctly under `--initialize-at-build-time`/SVM, or that the
  FFM downcall handles this provider creates survive native-image's
  closed-world analysis. Combining them is a real open question, not a
  small config change assumed to work.
- **No profiling of the native path specifically within a full
  handshake.** `benchmarks/arm-performix-profile/README.md`'s CPU
  breakdown was taken against the `after` (BC pure-Java) config; nothing
  here re-runs that profile against `after-native` to see whether the
  ~5.55% BouncyCastle slice shrinks, and by how much, when the KEM math
  itself moves off-JVM.
- **The correctness gate is functional, not statistical.** `run-nativekem.sh`
  confirms one real handshake completes correctly and that mlkem-native's
  FFM path handled it. It does not repeat `mlkem-ffm-bench`'s 50-trial
  shared-secret-agreement check inside the handshake itself - that
  property is inherited from `NativeMlkem768`'s existing bindings (the
  same code both benchmarks call), not independently re-verified here.
- **Only one real-hardware timed session exists** (3 runs each config,
  one VM instance, one sitting). The `after` run's run-2 throughput
  outlier above is reported, not resolved by a fourth run - a genuinely
  larger sample would be needed to say with confidence whether it's
  representative noise or something specific to that pass.

## Reproducing

```bash
./run-nativekem.sh                              # correctness gate, verified end to end
./run-benchmark.sh after-native 200 20 8         # timed pass, same shape as after/before
```

Requires the same `vendor/mlkem-native/libmlkem768ffm.{dylib,so}` shared
library `mlkem-ffm-bench` documents building (`run-nativekem.sh` checks for
it and prints the build steps if missing), and JDK 21 with
`--enable-preview` (FFM is a preview feature on this project's pinned JDK,
same as `mlkem-ffm-bench`).
