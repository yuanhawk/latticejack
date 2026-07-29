# Resolving the open question: what actually consumes the ~88ms handshake latency?

`benchmarks/mlkem-native-bench/README.md` and `benchmarks/samples/azure-cobalt100-2vcpu/README.md`
both flagged the same open question, deliberately left unresolved rather
than overclaimed: B1's ~88ms p50 handshake latency for the PQC config is
three orders of magnitude larger than raw ML-KEM crypto cost (~190µs at
BC's slowest, per `benchmarks/mlkem-microbench/`) - so what actually
consumes the rest? This uses [Arm Performix](https://developer.arm.com/servers-and-cloud-computing/arm-performix)
(Arm's own hardware-level profiling toolkit, targeting Neoverse platforms
including the exact Cobalt 100 hardware this project benchmarks on) to
answer it directly, with real CPU sampling data - not inference.

## Method

Profiled the **exact command that produces the 88ms number** -
`./run-benchmark.sh after 200 20 8` - on the real Azure Cobalt 100 target,
via Performix's `code_hotspots` recipe with `collect_java_stacks=true` and
`sampling_freq=high`, high-frequency CPU sampling with full Java stack
symbolication (not just native frames). 1,149,736 samples collected across
the whole run.

## Result: it's the JVM, not the crypto

| Category | Share of all CPU samples |
|---|---|
| `libjvm.so` (JIT compiler passes, GC, class loading, JVM runtime) | **62.96%** |
| Bytecode `Interpreter` (code not yet JIT-compiled) | **10.04%** |
| **All BouncyCastle code combined** (TLS engine, ASN.1, ML-KEM, classical crypto) | **5.55%** |
| `libc.so.6` (syscalls - some genuine socket I/O, some profiler overhead, see caveat below) | 10.77% |

**Identifiable C2 JIT compiler passes alone** (`PhaseIdealLoop`,
`PhaseChaitin` register allocation, `PhaseLive`, `PhaseIterGVN`, and
similar - a conservative substring match, undercounting total JVM
overhead) already account for **20.87%** of all samples - roughly 4x more
than *all* of BouncyCastle's crypto and protocol code combined.

**The answer: ~73% of total CPU time (`libjvm.so` + `Interpreter`) is JVM
overhead - active JIT compilation and not-yet-compiled interpreted
execution - not TLS protocol processing and not cryptographic computation.**
BC's ML-KEM-specific functions do appear in the profile
(`Ntt::ntt`/`invNtt`, `MLKEMIndCpa::rejectionSampling`,
`Poly::baseMultMontgomery`, `CBD::eta2`), each individually under 0.05% of
total samples - consistent with, and now directly explaining, why the
mlkem-microbench numbers (µs-scale) looked so small next to the B1
handshake numbers (tens of ms): they *are* small, in absolute terms, on
this hardware. The other ~99.95% of BC's own 5.55% share is TLS handshake
logic, ASN.1 DER encoding/decoding, and JSSE algorithm-constraint checking
- protocol overhead, not crypto math.

## Why this matters for the rest of this project's findings

This isn't a new, disconnected result - it's hard evidence for the exact
mechanism `benchmarks/graalvm-native-image/README.md` (B2 lever 4) already
found and exploited without this level of proof: **JIT compilation and
JVM startup/warmup cost, not crypto compute, is the dominant lever on this
hardware.** Lever 4's ~7.9x cold-start win by eliminating JIT entirely via
ahead-of-time compilation is now directly explained by this profile, not
just correlated with it. It also reframes why levers 1-3 (session
resumption, JVM GC/heap tuning, JDK 25's crypto-specific intrinsics) found
so little: none of them touch JIT compilation cost itself, which is where
most of the time actually goes.

## An honest caveat: profiling overhead in the `libc.so.6` bucket

Walking the call stack for the largest single `libc.so.6` contributor
(`__write`, 4.66% of all samples) found its largest chain
(45,453 of ~64,000 total `__write` self-samples) traces back through
`JvmtiExport::post_compiled_method_load` to `libjitdump_jvm_agent.so` -
**Performix's own Java-stack-collection instrumentation**, which attaches
a JVMTI agent that logs a jitdump event for every JIT compilation, so it
can symbolicate JIT-compiled frames in the profile. That's profiler
overhead from `collect_java_stacks=true`, not organic application
behavior - disclosed here rather than left to inflate the `libc.so.6`
number silently. It does **not** affect the headline finding: the
`PhaseIdealLoop`/`PhaseChaitin`/`PhaseLive`/`PhaseIterGVN` functions
driving the 62.96% `libjvm.so` share are core HotSpot C2 compiler
internals, unrelated to any profiling agent - they run identically whether
or not Performix is watching. Real, organic socket writes (via
`sun.nio.ch.SocketDispatcher::write0`/`NioSocketImpl::tryWrite`, including
one chain running through BC's own
`TlsClientProtocol::handleHandshakeMessage`) are also present in the
`__write` breakdown, distinct from the profiler-induced chains.

## Reproducing

```bash
# Install Arm Performix CLI (free, no account required):
# https://developer.arm.com/servers-and-cloud-computing/arm-performix

apx target add "ssh://user@host:22:/path/to/key:auth=key" --name <target>
apx target prepare --target <target>
apx recipe run code_hotspots --target <target> \
  --workload "./run-benchmark.sh after 200 20 8" \
  --working-dir /path/to/latticejack \
  --use-shell --deploy-tools \
  --param sampling_freq=high --param collect_java_stacks=true \
  --timeout 120

apx run list                      # find the run ID
apx run prepare-render <run_id>   # inspect available renderers/visualizations
# raw CSV data lands under ~/.local/share/apxd/runs/<run_id>/tool/neoprof/0/output/
# (functions-capture-periodic_sampling.csv has the per-sample function attribution
# used to compute the percentages above)
```
