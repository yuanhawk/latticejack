use mlkem_rust_ffm::{bench_decaps, bench_encaps, bench_keygen, roundtrip_ok};
use rand::rngs::StdRng;
use rand::SeedableRng;
use std::time::Instant;

const WARMUP: usize = 2000;
const MEASURED: usize = 20000;

fn percentile(sorted: &[u128], p: usize) -> u128 {
    sorted[sorted.len() * p / 100]
}

fn main() {
    let mut rng = StdRng::seed_from_u64(1);

    for _ in 0..50 {
        if !roundtrip_ok(&mut rng) {
            panic!("correctness check failed: shared secrets did not agree");
        }
    }
    println!("[mlkem-rust-bench] correctness: OK (encaps/decaps shared secrets agree, 50 trials)");

    // keygen
    for _ in 0..WARMUP {
        std::hint::black_box(bench_keygen(&mut rng));
    }
    let mut kg_ns: Vec<u128> = Vec::with_capacity(MEASURED);
    for _ in 0..MEASURED {
        let start = Instant::now();
        std::hint::black_box(bench_keygen(&mut rng));
        kg_ns.push(start.elapsed().as_nanos());
    }

    // encaps (fixed ek, matching the C benchmark's own methodology)
    let (dk, ek) = bench_keygen(&mut rng);
    for _ in 0..WARMUP {
        std::hint::black_box(bench_encaps(&mut rng, &ek));
    }
    let mut enc_ns: Vec<u128> = Vec::with_capacity(MEASURED);
    for _ in 0..MEASURED {
        let start = Instant::now();
        std::hint::black_box(bench_encaps(&mut rng, &ek));
        enc_ns.push(start.elapsed().as_nanos());
    }

    // decaps (fixed dk/ct)
    let (ct, _ss) = bench_encaps(&mut rng, &ek);
    for _ in 0..WARMUP {
        std::hint::black_box(bench_decaps(&dk, &ct));
    }
    let mut dec_ns: Vec<u128> = Vec::with_capacity(MEASURED);
    for _ in 0..MEASURED {
        let start = Instant::now();
        std::hint::black_box(bench_decaps(&dk, &ct));
        dec_ns.push(start.elapsed().as_nanos());
    }

    kg_ns.sort();
    enc_ns.sort();
    dec_ns.sort();

    println!();
    println!(
        "keygen (raw Rust) us: p50={:.2} p10={:.2} p90={:.2}",
        percentile(&kg_ns, 50) as f64 / 1000.0,
        percentile(&kg_ns, 10) as f64 / 1000.0,
        percentile(&kg_ns, 90) as f64 / 1000.0
    );
    println!(
        "encaps (raw Rust) us: p50={:.2} p10={:.2} p90={:.2}",
        percentile(&enc_ns, 50) as f64 / 1000.0,
        percentile(&enc_ns, 10) as f64 / 1000.0,
        percentile(&enc_ns, 90) as f64 / 1000.0
    );
    println!(
        "decaps (raw Rust) us: p50={:.2} p10={:.2} p90={:.2}",
        percentile(&dec_ns, 50) as f64 / 1000.0,
        percentile(&dec_ns, 10) as f64 / 1000.0,
        percentile(&dec_ns, 90) as f64 / 1000.0
    );
}
