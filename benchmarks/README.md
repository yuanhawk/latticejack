# B1 characterization harness

The measurement tool for `arm-hackathon-plan.md` §3 Component B1: handshake
latency (p50/p95/p99), throughput under concurrency, bytes-on-wire, and
client-side CPU time, for the classical ("before") and hybrid PQC ("after")
configs — the baseline B2's Arm64 tuning work will later measure its delta
against.

## Usage

```bash
./run-benchmark.sh before [iterations] [warmup] [concurrency]
./run-benchmark.sh after  [iterations] [warmup] [concurrency]
```

Defaults: 200 iterations, 20 warmup handshakes (discarded — JIT warmup would
otherwise skew tail latency), concurrency 8 for the throughput pass. Each
invocation runs three separate passes (latency, throughput, bytes-on-wire)
against a freshly-started `BenchmarkServer`, and writes latency/bytes CSVs to
`benchmarks/results/`.

## Design notes

- **Handshake-only, no application data.** Both `BenchmarkServer` and
  `BenchmarkClient` close the connection immediately after `startHandshake()`
  completes — this is what makes "bytes-on-wire" in the `bytes` pass
  unambiguously handshake-only bytes, not a mix of handshake + payload.
- **Three passes, not one combined run.** Bytes-on-wire measurement routes
  through a local `ByteCountingRelay` (a plain TCP proxy, no TLS-awareness
  needed) to count raw bytes without subclassing `java.net.Socket`. That
  relay adds a loopback hop, which would bias latency numbers if measured in
  the same pass as `latency` mode — so they're kept separate.
- **Server-side handshakes run on a thread pool**, not one-at-a-time on the
  accept loop, so the `throughput` pass measures real concurrent capacity
  rather than being artificially serialized by the server.
- **No external dependencies** (no HdrHistogram, no benchmarking framework)
  — a sorted-array percentile calc (`Stats.java`) is plenty accurate at
  these sample sizes, matching arm-hackathon-plan.md §8's "a simple, honest
  harness you control beats a heavyweight framework."

## Status

This has been smoke-tested locally (Apple Silicon, i.e. also arm64) to
confirm the harness itself works and produces sane, consistent numbers — it
has **not** yet been run on the actual target hardware (AWS Graviton /
Oracle Ampere). Local-Mac numbers are not the numbers to cite in the
submission; re-run this on real Arm64 cloud hardware once provisioned
(`docs/arm64-instance-setup.md`) for the numbers that matter.
