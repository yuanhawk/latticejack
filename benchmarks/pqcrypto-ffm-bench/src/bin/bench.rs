use pqcrypto_mlkem::mlkem768::*;
use pqcrypto_ffm::roundtrip_ok;
use std::time::Instant;

const WARMUP: usize = 2000;
const MEASURED: usize = 20000;

fn percentile(sorted: &[u128], p: usize) -> u128 {
    sorted[sorted.len() * p / 100]
}

fn main() {
    for _ in 0..50 {
        if !roundtrip_ok() {
            panic!("correctness check failed: shared secrets did not agree");
        }
    }
    println!("[pqcrypto-bench] correctness: OK (encaps/decaps shared secrets agree, 50 trials)");

    for _ in 0..WARMUP {
        std::hint::black_box(keypair());
    }
    let mut kg_ns: Vec<u128> = Vec::with_capacity(MEASURED);
    for _ in 0..MEASURED {
        let start = Instant::now();
        std::hint::black_box(keypair());
        kg_ns.push(start.elapsed().as_nanos());
    }

    let (pk, sk) = keypair();
    for _ in 0..WARMUP {
        std::hint::black_box(encapsulate(&pk));
    }
    let mut enc_ns: Vec<u128> = Vec::with_capacity(MEASURED);
    for _ in 0..MEASURED {
        let start = Instant::now();
        std::hint::black_box(encapsulate(&pk));
        enc_ns.push(start.elapsed().as_nanos());
    }

    let (_ss, ct) = encapsulate(&pk);
    for _ in 0..WARMUP {
        std::hint::black_box(decapsulate(&ct, &sk));
    }
    let mut dec_ns: Vec<u128> = Vec::with_capacity(MEASURED);
    for _ in 0..MEASURED {
        let start = Instant::now();
        std::hint::black_box(decapsulate(&ct, &sk));
        dec_ns.push(start.elapsed().as_nanos());
    }

    kg_ns.sort();
    enc_ns.sort();
    dec_ns.sort();

    println!();
    println!(
        "keygen (raw Rust/pqcrypto) us: p50={:.2} p10={:.2} p90={:.2}",
        percentile(&kg_ns, 50) as f64 / 1000.0,
        percentile(&kg_ns, 10) as f64 / 1000.0,
        percentile(&kg_ns, 90) as f64 / 1000.0
    );
    println!(
        "encaps (raw Rust/pqcrypto) us: p50={:.2} p10={:.2} p90={:.2}",
        percentile(&enc_ns, 50) as f64 / 1000.0,
        percentile(&enc_ns, 10) as f64 / 1000.0,
        percentile(&enc_ns, 90) as f64 / 1000.0
    );
    println!(
        "decaps (raw Rust/pqcrypto) us: p50={:.2} p10={:.2} p90={:.2}",
        percentile(&dec_ns, 50) as f64 / 1000.0,
        percentile(&dec_ns, 10) as f64 / 1000.0,
        percentile(&dec_ns, 90) as f64 / 1000.0
    );
}
