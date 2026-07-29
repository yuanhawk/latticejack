# B2 lever 7 (exploratory): RustCrypto `ml-kem`, memory safety vs. performance

Prompted by a direct question: does Rust's memory safety (vs. C) come at a
measurable performance cost for ML-KEM, relative to mlkem-native's
hand-tuned, formally verified AArch64 assembly? Same two-stage methodology
as lever 5 (`benchmarks/mlkem-native-bench/`, `benchmarks/mlkem-ffm-bench/`):
raw standalone performance, then a real Java integration via FFM, both on
the same real Cobalt 100 hardware, 3-run averaged.

## Results (ML-KEM-768, real Azure Cobalt 100 / Neoverse-N2, Linux aarch64)

Correctness verified before any timing was trusted (shared-secret agreement,
50 trials, every run - raw evidence: `cobalt100-raw-rust-3run.txt`,
`cobalt100-ffm-rust-3run.txt`).

| Operation | RustCrypto raw | RustCrypto via FFM | mlkem-native raw (C) | mlkem-native via FFM (C) | BC (warm) | JDK 25 (warm) |
|---|---|---|---|---|---|---|
| keygen | 39.20 µs | 46.60 µs | 11.85 µs | 15.82 µs | 72.66 µs | 65.35 µs |
| encaps | 37.01 µs | 44.06 µs | 13.05 µs | 15.11 µs | 58.83 µs | 56.02 µs |
| decaps | 47.88 µs | 51.44 µs | 16.22 µs | 16.39 µs | 58.33 µs | 54.61 µs |
| **total** | **124.09 µs** | **142.11 µs** | **41.12 µs** | **47.32 µs** | **189.82 µs** | **175.98 µs** |

**The answer to the original question: no, memory safety itself isn't the
cost.** FFM-integrated RustCrypto is still faster than pure Java - ~1.34x
vs BC, ~1.24x vs JDK 25's own built-in ML-KEM - so switching from managed
Java to a memory-safe systems language is a real, if modest, net win on its
own. **But it's ~3.0x *slower* than the FFM-integrated C** (mlkem-native).
That gap isn't explained by Rust vs. C as languages - it's explained by
what each library actually *is*: mlkem-native is architecture-specific,
hand-written, formally-proven AArch64 NEON assembly, built by cryptographic
engineers specifically targeting this chip family. RustCrypto's `ml-kem` is
a portable, generic, safe-Rust implementation with no NEON-specific tuning
at all. Nothing about Rust as a language prevents writing the equivalent of
mlkem-native's assembly (`std::arch::aarch64` intrinsics exist for exactly
this) - nobody has invested that engineering effort into this particular
crate yet. **The real axis was always "hand-tuned for this chip" vs.
"portable," not "which language."**

FFI overhead (raw → via FFM) is proportionally similar to what lever 5
found for C - largest for the cheapest operation, smallest for the most
expensive: keygen +18.9%, encaps +19.0%, decaps +7.4% (total +14.5%),
essentially the same magnitude and shape mlkem-native's own FFM integration
showed, confirming that behavior is a property of the FFM mechanism, not
of which native language sits behind it.

## A concrete illustration of Rust's type-level safety

Building this surfaced something directly relevant to the safety
discussion that prompted this lever. The implementation originally used
`rand::rngs::SmallRng` (explicitly documented as non-cryptographic, chosen
to match mlkem-native's own non-secure `notrandombytes` test double for a
fair apples-to-apples comparison) - **and it failed to compile.**
`ml-kem`'s own API requires `rand_core::CryptoRngCore` for `generate()` and
`encapsulate()`; `SmallRng` deliberately doesn't implement it, and the
Rust compiler rejects the mismatch at build time, not at review time or
runtime. There is no equivalent guard on the C side - mlkem-native's own
official benchmark binary links in a non-secure RNG double specifically
because *nothing stops it from doing so*. Switched to `StdRng` (a
ChaCha12-based, `CryptoRngCore`-marked PRNG, still seeded deterministically
for benchmark reproducibility, still not fit for real use because of that
fixed seed) to satisfy the API. This is a small, concrete example of the
type-level safety difference discussed earlier: not just "fewer memory bugs
at runtime" but categories of misuse the compiler refuses to allow at all.

## What this is, and isn't

Same scope boundary as lever 5: real correctness-verified library calls via
FFM, not wired into `ProviderBootstrap`/BCJSSE or the actual mTLS
handshake. And explicitly not a claim that Rust *can't* match mlkem-native
- only that *this specific, unmodified crate* doesn't, because it hasn't
been given the same hand-tuning investment. A NEON-intrinsic-optimized
Rust implementation is plausible future work, untested here.

## Reproducing

```bash
# vendor/mlkem-rust-ffm/ (not committed - see Cargo.toml/src/ here for the
# exact sources used):
cargo build --release   # -> target/release/{libmlkem_rust_ffm.{so,dylib}, bench}
./target/release/bench  # raw Rust ceiling

javac --enable-preview --release 21 -d target MlkemRustFfmBench.java
java  --enable-preview -cp target MlkemRustFfmBench /path/to/libmlkem_rust_ffm.{so,dylib}
```
