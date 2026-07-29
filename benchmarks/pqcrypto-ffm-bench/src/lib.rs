//! B2 lever 8 (exploratory): pqcrypto-mlkem (rustpq/pqcrypto) - a Rust FFI
//! wrapper around PQClean's C ML-KEM-768 implementation, benchmarked the
//! same way as levers 5 (mlkem-native) and 7 (RustCrypto ml-kem) - raw
//! standalone, then a real Java FFM integration - on the same real Cobalt
//! 100 hardware, 3-run averaged.
//!
//! Architecturally different from lever 7: this is NOT a pure-Rust
//! implementation. PQClean's `ml-kem-768/aarch64` variant (used here via
//! the crate's default `neon` feature) has real hand-written NEON assembly
//! (`__asm_NTT.S`, `__asm_base_mul.S`, `__asm_iNTT.S`, `__asm_poly.S`) -
//! confirmed via the PQClean source tree, not assumed. Rust only wraps the
//! API surface; the actual crypto computation runs in C and assembly,
//! identical in safety profile to mlkem-native's hot path (none, inside
//! the math) - unlike lever 7's RustCrypto crate, where Rust's ownership
//! model covers the computation itself.

use pqcrypto_mlkem::mlkem768::*;
use pqcrypto_traits::kem::{Ciphertext, PublicKey, SecretKey, SharedSecret};
use std::slice;

pub const PUBLICKEYBYTES: usize = 1184;
pub const SECRETKEYBYTES: usize = 2400;
pub const CIPHERTEXTBYTES: usize = 1088;
pub const SSBYTES: usize = 32;

pub fn roundtrip_ok() -> bool {
    let (pk, sk) = keypair();
    let (ss1, ct) = encapsulate(&pk);
    let ss2 = decapsulate(&ct, &sk);
    ss1.as_bytes() == ss2.as_bytes()
}

// ---- C-ABI exports for Java FFM ----
// Same argument order convention as MlkemFfmBench.java/MlkemRustFfmBench.java:
// keypair(pk_out, sk_out), enc(ct_out, ss_out, pk_in), dec(ss_out, ct_in, sk_in).

#[no_mangle]
pub extern "C" fn pqcrypto_keypair(pk_out: *mut u8, sk_out: *mut u8) -> i32 {
    let (pk, sk) = keypair();
    unsafe {
        slice::from_raw_parts_mut(pk_out, PUBLICKEYBYTES).copy_from_slice(pk.as_bytes());
        slice::from_raw_parts_mut(sk_out, SECRETKEYBYTES).copy_from_slice(sk.as_bytes());
    }
    0
}

#[no_mangle]
pub extern "C" fn pqcrypto_enc(ct_out: *mut u8, ss_out: *mut u8, pk_in: *const u8) -> i32 {
    let pk_bytes = unsafe { slice::from_raw_parts(pk_in, PUBLICKEYBYTES) };
    let pk = match PublicKey::from_bytes(pk_bytes) {
        Ok(v) => v,
        Err(_) => return 1,
    };
    let (ss, ct) = encapsulate(&pk);
    unsafe {
        slice::from_raw_parts_mut(ct_out, CIPHERTEXTBYTES).copy_from_slice(ct.as_bytes());
        slice::from_raw_parts_mut(ss_out, SSBYTES).copy_from_slice(ss.as_bytes());
    }
    0
}

#[no_mangle]
pub extern "C" fn pqcrypto_dec(ss_out: *mut u8, ct_in: *const u8, sk_in: *const u8) -> i32 {
    let ct_bytes = unsafe { slice::from_raw_parts(ct_in, CIPHERTEXTBYTES) };
    let sk_bytes = unsafe { slice::from_raw_parts(sk_in, SECRETKEYBYTES) };
    let ct = match Ciphertext::from_bytes(ct_bytes) {
        Ok(v) => v,
        Err(_) => return 1,
    };
    let sk = match SecretKey::from_bytes(sk_bytes) {
        Ok(v) => v,
        Err(_) => return 1,
    };
    let ss = decapsulate(&ct, &sk);
    unsafe {
        slice::from_raw_parts_mut(ss_out, SSBYTES).copy_from_slice(ss.as_bytes());
    }
    0
}
