# A CPU profile of the full B2 benchmark script, via Arm Performix

**This was originally titled "Resolving the open question" and reported
as a resolution of what consumes the ~88ms handshake latency. An
independent audit (Opus + Fable, run against this project's own claims -
see `WRITEUP.md`'s "Independent audit" section) found that framing
overclaimed what a single CPU-sampling profile of this specific workload
can actually establish. Rewritten below to state precisely what was
measured, what it does and doesn't show, and to restore the "open
question" status to `benchmarks/mlkem-native-bench/README.md` and
`benchmarks/samples/azure-cobalt100-2vcpu/README.md` rather than leave a
claimed resolution standing that the data doesn't fully support. This is
the same self-correction discipline this project has applied throughout
(the walked-back "BC is broken" diagnosis, the four resumption-benchmark
bugs) - applied to this project's own more recent work, not just older
findings.**

## What was actually profiled - and why that matters

[Arm Performix](https://developer.arm.com/servers-and-cloud-computing/arm-performix)
(Arm's own hardware-level profiling toolkit, targeting Neoverse platforms
including the exact Cobalt 100 hardware this project benchmarks on) was
pointed at `./run-benchmark.sh after 200 20 8` on the real Azure Cobalt
100 target, via the `code_hotspots` recipe with `collect_java_stacks=true`
and `sampling_freq=high`. 1,149,736 samples collected. Raw data:
`functions-capture-periodic_sampling.csv.gz` (committed, gzipped, ~500KB);
reproduce the percentages below with `python3 analyze.py`.

**The profiled command is not an isolated warm-handshake loop.**
`run-benchmark.sh` (read directly, not assumed) does, in order: a full
`mvn -q -DskipTests package` build, then four separate passes (latency,
throughput at concurrency 8, bytes-on-wire, resumption), **each
restarting the server JVM fresh** rather than sharing one long-lived
process, plus a fresh client JVM per handshake. So the 1.15M samples
aggregate: the Maven build itself, on the order of 8-10 separate JVM cold
starts, every warmup handshake (which the project's own p50 statistic
deliberately excludes from the *latency* number), and a 4x-oversubscribed
throughput pass, all on 2 vCPUs. **The percentages below characterize CPU
time across that whole script, not a decomposition of the specific 88ms
p50 latency figure.** Treating them as the same thing was the core error
in the original version of this document.

## What was measured

| Category | Share of all CPU samples (whole-script run) |
|---|---|
| `libjvm.so` (JIT compiler passes, GC, class loading, JVM runtime) | **62.96%** |
| Bytecode `Interpreter` (code not yet JIT-compiled) | **10.04%** |
| **All BouncyCastle code combined** (TLS engine, ASN.1, ML-KEM, classical crypto) | **5.55%** |
| `libc.so.6` (syscalls - some genuine socket I/O, some profiler overhead, see caveat below) | 10.77% |

Identifiable C2 JIT compiler passes alone (`PhaseIdealLoop`,
`PhaseChaitin`, `PhaseLive`, `PhaseIterGVN`, a conservative substring
match) account for **20.87%** of all samples.

## Why this is corroborating, not conclusive - three specific problems

**1. CPU-sample share is not wall-clock latency composition.** A sampling
profiler only sees threads while they're *on-CPU*. This project's own B1
table reports client CPU cost per handshake at 5.30ms against an 88.7ms
p50 - meaning roughly 94% of the handshake's wall-clock time is *off-CPU*
(blocking on I/O, scheduling wait), which this profiling method cannot see
at all. A profile answering "what CPU time was spent across this whole
script" is a different question from "what makes up the 88ms of
*latency*," and the original version of this document conflated the two.

**2. This project's own lever 2 data is in tension with "JIT dominates
the warm handshake."** Lever 2 tested `-XX:TieredStopAtLevel=1` (C1-only -
eliminates every C2 pass this profile's top compiler functions belong to)
against the same "after" latency benchmark, and it landed at 87.948ms vs.
an 87.943ms baseline - a ~0.01% difference
(`benchmarks/samples/azure-cobalt100-2vcpu/jvm-tuning-raw.txt`). If C2
JIT compilation were the dominant cost inside the measured warm p50,
removing all of it should have moved that number measurably. It didn't.
The more consistent reading: the JIT activity this profile captured is
concentrated in the build, the cold starts, and the warmup phase this
script also contains - not in the steady-state handshakes the p50
actually measures.

**3. The profiled workload (concurrency-8 throughput pass, embedded in a
mixed 4-pass script) differs from what lever 4 (native-image) itself
measured (single-handshake, concurrency-1, fresh-process cold-start).**
Connecting this profile to lever 4's win requires an inferential bridge
between two different measurement conditions, not a direct decomposition
of the same one.

## What this data does support

- JVM/JIT machinery (build + cold starts + warmup, all present in this
  profiled run) consumes a large share of total CPU time somewhere in
  this project's benchmarking pipeline - consistent with, and a plausible
  partial explanation for, why lever 4's cold-start-focused optimization
  found the largest win of any B2 lever. "Consistent with and corroborating"
  is the honest framing; "directly explains, not inference" was not.
- BouncyCastle's own code (crypto + TLS protocol + ASN.1 combined) is a
  small fraction (5.55%) of total CPU time across this whole script - a
  real, useful data point, even if it doesn't cleanly answer "what's
  inside the 88ms" on its own.
- ML-KEM-specific functions (`Ntt::ntt`/`invNtt`,
  `MLKEMIndCpa::rejectionSampling`, `Poly::baseMultMontgomery`, `CBD::eta2`)
  each individually consume under 0.05% of total samples - consistent
  with `benchmarks/mlkem-microbench/`'s microsecond-scale measurements of
  the same operations, for whatever that consistency is worth given the
  scope caveats above.

## What this data does not resolve

**The original open question - what specifically consumes the ~88ms
warm-handshake p50, given raw ML-KEM math is only tens of microseconds -
is still open.** This profile is a real, useful, but methodologically
limited data point toward it, not a resolution. A cleaner follow-up, not
done here: profile *only* the warm steady-state portion of the latency
pass (e.g. attach to an already-warmed, long-running server process via
Performix's `--pid` option rather than `--workload`, well past any JIT
activity), and pair it with an off-CPU-aware tool (Performix's
`system_utilization` or `syscall_trace_summary` recipes, or Linux
`perf sched`) to account for the ~94% of wall-clock time this CPU-only
profile can't see.

## An honest caveat: profiling overhead in the `libc.so.6` bucket

Walking the call stack for the largest single `libc.so.6` contributor
(`__write`, 4.66% of all samples) found its largest chain (45,453 of
~64,000 total `__write` self-samples) traces back through
`JvmtiExport::post_compiled_method_load` to `libjitdump_jvm_agent.so` -
**Performix's own Java-stack-collection instrumentation**, which attaches
a JVMTI agent that logs a jitdump event for every JIT compilation, so it
can symbolicate JIT-compiled frames in the profile. That's profiler
overhead from `collect_java_stacks=true`, not organic application
behavior. It does not affect the `libjvm.so` share, which is dominated by
core HotSpot C2 compiler internals unrelated to any profiling agent - but
it's one more reason to read the exact percentages as approximate, not to
four significant figures of real precision. Real, organic socket writes
(via `sun.nio.ch.SocketDispatcher::write0`/`NioSocketImpl::tryWrite`,
including one chain running through BC's own
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
# this directory's analyze.py consumes)
```

Or recompute this directory's own numbers without re-profiling:
`python3 analyze.py` (reads the committed `functions-capture-periodic_sampling.csv.gz`).
