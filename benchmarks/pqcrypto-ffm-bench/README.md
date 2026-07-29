# B2 lever 8 (exploratory): pqcrypto (rustpq) — does wrapping C in Rust close the gap?

Lever 7 (RustCrypto's pure-Rust `ml-kem`) landed ~3x slower than mlkem-native's
C, and traced that gap to hand-tuned NEON assembly investment, not language
choice. This tests that conclusion directly: [`rustpq/pqcrypto`](https://github.com/rustpq/pqcrypto)
is architecturally different from RustCrypto — it's a **Rust FFI wrapper
around PQClean's C implementation**, and PQClean's `ml-kem-768/aarch64`
variant has real hand-written NEON assembly (`__asm_NTT.S`,
`__asm_base_mul.S`, `__asm_iNTT.S`, `__asm_poly.S` — confirmed via the
PQClean source tree, and via this build's own compiled artifacts:
`libml-kem-768_aarch64.a` plus `neon_poly.o`/`neon_polyvec.o`/
`neon_symmetric-shake.o` object files, `neon` being one of the crate's
default features). Same two-stage methodology as levers 5 and 7: raw
standalone, then a real Java FFM integration, on the same real Cobalt 100
hardware, 3-run averaged, correctness-verified (shared-secret agreement).

## Results (ML-KEM-768, real Azure Cobalt 100 / Neoverse-N2, Linux aarch64)

| Operation | pqcrypto raw | pqcrypto FFM | mlkem-native FFM (C) | RustCrypto FFM (pure Rust) |
|---|---|---|---|---|
| keygen | 14.57 µs | 14.99 µs | 15.82 µs | 46.60 µs |
| encaps | 15.72 µs | 16.12 µs | 15.11 µs | 44.06 µs |
| decaps | 17.48 µs | 17.79 µs | 16.39 µs | 51.44 µs |
| **total** | **47.78 µs** | **48.90 µs** | **47.32 µs** | **142.11 µs** |

**pqcrypto's FFM-integrated total is within 3.3% of mlkem-native's** (48.90µs
vs. 47.32µs) — essentially the same performance class, not a fraction of
it. Against the JVM baselines: **~3.9x faster than BC, ~3.6x faster than
JDK 25's built-in ML-KEM** — nearly matching lever 5's ~4.0x/~3.7x. Against
lever 7 specifically: **pqcrypto is ~2.9x faster than RustCrypto's
FFM-integrated numbers**, using the exact same host language.

## This confirms lever 7's conclusion with a positive control, not just a negative one

Lever 7 showed *what doesn't work* (portable Rust, no assembly investment
→ ~3x slower than mlkem-native). This lever shows *what does*: the same
host language (Rust), calling into hand-tuned architecture-specific
assembly (via C/PQClean rather than native Rust intrinsics, but the
distinction that matters here is "assembly investment," not which
language emits it), lands within a few percent of mlkem-native's own
performance. **The axis was never "which language" — it's "does this
specific implementation have chip-specific hand-tuning," independent of
what's wrapping it.** RustCrypto could theoretically reach the same
performance with equivalent NEON-intrinsic investment (nothing in Rust
prevents it); it simply hasn't been done for that crate.

## The safety story is also different from lever 7 — and worth being precise about

**This is not a "Rust memory safety at no cost" result the way lever 7
was.** pqcrypto's Rust layer only wraps the *API surface* — the actual
crypto computation, including the hot NTT/base-multiplication loops, runs
in C and hand-written assembly, identical in safety profile to
mlkem-native's hot path (none, inside the math). Rust's ownership model
covers argument marshaling and the public API, not the computation itself.
So while pqcrypto gets close to mlkem-native's speed, it does *not* get
RustCrypto's safety property — those two levers each demonstrate one side
of a real tradeoff (RustCrypto: safe but slow-relative-to-assembly;
pqcrypto: fast but no more memory-safe than C in the hot path), and no
lever tested here gets both simultaneously. A NEON-intrinsic RustCrypto
implementation (unwritten, as lever 7 noted) would be the one that could.

## Reproducing

```bash
# vendor/pqcrypto-ffm/ (not committed - Cargo.toml/src/ here are the exact
# sources used):
cargo build --release   # -> target/release/{libpqcrypto_ffm.{so,dylib}, bench}
./target/release/bench  # raw ceiling

javac --enable-preview --release 21 -d target PqcryptoFfmBench.java
java  --enable-preview -cp target PqcryptoFfmBench /path/to/libpqcrypto_ffm.{so,dylib}
```
