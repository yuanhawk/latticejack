# Real AI inference workload behind the hybrid-PQC TLS handshake

This directory documents (no code of its own — the code lives in
`src/main/java/com/latticejack/pqc/aiproxy/`, driven by `../../run-ai.sh`)
a real local LLM inference server, KleidiAI-accelerated, fronted by this
project's actual hybrid X25519MLKEM768 TLS 1.3 handshake — the same
handshake `./run after` verifies, not a separate demo path.

## What this demonstrates, and why it exists

Two independent audits (Opus and Fable) flagged the same theme-fit risk
against this hackathon's "AI solution on Arm" gate: `WRITEUP.md`'s
"Why this is an AI solution" argument was, until this work, entirely
prose — a Claude Code Skill (Component C), an AI-adversarial-review
process applied to this project's own claims, and an assertion that "TLS
is what AI infrastructure runs over." That third leg was true but
un-demonstrated: nothing in the repo actually served an AI workload over
the migrated path. This closes that specific gap: a real quantized LLM
(Llama-3.2-1B-Instruct, Q4_0), served by `llama.cpp` with the KleidiAI
Arm CPU backend, answering a prompt sent by a client over a real,
HelloRetryRequest-verified hybrid-PQC TLS 1.3 connection, with the
request and reply both crossing the encrypted channel.

This is additional evidence for the existing three-legged argument, not a
replacement for it — see the WRITEUP.md update below for exactly what
tier of evidence it is and isn't.

Two phases happened, in order:

1. **Local prototype** (Apple M5 Mac) — worked out the integration
   mechanics (build flags, model format, TLS-proxy plumbing, KleidiAI
   verification method) cheaply before repeating on real target hardware.
   Full detail: [`component-ai/README.md`](../../component-ai/README.md).
   That document predates phase 2 below and still frames itself as
   "local prototype only... not the project's real hardware target" —
   read it as the phase-1 build log, not the final result.
2. **Real hardware** (Azure `Standard_D2pls_v6`, Cobalt 100/Neoverse-N2,
   2 vCPU, 3.8GB RAM, Ubuntu 24.04 aarch64 — the same instance every
   other real-hardware number in this project was measured on) — rebuilt
   llama.cpp+KleidiAI natively on that VM (not reused from the Mac —
   different CPU, different toolchain), re-verified KleidiAI engagement
   on that hardware specifically, and took the timing measurements below.
   **This directory's numbers are the phase-2 (Azure) measurements.**

## Mechanism

New, additive package: `src/main/java/com/latticejack/pqc/aiproxy/`.
Nothing in `EchoTlsServer.java`, `EchoTlsClient.java`,
`BenchmarkClient.java`/`BenchmarkServer.java` was touched.

- `PqcAiTlsServer.java` — one-shot TLS server, structured like
  `EchoTlsServer`: accepts one hybrid-PQC TLS connection (via
  `ProviderBootstrap.buildContext()`, the same context `./run after`
  uses), reads a prompt line, calls `LlamaServerClient` over loopback
  HTTP to the already-running `llama-server` process, writes one JSON
  reply line back over the same encrypted channel.
- `PqcAiTlsClient.java` — connects, times the handshake and the full
  request-to-response round trip separately, prints both alongside
  llama-server's own self-reported prompt-eval/generation timings.
- `LlamaServerClient.java` — `java.net.http.HttpClient` wrapper (already
  in the JDK, no new dependency) for llama-server's `/completion` and
  `/health` endpoints.
- `MiniJson.java` — dependency-free JSON reader/writer, same rationale as
  `Stats.java` elsewhere in this project (a simple, honest harness you
  control beats pulling in a library for this).
- `AiConfig.java` — system-property-driven config record, mirrors
  `TlsConfig.java`'s convention.

One pre-existing file was edited:
[`ProviderBootstrap.java`](../../src/main/java/com/latticejack/pqc/ProviderBootstrap.java) —
the class, `install()`, and `buildContext()` were widened from
package-private to `public` (documented in that file's Javadoc) so
`aiproxy` could reuse the exact same handshake setup `./run after` uses
instead of forking a second copy of it. `NAMED_GROUPS` itself did NOT stay
public: a follow-up audit found a public mutable array is a real
shared-state hazard (any code anywhere could silently downgrade the
key-exchange preference for every caller), so it went back to `private`
behind a `namedGroups()` accessor returning a defensive copy — updating
all six call sites project-wide, this package's two new classes included.
No handshake-negotiation logic changed either way. `./run-after.sh` and
`./run-nativekem.sh` were re-run after both edits and still pass — no
regression.

Driver: `run-ai.sh` (repo root), structured like `run-after.sh`/
`run-nativekem.sh`, reusing their HelloRetryRequest check verbatim to rule
out a silent classical fallback on this path too — the same check every
other "verified, not just ran" claim in this project relies on.

## KleidiAI verification evidence

This is the single most important thing to get right — a backend that
silently fails to engage while everything still "works" (wrong answers
never happen, only slow ones) is exactly this project's recurring failure
mode (`SSLContext.getDefault()`'s silent classical fallback, BC's silent
MLKEMSpi handling). So the check here is "what does the log actually
say," not "did it exit 0." Both build phases used the same method;
phase 2 (Azure) is the one whose numbers below are load-bearing.

**Build:** `cmake -B build -G Ninja -DGGML_CPU_KLEIDIAI=ON -DGGML_METAL=OFF
-DGGML_BLAS=OFF -DLLAMA_CURL=OFF -DCMAKE_BUILD_TYPE=Release` against
`ggml-org/llama.cpp` @ `de699957`. `GGML_CPU_KLEIDIAI=ON` triggers a CMake
`FetchContent` pull of ARM-software/kleidiai v1.24.0. On the Azure VM
this was a fresh native build, not a binary carried over from the Mac —
different CPU (Neoverse-N2 vs Apple M5), different toolchain (Ubuntu
24.04 gcc/clang vs macOS clang).

**Model:** `bartowski/Llama-3.2-1B-Instruct-GGUF`,
`Llama-3.2-1B-Instruct-Q4_0.gguf` (773,025,920 bytes; magic bytes/version
checked). Q4_0 specifically, not a K-quant, because KleidiAI's optimized
kernels are hardcoded (confirmed by reading
`ggml/src/ggml-cpu/kleidiai/kleidiai.cpp` in the checkout) to only cover
`GGML_TYPE_Q4_0`/`GGML_TYPE_Q8_0` — the much more commonly-distributed
`Q4_K_M`-style K-quant family is not covered and would silently fall back
to generic (unaccelerated) kernels for those tensors.

**Symbol-level confirmation (Azure VM, `libggml-cpu.so`):**
`nm build/bin/libggml-cpu.so | grep kleidi` shows
`ggml_kleidiai_select_kernels_q4_0`/`_q8_0` compiled in.

**Runtime confirmation (`--verbose` server log, Azure VM):**

```
system_info: ... KLEIDIAI = 1 | ...
kleidiai: primary q4 kernel feature I8MM
kleidiai: primary q8 kernel feature I8MM
load_tensors: CPU_KLEIDIAI model buffer size =   504.01 MiB
```

Two things worth calling out precisely, not glossing over:

- **The selected kernel feature differs from the Mac build**: `I8MM` on
  the Azure Neoverse-N2, vs `DOTPROD` on the Mac's Apple M5. This is a
  real, hardware-specific difference, not a discrepancy to explain away —
  both `GGML_MACHINE_SUPPORTS_dotprod` and `GGML_MACHINE_SUPPORTS_i8mm`
  independently showed `Success` in this VM's own `cmake` configure
  output, and KleidiAI's kernel selection logic picked I8MM as primary on
  this hardware. Confirmed on this machine's own build output, not
  assumed transferred from the Mac phase.
- **`CPU_KLEIDIAI model buffer size = 504.01 MiB`** — identical byte
  count to the Mac build, which is the useful cross-check: same model,
  same quantization, same set of tensors routed into the accelerated
  buffer type on both platforms. This is the strongest single signal
  (non-zero, plausible-for-this-model weight data actually placed in
  KleidiAI's buffer, not just "the backend loaded").

**The same expected fallback signature reproduced:** `token_embd.weight`
and 52 other tensors are `q6_K` (llama.cpp keeps embedding/output/norm
tensors at higher precision by convention even in a nominally "Q4_0"
file) and are correctly reported as NOT accelerated —
`kleidiai: no kernel for tensor type q6_K, not accelerated by KleidiAI
(kernels available for Q4_0 and Q8_0)`. This line, and only this line, is
what shows at llama-server's **default** verbosity — none of the positive
lines above (`KLEIDIAI = 1`, `primary q* kernel feature`, `CPU_KLEIDIAI
model buffer size`) print without `--verbose`. Anyone re-running this
without `--verbose` would see a single WARN line and no adjacent
confirmation that the other ~504MB of tensors *did* get accelerated —
this was checked explicitly (a separate default-verbosity run) on the Mac
phase and the finding carries over as a standing caution for anyone
reproducing this, not re-verified line-by-line again on Azure.

A direct `curl .../completion` request against `llama-server` was
confirmed to return correct model output before the TLS integration was
attempted on each phase, isolating "does the model work" from "does the
proxy work."

## Real measurements (Azure Cobalt 100, 3 runs)

All three runs `VERIFIED`: `run-ai.sh`'s own checks passed each time —
HelloRetryRequest observed (2 ClientHellos, consistent with
X25519MLKEM768 negotiating, not a silent fallback to a classical group),
and a real model reply received back through the encrypted channel.

| Run | Handshake | Request-to-response | of which prompt eval | of which generation | Ratio (handshake / total) |
|---|---|---|---|---|---|
| 1 | 330.6 ms | 3007.1 ms | 161.953 ms | 2801.876 ms | 0.110 (~9.1x smaller) |
| 2 | 340.8 ms | 3130.8 ms | 163.967 ms | 2914.883 ms | 0.109 (~9.2x smaller) |
| 3 | 310.8 ms | 3055.8 ms | 164.019 ms | 2842.476 ms | 0.102 (~9.8x smaller) |
| **avg** | **~327 ms** | **~3065 ms** | — | — | **~9.4x smaller** |

"Request-to-response" is `PqcAiTlsClient`'s wall-clock timer around the
full round trip (prompt sent over TLS → server proxies to llama-server →
reply returned over TLS); prompt-eval/generation are llama-server's own
self-reported sub-timings within that window, not independently measured.

**Finding:** on real target hardware, handshake cost is roughly an order
of magnitude smaller than the AI inference request it fronts — noise next
to the AI workload, the qualitative result the local Mac prototype
suggested (handshake 76–78 ms / request-response 853–1126 ms / ratio
11–14x across two single-shot runs) and this confirms on the actual
target silicon. The two platforms differ substantially in absolute terms
— Azure's numbers are roughly 4x slower on handshake and ~3x slower on
inference than the Mac's — which is expected (2 vCPU Neoverse-N2 vs a
much stronger Apple M5 core, plus Azure's context size deliberately
capped at 4096 tokens vs the Mac's uncapped default, see below) and not
something this data isolates the cause of. The qualitative shape (ratio
~9–14x either way) is the load-bearing part, not the absolute millisecond
values.

## Honest caveats on these numbers

- **3 runs, single-shot each, not a percentile harness.** Unlike
  `run-benchmark.sh`'s 200-iteration/20-warmup methodology
  (`benchmarks/README.md`), these are three individual timed requests —
  no warmup discarding, no p50/p95/p99, no statistical characterization
  of variance beyond eyeballing the spread across 3 runs (±10ms
  handshake, ±60ms response here). Treat the ratio's *order of magnitude*
  as the reliable finding, not the specific decimal values.
- **A real hardware-resource problem was hit and fixed, not avoided.**
  The first `run-ai.sh` attempt on this VM had `llama-server` OOM-killed
  mid-run (`journalctl -k`: `Out of memory: Killed process ... llama-server
  ... anon-rss:3352752kB`) — llama-server's default context size
  (`n_ctx=79872`, inherited from the model) alone consumed ~3.35GB RSS on
  a 3.8GB-RAM VM, leaving no headroom for the JVM handshake/build
  processes running concurrently. Fixed by restarting llama-server with
  `-c 4096`, cutting RSS to ~1.68GB. KleidiAI engagement was re-verified
  unchanged (504.01 MiB buffer) after the restart, before re-running the
  TLS integration, which then passed cleanly 3/3 times. This is a genuine
  finding about this target's resource envelope, not swept under the rug:
  **the Azure Cobalt 100 target's 3.8GB RAM is tight for LLM inference +
  JVM + build tooling running concurrently**, and the reduced context
  size is a real constraint on the measurement, not a free choice.
- **Update: the cosmetic label bug this section originally flagged is
  fixed.** `run-ai.sh`'s printed timing label (and `PqcAiTlsClient.java`'s
  matching string) used to read `LOCAL PROTOTYPE TIMING (Mac laptop, NOT
  the Azure Cobalt 100 headline number)` unconditionally - leftover text
  from the phase-1 Mac prototype that was still printing on the Azure VM
  run that produced the numbers above. Found by an independent audit that
  fact-checked this project's own demo-video script against the real
  tooling output. Both now read the actual platform from
  `os.name`/`os.arch` at runtime instead of asserting a specific machine.
  The numbers above were always real Azure Cobalt 100 measurements (the
  SSH session that produced them was independently confirmed `aarch64` via
  `uname -m`) - only the printed label was ever wrong, and it no longer
  is.

## What this does not establish

- **Concurrent load.** Every run above is one client, one connection,
  serialized — nothing here exercises `BenchmarkServer`'s thread-pool
  concurrent-handshake path or measures throughput under simultaneous
  inference requests. Whether the "handshake is noise" finding holds when
  the inference server itself is contended (multiple requests queued
  behind the same CPU-bound llama-server process) is untested.
- **Larger models.** Only a ~1.24B-parameter, Q4_0-quantized model was
  tested. Larger/production-scale models' KleidiAI tensor-type coverage,
  memory footprint, and generation latency on this 3.8GB-RAM target are
  unknown — and per the RAM caveat above, this exact VM size is already
  tight for a 1B model.
- **Production readiness.** No authentication beyond the mTLS handshake
  already in place, no rate limiting, no hardening of `aiproxy`'s error
  paths beyond the happy path exercised. Verification here is a manual
  run inspected by hand (same tier as `run-after.sh`/`run-nativekem.sh`'s
  shell-script checks elsewhere in this project), not an automated test
  suite for the new package.
- **A classical-TLS-fronted control run of the same AI workload.** The
  "handshake is noise" finding compares handshake cost to inference cost
  *within* the hybrid-PQC run itself; there is no side-by-side classical-
  vs-PQC timing of the identical AI workload here (Component B1/B2
  already established the classical-vs-PQC handshake delta on its own,
  separately, without an AI workload attached).
- **Combination with GraalVM native-image (B2 lever 4).** Untested
  together with this integration, same open item `nativekem-e2e-bench`
  already flagged for the native-KEM path.
- **Whether the RAM-driven context-size reduction (`-c 4096`) changed
  model output quality.** Not evaluated — the check performed was "does
  the model return a real, on-topic reply," not a quality/perplexity
  comparison against the uncapped-context Mac run.

## Reproducing

Phase 1 (local prototype, any machine): follow
[`component-ai/README.md`](../../component-ai/README.md) end to end.

Phase 2 (real hardware) differs only in: build natively on the target
VM rather than reusing a binary (`apt install cmake ninja-build` instead
of Homebrew), confirm `GGML_MACHINE_SUPPORTS_dotprod`/`_i8mm` in that
machine's own `cmake` configure output rather than assuming Neoverse-N2
support, and watch RAM headroom — cap llama-server's context
(`-c 4096` or lower) if running alongside a concurrent JVM build/handshake
on a memory-constrained instance, and re-check `journalctl -k` for OOM
kills if `run-ai.sh` fails without a clear Java-side error.
