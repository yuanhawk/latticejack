# B2 lever 6 (exploratory): Java Vector API for NTT-shaped work

Prompted by a direct question: is closing the gap to `mlkem-native`'s
~4.3-4.6x win (`benchmarks/mlkem-native-bench/`) only possible by leaving
Java, or is there real headroom in pure-Java SIMD via `jdk.incubator.vector`
(the Vector API - no JNI, no FFM, no native-image required, works on any
standard JVM)? This tests that question directly against a representative
kernel, correctness-verified before any timing is trusted, on the same real
hardware as every other B2 finding.

## What this is, and isn't

**Not a byte-exact ML-KEM NTT.** FIPS 203's NTT operates over Q=3329, and
is deliberately *incomplete* (7 layers, not 8) because 512 does not divide
Q-1 for that prime - getting its exact zeta ordering right from scratch is
real cryptographic engineering with real correctness risk this exploratory
question doesn't need to take on. Instead, `VectorNttBench.java` implements
a standard, complete, textbook radix-2 Cooley-Tukey NTT over Q=12289 (the
classic NTT-friendly prime from NewHope/early Kyber - a full transform
exists since 512 | Q-1): same size (256), same computational shape (modular
multiply-add butterflies over an array), same asymptotic cost - testing the
*mechanism*, not producing a drop-in ML-KEM replacement.

**Conservative implementation choices, disclosed rather than hidden:**
- `long[]` arrays throughout (not `int[]`/`short[]`), even though
  coefficients fit in 14 bits - avoids int-to-long widening-conversion calls
  in the Vector API surface (fewer novel API points to get subtly wrong).
  This "wastes" SIMD width: `LongVector.SPECIES_PREFERRED` gets only **2
  lanes** on 128-bit NEON, vs. up to 8 for a properly-packed 16-bit-lane
  implementation. A tuned implementation (what mlkem-native's actual
  assembly does) has real, untested headroom above what's measured here.
- Barrett reduction (not raw `%`) throughout, including the scalar
  baseline - NEON has no vector integer divide, so a naive `%`-based vector
  version wouldn't actually vectorize the reduction step at all, which
  would understate what real optimization work achieves. Using Barrett in
  *both* arms isolates "vectorized vs not," not "division vs multiplication."
- Not constant-time. Throughput benchmark, not production code.

## Correctness, checked before any number is trusted

1. Barrett reduction vs. Java's own `%`, 200,000 random values: exact match.
2. Scalar forward+inverse NTT round-trips to the identity, 100 random trials.
3. **Vector implementation produces bit-identical output to scalar**, 200
   random trials - the check that actually matters: proves the "optimized"
   code computes the same math, not just something plausible-looking.
4. Vector forward+inverse also round-trips to the identity independently.

All four checks run automatically before the benchmark and abort on any
mismatch (`AssertionError`). All passed, every run, both locally and on
real hardware.

## Results

**Local (Apple Silicon, M-series) - the signal that prompted checking real
hardware:** vector was *slower* than scalar, 0.949x (p50 1625ns vs 1542ns).

**Real Azure Cobalt 100 (Neoverse-N2), 3-run averaged (raw:
`cobalt100-3run-raw.txt`), remarkably consistent (1.069x / 1.066x / 1.066x
across the 3 runs):**

| | Scalar (p50) | Vector (p50) |
|---|---|---|
| Run 1 | 2848 ns | 2664 ns |
| Run 2 | 2848 ns | 2672 ns |
| Run 3 | 2848 ns | 2672 ns |
| **Average** | **2848 ns** | **2669 ns** |

**Vector API is ~6.7% faster than scalar on real target hardware** -
**the opposite direction from the local signal**, joining this project's
growing list of levers where a local Mac/dev-machine result didn't predict
the real-hardware outcome (lever 2's promising local signal evaporated on
real hardware; lever 4's real-hardware gap was *larger* than local; here,
local showed a regression and real hardware showed a modest gain - a third,
different failure mode for the same underlying lesson: **don't trust a
local signal in either direction without checking real target hardware**).

## What this means for "is native the only way to optimize PQC in Java"

**No — there's real, measured headroom in pure Java, it's just modest with
this conservative implementation.** ~6.7% is a small fraction of
mlkem-native's ~4.3-4.6x, and doesn't change this project's B2 lever
ranking - but it's a genuine, correctness-verified, positive, non-Arm
-exclusive-instruction result (Vector API is portable pure Java, not tied
to AArch64 the way lever 5's native NEON assembly is) obtained without
leaving the JVM, without JNI, without native-image, and without any of the
GraalVM/BouncyCastle compatibility work lever 4 needed. The untested,
plausible next increment: a 16-bit-lane implementation with proper
int/short packing (4x this benchmark's SIMD width) - not attempted here,
real correctness/implementation risk, but the natural next step if this
lever were pursued further.

## Reproducing

```bash
javac --add-modules jdk.incubator.vector -d target VectorNttBench.java
java  --add-modules jdk.incubator.vector -cp target VectorNttBench
```

Requires JDK 21+ (uses the incubating `jdk.incubator.vector` module,
present since JDK 16; this project's pinned JDK 21 has it). No other
dependencies - single file, no Maven, matching `benchmarks/mlkem-microbench/`'s
pattern.
