# B1/B2 characterization harness

The measurement tool for `arm-hackathon-plan.md` §3 Component B1: handshake
latency (p50/p95/p99), throughput under concurrency, bytes-on-wire, and
client-side CPU time, for the classical ("before") and hybrid PQC ("after")
configs. Also carries Component B2's first Arm64 tuning-lever experiment
(session resumption) — see below and `benchmarks/samples/*/README.md` for
the actual finding, which turned out to contradict the plan's assumption
that resumption is a "near-guaranteed win."

## Usage

```bash
./run-benchmark.sh before [iterations] [warmup] [concurrency]
./run-benchmark.sh after  [iterations] [warmup] [concurrency]
```

Defaults: 200 iterations, 20 warmup handshakes (discarded — JIT warmup would
otherwise skew tail latency), concurrency 8 for the throughput pass. Each
invocation runs four separate passes (latency, throughput, bytes-on-wire,
resumption) against a freshly-started `BenchmarkServer`, and writes CSVs to
`benchmarks/results/`.

## Design notes

- **Handshake-only, no application data.** Both `BenchmarkServer` and
  `BenchmarkClient` close the connection immediately after `startHandshake()`
  completes — this is what makes "bytes-on-wire" in the `bytes` pass
  unambiguously handshake-only bytes, not a mix of handshake + payload.
- **Four passes, not one combined run.** Bytes-on-wire measurement routes
  through a local `ByteCountingRelay` (a plain TCP proxy, no TLS-awareness
  needed) to count raw bytes without subclassing `java.net.Socket`. That
  relay adds a loopback hop, which would bias latency numbers if measured in
  the same pass as `latency` mode. Resumption mode does two connections per
  iteration and needs its own warmup accounting — also kept separate.
- **Never enable debug logging during a timed pass.** A real bug, found by
  testing, not by inspection: an earlier version of this script enabled
  BC's `FINEST`-level logging for every "after" pass, not just
  `run-after.sh`'s negotiated-group verification (the only place it's
  actually needed) — printing thousands of debug lines per handshake
  perturbs the very timing being measured. See
  `benchmarks/samples/*/README.md` for how much this actually moved the
  numbers (answer: a lot on a fast dev machine, surprisingly little on the
  target Arm64 hardware — the two aren't necessarily proportional).
- **Server-side handshakes run on a thread pool**, not one-at-a-time on the
  accept loop, so the `throughput` pass measures real concurrent capacity
  rather than being artificially serialized by the server.
- **Resumption detection is best-effort, not authoritative.** `SSLSession`
  ID comparison is used to flag whether a connection likely resumed, but
  it's not a documented API contract and TLS 1.3 implementations aren't
  required to reuse IDs on resumption. The measured latency delta between
  the full and resumed-attempt series (after proper warmup and symmetric
  instrumentation — two more bugs found and fixed, see the samples README)
  is the primary, defensible result; treat the per-connection "resumed"
  flag as a hint, verified independently via debug-log inspection in one-off
  runs rather than trusted every time.
- **No external dependencies** (no HdrHistogram, no benchmarking framework)
  — a sorted-array percentile calc (`Stats.java`) is plenty accurate at
  these sample sizes, matching arm-hackathon-plan.md §8's "a simple, honest
  harness you control beats a heavyweight framework."

## Status

Run for real on the actual target hardware (Azure Cobalt 100 Arm64, not
just a local dev-machine smoke test) — see
`benchmarks/samples/azure-cobalt100-2vcpu/README.md` for the numbers and,
importantly, the four real bugs found and fixed while getting to them.
