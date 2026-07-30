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

> **Update: the RNG issue this callout originally warned about is fixed.**
> An independent Opus audit found the most significant problem with an
> earlier version of this feature: the shipped
> `vendor/mlkem-native/libmlkem768ffm.{dylib,so}` links mlkem-native's own
> `notrandombytes()` **TEST double** - a deterministic, fixed-seed
> generator, not a CSPRNG - and the plain `keypair()`/`enc()` entry points
> this code originally called take no randomness argument at all, so every
> key and every encapsulation were fully deterministic and predictable,
> deriving a real TLS session secret from non-secret material. That's
> fixed now, not just documented as a known gap: `NativeMlkem768.java`
> only binds and calls the `_derand` entry points
> (`PQCP_MLKEM_NATIVE_MLKEM768_keypair_derand`/`_enc_derand`), which take
> caller-supplied coins and never call the library's internal
> `randombytes()` at all - so which stub the shared library happens to
> link is now irrelevant. Real coins come from the `java.security.SecureRandom`
> the JCA/JCE SPI contract already hands `NativeMlkemKeyPairGeneratorSpi`
> and `NativeMlkemKemSpi` (previously received and silently discarded, now
> used). **Verified directly, not just argued**: generating two keypairs
> back to back, and generating a keypair in two separate fresh JVM
> processes, all produce different public keys - before this fix, all four
> would have been byte-identical. The plain (non-derand) entry points and
> their method handles were removed from `NativeMlkem768.java` entirely,
> not just left unused alongside the fixed path, so there is no remaining
> way to accidentally call back into the deterministic behavior. "Positively
> verified" below now means what it should: both the wiring/negotiation
> *and* the key material are real.

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
difference is which ML-KEM-768 implementation executes underneath, and
(for `after-native`, below) whether it's the pre-fix deterministic-RNG
build or the post-fix real-`SecureRandom` build.

### Post-fix (real SecureRandom via `_derand`) - current numbers

Measured after the CSPRNG fix above, on the same VM, in one session; the
`after` baseline was also re-run fresh in the same session rather than
reusing the pre-fix numbers below, so the comparison is apples-to-apples
against the *current* code on *both* sides.

| Config | Run | p50 (ms) | p95 (ms) | p99 (ms) | mean (ms) | Throughput (handshakes/sec) |
|---|---|---|---|---|---|---|
| after | 1 | 88.800 | 95.878 | 101.195 | 86.044 | 77.1 |
| after | 2 | 89.047 | 96.266 | 99.744 | 86.911 | 74.9 |
| after | 3 | 89.021 | 95.795 | 103.094 | 85.354 | 74.7 |
| after-native | 1 | 89.937 | 97.189 | 101.937 | 87.802 | 72.9 |
| after-native | 2 | 89.186 | 97.631 | 100.827 | 87.239 | 73.3 |
| after-native | 3 | 89.523 | 96.629 | 99.949 | 87.015 | 74.6 |

Averaged across the 3 runs (each run's own p50 is already that run's
median, per this project's established 3-run methodology):

| Metric | after (BC pure-Java ML-KEM) | after-native (mlkem-native NEON, real SecureRandom) | Delta |
|---|---|---|---|
| p50 latency | 88.956 ms | 89.549 ms | +0.67% (native higher) |
| p95 latency | 95.980 ms | 97.150 ms | +1.22% (native higher) |
| p99 latency | 101.344 ms | 100.904 ms | −0.43% (~noise) |
| mean latency | 86.103 ms | 87.352 ms | +1.45% (native higher) |
| Throughput | 75.567 h/s | 73.600 h/s | −2.60% (native lower) |

**The honest finding: fixing the RNG erased the modest edge the pre-fix
numbers showed, and the reason is exactly what was predicted before this
was re-measured** (see "What's not done" below, pre-fix version) - real
`SecureRandom.nextBytes()` plus marshalling the coins across the FFM
boundary as an additional argument is new cost the pre-fix numbers never
paid, and it was large enough, relative to a full-handshake baseline this
close to parity already, to flip a small apparent native advantage into a
small (and by these run-to-run swings, arguably noise-level) native
disadvantage at p50/p95/throughput. p99 lands basically even. **This is
not a regression to be explained away - it's the same "measure, don't
assume" discipline that produced the original ~4x per-operation number
catching up with a cost that number never included.** For context on
scale: run-to-run variation *within* a single config here is itself
2-3% (e.g. `after`'s own throughput ranges 74.7-77.1 h/s across its 3
runs) - roughly the same size as the between-config deltas above, so
these should be read as "no longer a clear net edge either way at the
full-handshake level," not as a confidently-measured slowdown.

### Pre-fix (deterministic RNG) numbers - superseded, kept for the record

The table below is what this section originally reported, before the
CSPRNG fix above. Left here rather than deleted, both because silently
replacing numbers without a trace would violate this project's own
disclosure practice, and because the *change* between the two tables is
itself the interesting finding (previous section).

| Config | Run | p50 (ms) | p95 (ms) | p99 (ms) | mean (ms) | Throughput (handshakes/sec) |
|---|---|---|---|---|---|---|
| after | 1 | 88.363 | 95.675 | 99.688 | 85.252 | 78.7 |
| after | 2 | 90.410 | 102.533 | 113.853 | 86.691 | 58.2 |
| after | 3 | 88.216 | 95.900 | 99.940 | 84.504 | 76.5 |
| after-native | 1 | 89.003 | 96.211 | 101.569 | 86.217 | 74.5 |
| after-native | 2 | 89.204 | 94.690 | 97.934 | 84.797 | 75.9 |
| after-native | 3 | 88.777 | 96.509 | 101.041 | 84.947 | 77.5 |

Averaged: p50 88.996ms vs 88.995ms (~0%), p95 98.036ms vs 95.803ms
(native −2.3%), p99 104.494ms vs 100.181ms (native −4.1%), throughput
71.133 vs 75.967 h/s (native +6.8%). The `after` run's run-2 throughput
pass in this pre-fix table shows a visible outlier (58.2 h/s vs. ~77 h/s
elsewhere) - consistent with shared-VM scheduling noise on a 2-vCPU
instance, reported as measured rather than discarded at the time,
matching this project's practice elsewhere (e.g. the session-resumption
findings in `benchmarks/samples/azure-cobalt100-2vcpu/README.md`) of not
silently dropping an inconvenient data point. Both tables' full-handshake
latency (~89ms) being dominated by JVM/TLS/connection-setup overhead
rather than ML-KEM-768 compute itself (`mlkem-ffm-bench` measured ~47µs
total per operation set via the same FFM path - roughly three orders of
magnitude below the handshake floor) still holds and explains why *either*
table's deltas are small relative to the whole handshake - it's the sign
and size of the small delta that moved once real randomness was paid for.

**This does not update the ~14,050-handshake lever-4/lever-5 crossover**
computed in `benchmarks/mlkem-ffm-bench/README.md`: that estimate was
built from the *realized per-operation* saving (~142.5µs vs. BC) measured
directly via FFM, not from a full-handshake latency delta - and a
full-handshake measurement dominated by ~89ms of non-crypto overhead was
never going to be sensitive enough to re-derive a microsecond-scale
per-operation number cleanly. The per-operation number remains the right
one to reason about that crossover from.

## What's not done - stated plainly

- **The RNG gap the top-of-document callout used to describe is now
  fixed** - see that callout for the detail. Kept as a crossed-off first
  item here rather than deleted, since a reader arriving at this section
  first (skipping the top) deserves the same information: this was the
  single correctness-for-purpose gap in this feature, not a
  rigor/completeness gap like everything below, and it no longer applies.
  Verified both for correctness (differing keypairs across repeated calls
  and fresh processes, where before the fix all output was
  byte-identical) and now re-measured for performance on real hardware
  (below) - the "expected small" guess about the fix's own cost was
  checked, not left assumed, and turned out to matter more than
  "small": see the post-fix results table above.
- **The CSPRNG fix's own performance cost has now been measured, and it
  matters.** Real coins via `SecureRandom.nextBytes()` plus marshalling
  them across the FFM boundary as an additional argument is new cost that
  wasn't in the original ~4.0x/~3.7x per-operation numbers or the original
  full-handshake table - those were measured against the deterministic-stub
  path. Re-measured on the same VM in the same session as the fix: the
  small full-handshake edge the pre-fix table showed (native ~2-4% lower
  p95/p99, ~7% higher throughput) is gone post-fix, replaced by a
  comparably small edge in the *other* direction at p50/p95/throughput
  (see the post-fix table above) - close enough to this VM's own
  run-to-run noise that "no longer a clear net edge either way" is the
  honest read, not "the fix made it slower." The per-operation ceiling
  (~4.0x/~3.7x) is unaffected - that's a separate, isolated measurement
  (`mlkem-ffm-bench`) this full-handshake number was never meant to
  re-derive.
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
