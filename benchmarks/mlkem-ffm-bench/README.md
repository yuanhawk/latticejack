# B2 lever 5, closed: mlkem-native via Java's Foreign Function & Memory API

`benchmarks/mlkem-native-bench/` measured mlkem-native's raw C performance
standalone - explicitly *not* an integration, just the ceiling. This closes
that loop: `MlkemFfmBench.java` calls the real ML-KEM-768 C implementation
from actual Java code via `java.lang.foreign` (the FFM API - no JNI, no
native-image required, works against a regular JVM), and measures what
survives once the real FFI crossing cost is included.

## Results (real Azure Cobalt 100 / Neoverse-N2, Linux aarch64, 3-run averaged)

Correctness verified before any timing was trusted: encaps/decaps shared
-secret agreement checked for 50 trials per run (`ssEnc == ssDec` after a
real keypair → encaps → decaps round trip through the FFM boundary) - the
right correctness check here, since this isn't reimplementing crypto (like
`benchmarks/vector-api-ntt/` had to), just calling an already-correct
library, so the check is "does the FFI plumbing work," not "is the
algorithm right."

| Operation | Raw C ceiling | **Via FFM (real)** | FFI overhead | vs BC | vs JDK 25 |
|---|---|---|---|---|---|
| keygen | 11.85 µs | **15.82 µs** | +3.97 µs (+33.5%) | 4.59x | 4.13x |
| encaps | 13.05 µs | **15.11 µs** | +2.06 µs (+15.8%) | 3.89x | 3.71x |
| decaps | 16.22 µs | **16.39 µs** | +0.17 µs (+1.0%) | 3.56x | 3.33x |
| **total** | **41.12 µs** | **47.32 µs** | **+6.20 µs (+15.1%)** | **4.01x** | **3.72x** |

**The FFI crossing cost is real, but small relative to the win, and shrinks
as a fraction of total time the longer the native call runs** - keygen
(cheapest native op) absorbs the largest relative overhead (+33.5%), decaps
(most expensive) barely moves (+1.0%). Combined across all three operations
a full handshake needs, **~85% of mlkem-native's raw ceiling survives real
integration**: ~4.0x faster than BC and ~3.7x faster than JDK 25's own
built-in ML-KEM, end-to-end through the FFM boundary, not just in isolation.

This is now the largest *realized* (not ceiling-only) per-operation gap
this project has measured - roughly 40-60x bigger than lever 3's JDK 25
intrinsic gain (~8.7%) and lever 6's exploratory Vector API result (~6.7%),
while lever 4 (GraalVM native-image, ~7.9x) remains the largest *overall*
B2 finding, attacking a different cost entirely (see
`benchmarks/mlkem-native-bench/README.md`'s lever-4-vs-lever-5 crossover
-point analysis - the *qualitative* "different costs, different deployment
shapes" conclusion is unchanged, but the crossover point itself moves
slightly, from ~13,460 handshakes (that analysis's original ceiling-based
estimate) to ~14,050 (recomputed from this integration's realized saving,
142.5µs vs. BC rather than the ceiling's 148.7µs) - this integration
confirms lever 5's number is real and not just a hypothetical ceiling, and
updates the crossover accordingly rather than leaving the ceiling-based
figure standing uncorrected).

## JDK 25 re-baseline: does the JDK 21 preview-flag confound matter?

The table above compares JDK 21 FFM numbers (this benchmark, `--enable-preview`
required) against `mlkem-microbench`'s BC/JDK 25-builtin numbers, which run
on JDK 25 — a disclosed but real cross-JDK confound (different JIT, different
FFM maturity: preview in 21, finalized in 22+). This was re-measured to check
whether removing that confound moves the result.

Recompiled and reran unchanged on the same real Azure Cobalt 100 VM, same
native `libmlkem768ffm.so`, only the JVM swapped — JDK 25 confirmed present
(`/usr/lib/jvm/java-25-openjdk-arm64`, `openjdk 25.0.3`), compiled with plain
`javac -d target MlkemFfmBench.java` (**no** `--enable-preview` — confirms
FFM's JDK 22+ finalization removes the flag requirement entirely), 3 runs,
correctness re-verified each run (shared-secret agreement, 50 trials):

| | keygen | encaps | decaps | **total** |
|---|---|---|---|---|
| JDK 21 (committed baseline, above) | 15.82 µs | 15.11 µs | 16.39 µs | **47.32 µs** |
| JDK 25 (this re-baseline) | 15.77 µs | 15.17 µs | 16.51 µs | **47.45 µs** |
| Delta | −0.3% | +0.4% | +0.7% | **+0.27%** |

**+0.27% total — within run-to-run noise, essentially identical.** Recomputed
ratios against the same (already-JDK-25) BC/JDK-25-builtin baseline: **4.00x
vs. BC / 3.71x vs. JDK 25-builtin** (was 4.01x/3.72x under the JDK 21
cross-version comparison — unchanged within rounding). **Finding, reported
either way per the task that motivated this check: the cross-JDK confound
did not matter here.** The FFM-integration win was never JDK-version-sensitive
to begin with — the earlier JDK21-vs-JDK25(BC) comparison, while a real
disclosed confound in principle, was not masking or inflating a real effect
in practice. Same result across the two other FFM benchmarks — see
[`mlkem-rust-ffm-bench/README.md`](../mlkem-rust-ffm-bench/README.md#jdk-25-re-baseline)
and
[`pqcrypto-ffm-bench/README.md`](../pqcrypto-ffm-bench/README.md#jdk-25-re-baseline).

## What this is, and isn't

**A real, working, correctness-verified FFM integration of the crypto
primitive** - not a hypothetical anymore. **Not a full end-to-end TLS
handshake integration** - this calls the three KEM operations directly,
not through `ProviderBootstrap`/BCJSSE/a JCA `KEM` SPI wired into the
actual mTLS reference service. Wiring this into a real JCA provider so an
actual handshake uses it (matching the rigor of levers 1-4, which all
measure real end-to-end handshakes) is the next step if this were pursued
further - real, non-trivial engineering (implementing `KEMSpi`/BC-internal
KEM interfaces correctly, integrating into the existing hybrid
X25519MLKEM768 group logic), not attempted here.

## How the shared library was built

mlkem-native's own build system only produces static archives (`.a`), not
shared libraries - built one from the same static archive
`benchmarks/mlkem-native-bench/` already used, plus mlkem-native's own
test-only `randombytes()` double (`test/notrandombytes/notrandombytes.c.o`
- **not cryptographically secure**, fine for a throughput benchmark whose
keys are never used for anything, matching the exact same non-production
disclosure `benchmarks/mlkem-native-bench/` already made about the
upstream benchmark binary using the same double):

```bash
# Linux:
gcc -shared -o libmlkem768ffm.so \
  -Wl,--whole-archive test/build/libmlkem768.a \
  test/build/mlkem768/test/notrandombytes/notrandombytes.c.o \
  -Wl,--no-whole-archive

# macOS:
gcc -shared -o libmlkem768ffm.dylib -Wl,-all_load \
  test/build/libmlkem768.a test/build/mlkem768/test/notrandombytes/notrandombytes.c.o
```

Exported symbols are `PQCP_MLKEM_NATIVE_MLKEM768_{keypair,enc,dec}` - the
default namespace mlkem-native's main build uses (not the `mlkem_*` alias
some of its `examples/` use with a custom `MLK_CONFIG_NAMESPACE_PREFIX`).

## Reproducing

Requires JDK 21+ - FFM is finalized in JDK 22+, still a **preview feature**
in this project's pinned JDK 21, so compile and run with `--enable-preview`:

```bash
git clone https://github.com/pq-code-package/mlkem-native.git
cd mlkem-native
git checkout 61c831345d8fec5b2ba9d727dddb486b7cce512a
git apply /path/to/latticejack/benchmarks/mlkem-native-bench/hal-walltime.patch  # not required for this benchmark, but keeps the checkout consistent with mlkem-native-bench's
make CYCLES=NO bench_768   # produces test/build/libmlkem768.a
gcc -shared -o libmlkem768ffm.so -Wl,--whole-archive test/build/libmlkem768.a \
  test/build/mlkem768/test/notrandombytes/notrandombytes.c.o -Wl,--no-whole-archive

javac --enable-preview --release 21 -d target MlkemFfmBench.java
java  --enable-preview -cp target MlkemFfmBench /path/to/libmlkem768ffm.so
```
