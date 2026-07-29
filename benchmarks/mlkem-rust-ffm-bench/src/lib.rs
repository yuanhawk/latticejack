//! B2 lever 7 (exploratory): RustCrypto's `ml-kem` crate, benchmarked the
//! same way as mlkem-native (benchmarks/mlkem-native-bench/) - raw
//! standalone performance, then a real Java integration via FFM
//! (benchmarks/mlkem-rust-ffm-bench/). Prompted directly by a question
//! about Rust's memory safety vs C, and whether that safety carries a
//! performance cost relative to mlkem-native's hand-tuned, formally
//! verified AArch64 assembly.
//!
//! Uses `rand::rngs::StdRng` seeded from a fixed constant (deterministic,
//! no OS-entropy syscalls per call, isolating algorithm cost from RNG
//! -source cost - the same methodology goal `benchmarks/mlkem-native-bench/`
//! had with mlkem-native's own non-secure `notrandombytes` test double).
//! Originally tried `SmallRng` (explicitly non-cryptographic) for this, but
//! ml-kem's own API **refuses to compile** with it - `KemCore::generate`
//! and `Encapsulate::encapsulate` require `rand_core::CryptoRngCore`, a
//! trait `SmallRng` deliberately doesn't implement. A concrete, load
//! -bearing illustration of the type-level safety difference discussed
//! when this lever was proposed: the C/mlkem-native path has no equivalent
//! compiler-enforced guard against an insecure RNG - upstream's own
//! benchmark binary links in a non-secure RNG double specifically because
//! nothing stops it from doing so. `StdRng` (ChaCha12-based) *is*
//! `CryptoRngCore`-marked, satisfying the API while still being a fast,
//! deterministic, seeded, in-memory-only PRNG once seeded - not a real
//! secure RNG in the sense of drawing genuine entropy, but type
//! -appropriate, unlike `SmallRng`. Still not fit for real use (fixed
//! seed), still disclosed as such.

use kem::{Decapsulate, Encapsulate};
use ml_kem::{Ciphertext, Encoded, EncodedSizeUser, KemCore, MlKem768};
use rand::rngs::StdRng;
use rand::SeedableRng;
use std::slice;

pub const PUBLICKEYBYTES: usize = 1184;
pub const SECRETKEYBYTES: usize = 2400;
pub const CIPHERTEXTBYTES: usize = 1088;
pub const SSBYTES: usize = 32;

type Ek = <MlKem768 as KemCore>::EncapsulationKey;
type Dk = <MlKem768 as KemCore>::DecapsulationKey;

/// Runs a full keypair -> encaps -> decaps cycle, returns true if the
/// shared secrets agree (correctness self-check).
pub fn roundtrip_ok(rng: &mut StdRng) -> bool {
    let (dk, ek) = MlKem768::generate(rng);
    let (ct, k_send) = ek.encapsulate(rng).unwrap();
    let k_recv = dk.decapsulate(&ct).unwrap();
    k_send == k_recv
}

pub fn bench_keygen(rng: &mut StdRng) -> (Dk, Ek) {
    MlKem768::generate(rng)
}

pub fn bench_encaps(rng: &mut StdRng, ek: &Ek) -> (Ciphertext<MlKem768>, ml_kem::SharedKey<MlKem768>) {
    ek.encapsulate(rng).unwrap()
}

pub fn bench_decaps(dk: &Dk, ct: &Ciphertext<MlKem768>) -> ml_kem::SharedKey<MlKem768> {
    dk.decapsulate(ct).unwrap()
}

// ---- C-ABI exports for Java FFM ----
// Mirrors mlkem-native's real symbols' argument order (keypair(pk,sk),
// enc(ct,ss,pk), dec(ss,ct,sk)) so the Java-side FFM binding is a straight
// port of MlkemFfmBench.java, just pointed at this library's symbol names.

#[no_mangle]
pub extern "C" fn mlkem_rust_keypair(pk_out: *mut u8, sk_out: *mut u8) -> i32 {
    let mut rng = StdRng::seed_from_u64(0xC0FFEE);
    let (dk, ek) = MlKem768::generate(&mut rng);
    unsafe {
        slice::from_raw_parts_mut(pk_out, PUBLICKEYBYTES).copy_from_slice(ek.as_bytes().as_slice());
        slice::from_raw_parts_mut(sk_out, SECRETKEYBYTES).copy_from_slice(dk.as_bytes().as_slice());
    }
    0
}

#[no_mangle]
pub extern "C" fn mlkem_rust_enc(ct_out: *mut u8, ss_out: *mut u8, pk_in: *const u8) -> i32 {
    let mut rng = StdRng::seed_from_u64(0xC0FFEE);
    let pk_bytes = unsafe { slice::from_raw_parts(pk_in, PUBLICKEYBYTES) };
    let ek = Ek::from_bytes(&Encoded::<Ek>::clone_from_slice(pk_bytes));
    let (ct, ss) = match ek.encapsulate(&mut rng) {
        Ok(v) => v,
        Err(_) => return 1,
    };
    unsafe {
        slice::from_raw_parts_mut(ct_out, CIPHERTEXTBYTES).copy_from_slice(ct.as_slice());
        slice::from_raw_parts_mut(ss_out, SSBYTES).copy_from_slice(ss.as_slice());
    }
    0
}

#[no_mangle]
pub extern "C" fn mlkem_rust_dec(ss_out: *mut u8, ct_in: *const u8, sk_in: *const u8) -> i32 {
    let sk_bytes = unsafe { slice::from_raw_parts(sk_in, SECRETKEYBYTES) };
    let ct_bytes = unsafe { slice::from_raw_parts(ct_in, CIPHERTEXTBYTES) };
    let dk = Dk::from_bytes(&Encoded::<Dk>::clone_from_slice(sk_bytes));
    let ct = Ciphertext::<MlKem768>::clone_from_slice(ct_bytes);
    let ss = match dk.decapsulate(&ct) {
        Ok(v) => v,
        Err(_) => return 1,
    };
    unsafe {
        slice::from_raw_parts_mut(ss_out, SSBYTES).copy_from_slice(ss.as_slice());
    }
    0
}
