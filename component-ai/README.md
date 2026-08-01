# Component D prototype: KleidiAI-accelerated LLM inference behind hybrid-PQC TLS

**LOCAL PROTOTYPE ONLY, built and measured on a Mac (Apple Silicon, arm64),
not the project's real-hardware target.** Everything else in this repo's
`benchmarks/` is measured on real Azure Cobalt 100 (Neoverse-N2, Linux
aarch64) — this directory is *not* that. It exists to work out the
integration mechanics (build flags, model format, TLS-proxy plumbing,
KleidiAI verification method) cheaply before repeating this on the real
target, per arm-hackathon-plan.md and the two independent audits (Opus,
Fable) that flagged the missing real AI workload as this project's biggest
risk against the hackathon's "AI solution on Arm" gate.

This directory (`component-ai/`) holds build tooling and a downloaded model
— not committed source, not part of the Maven build. The Java integration
code that matters is in
`src/main/java/com/latticejack/pqc/aiproxy/`, driven by `../run-ai.sh`.

## 1. Building llama.cpp with the KleidiAI CPU backend

```bash
cd component-ai
git clone --depth 1 https://github.com/ggml-org/llama.cpp.git
cd llama.cpp

# Built at commit de699957b92f490efebad149665b0dccf127eaff (2026-08-01).
# The flag is GGML_CPU_KLEIDIAI (confirmed by reading
# ggml/CMakeLists.txt:153 and ggml/src/ggml-cpu/CMakeLists.txt directly in
# this checkout, not assumed from memory — build flags drift).
cmake -B build -G Ninja \
  -DGGML_CPU_KLEIDIAI=ON \
  -DGGML_METAL=OFF \
  -DGGML_BLAS=OFF \
  -DLLAMA_CURL=OFF \
  -DCMAKE_BUILD_TYPE=Release

cmake --build build -j <nproc> --target llama-server llama-cli
```

`GGML_CPU_KLEIDIAI=ON` triggers a CMake `FetchContent` download of
ARM-software/kleidiai **v1.24.0** source
(`ggml/src/ggml-cpu/CMakeLists.txt` lines ~586-608) into
`build/_deps/kleidiai_download-src/` at configure time — confirmed present
after `cmake -B build` ran. No manual KleidiAI checkout needed.

Prerequisites installed via Homebrew for this build: `cmake`, `ninja`
(neither was present on this Mac beforehand). `GGML_METAL=OFF`/
`GGML_BLAS=OFF` disable Mac-specific backends that would otherwise compete
with/mask the CPU+KleidiAI path being the thing actually exercised —
**the real Azure Cobalt 100 target has neither Metal nor Accelerate, so
this flag combination is closer to what that target's build will look
like anyway.** `LLAMA_CURL=OFF` avoids an unrelated libcurl dependency
requirement for the model-download-by-URL feature this project doesn't use
(models are fetched by this project's own `curl`, verified, then pointed
at explicitly).

Build machine: Apple M5, macOS 26.5.2, arm64. Confirmed CPU feature
support before building: `sysctl hw.optional.arm.FEAT_DotProd` and
`hw.optional.arm.FEAT_I8MM` both `1`. cmake's own configure output
independently confirmed the same
(`GGML_MACHINE_SUPPORTS_dotprod - Success`,
`GGML_MACHINE_SUPPORTS_i8mm - Success`).

**Linux/aarch64 (Azure Cobalt 100/Neoverse-N2) differences to expect next
phase:** OpenMP was NOT found on this Mac (`Could NOT find OpenMP` in the
cmake output) — on Linux, `apt install libomp-dev` (or equivalent) will
likely be available and should probably be enabled for real multi-thread
performance; this Mac build ran CPU-only threading via llama.cpp's own
thread pool instead (`-t 4`), which was enough to prove the mechanism but
not tuned for throughput. Verify Neoverse-N2's DOTPROD/I8MM support the
same explicit way (`cmake`'s own `GGML_MACHINE_SUPPORTS_*` test output, not
assumption) — N2 should have both, but confirm from that build's own
output, not this document.

## 2. Model: explicitly Q4_0, verified before trusting it

**Why Q4_0 specifically, not "Q4" generically:** KleidiAI's optimized
kernels only cover `GGML_TYPE_Q4_0` and `GGML_TYPE_Q8_0` — confirmed by
reading `ggml/src/ggml-cpu/kleidiai/kleidiai.cpp` in this checkout (the
type checks are hardcoded, e.g. lines 373-375, 551-552, 974, 1337, 1430).
The much more commonly-distributed K-quant family (`Q4_K_M` etc.) is NOT
covered and silently falls back to generic ggml kernels for those tensors.

Model: **`bartowski/Llama-3.2-1B-Instruct-GGUF`**, file
**`Llama-3.2-1B-Instruct-Q4_0.gguf`** (773,025,920 bytes), downloaded from
`https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_0.gguf`.
bartowski is a well-known, widely-used GGUF quantizer whose files are
routinely referenced by the llama.cpp project itself. The repo's file
listing was checked via the HF API
(`GET /api/models/bartowski/Llama-3.2-1B-Instruct-GGUF`) before downloading
— it explicitly offers separate `-Q4_0.gguf`, `-Q4_K_M.gguf`, `-Q4_K_S.gguf`,
`-Q4_K_L.gguf`, `-Q8_0.gguf` files, so the filename genuinely disambiguates
Q4_0 from the K-quant family (not a generic "4-bit" label that could be
either). File verified as a real GGUF after download: magic bytes `47 47
55 46` (`GGUF`), version 3 (`xxd -l 32`).

~1.24B parameters, ~773MB on disk as Q4_0 — well within the eventual Azure
target's 4GB RAM budget alongside a JVM.

## 3. Positive confirmation that KleidiAI actually engaged (not silently bypassed)

Ran `llama-server` with `--verbose` once to capture full kernel-selection
logging, then again at **default verbosity** specifically to check what's
visible without asking for it (the failure mode this project has been
burned by twice before — SSLContext classical fallback, BC MLKEMSpi silent
handling — was always a *quiet* success path, not a hard error, so the
check has to be "what does the log actually say," not "did it exit 0").

**Positive engagement evidence (`--verbose` run, exact log lines):**

```
0.00.002.823 I cmn  common_param: system_info: ... KLEIDIAI = 1 | ...
0.00.133.933 I kleidiai: primary q4 kernel feature DOTPROD
0.00.133.933 I kleidiai: primary q8 kernel feature I8MM
0.00.516.771 I load_tensors: CPU_KLEIDIAI model buffer size =   504.01 MiB
```

`KLEIDIAI = 1` in `system_info` confirms the backend was compiled in and
detected at runtime (this line alone is necessary but NOT sufficient —
see below). `primary q4 kernel feature DOTPROD` confirms a real
NEON-dotprod-accelerated kernel was selected for Q4_0 tensors specifically.
**The `CPU_KLEIDIAI model buffer size = 504.01 MiB` line is the strongest
evidence**: it's a non-zero, plausible-for-this-model byte count of
tensors that were actually placed into KleidiAI's accelerated buffer type
— not just "the backend loaded," but "the backend is holding real model
weight data." (Independently confirmed at the symbol level too:
`nm build/bin/libggml-cpu.dylib | grep -i kleidi` shows
`ggml_kleidiai_select_kernels_q4_0`/`_q8_0` compiled in.)

**Real inference request, confirming the loaded weights actually run**
(`curl http://127.0.0.1:8090/completion` with `{"prompt":"The capital of
France is","n_predict":16,"temperature":0}`):

```json
{"content": " Paris. The capital of Germany is Berlin. The capital of Italy is Rome.",
 "timings": {"prompt_n": 6, "prompt_ms": 134.522, "prompt_per_second": 44.6,
             "predicted_n": 16, "predicted_ms": 164.659, "predicted_per_second": 97.17}}
```

**What the silent-fallback failure mode actually looks like here (found,
not hypothesized) — this is the exact thing the next phase must check
for:** even in this genuinely-Q4_0 file, a handful of tensors
(`token_embd.weight` and 52 others — llama.cpp's own quantizer keeps
embedding/output/norm tensors at higher precision by convention, even in a
nominally "Q4_0" quant) are `q6_K`, which KleidiAI does NOT support:

```
0.00.129.806 D done_getting_tensors: tensor 'token_embd.weight' (q6_K) (and 52 others)
  cannot be used with preferred buffer type CPU_KLEIDIAI, using CPU instead
0.00.737.279 W kleidiai: no kernel for tensor type q6_K, not accelerated by KleidiAI
  (kernels available for Q4_0 and Q8_0)
```

**Critically: this WARN line is the ONLY KleidiAI-related output visible at
llama-server's DEFAULT verbosity** (re-ran with no `--verbose` flag,
verbosity defaulted to 3 — confirmed via a second, separate run whose full
11-line output is reproduced below). None of the positive-confirmation
lines above (`KLEIDIAI = 1`, `primary q4 kernel feature`, `CPU_KLEIDIAI
model buffer size`) appear without `--verbose` (or `-lv N` for higher N).
So at default verbosity, all you see is:

```
0.00.384.781 W kleidiai: no kernel for tensor type q6_K, not accelerated by KleidiAI (kernels available for Q4_0 and Q8_0)
```

— a single WARN line, easy to read as "one tensor type doesn't matter" and
move on, with NO adjacent line confirming the other ~504MB of tensors DID
get accelerated. **Lesson for the real-hardware phase: always run with
`--verbose` (or check the `system_info`/`CPU_KLEIDIAI model buffer size`
lines specifically) to get the positive confirmation — the default log
output alone cannot distinguish "everything using Q4_0/Q8_0 tensors got
accelerated, only the expected few outlier tensors didn't" from "KleidiAI
silently accelerated nothing."** If `CPU_KLEIDIAI model buffer size` ever
reads `0.00 MiB` in the FINAL load (not the interim first pass, which
always reads 0 — see the two-pass `load_tensors` lines in the full log),
or if `KLEIDIAI = 1` is absent from `system_info` entirely (e.g. built
without `-DGGML_CPU_KLEIDIAI=ON`, or the CPU lacks DOTPROD/I8MM), that is
the true silent-total-bypass case to watch for — not observed here, but
exactly the check this project's own `run-nativekem.sh`
positive-trace-marker pattern (see below) already models.

## 4. TLS-fronting integration

New, additive package: `src/main/java/com/latticejack/pqc/aiproxy/`.
Nothing in `EchoTlsServer.java`, `EchoTlsClient.java`,
`BenchmarkClient.java`/`BenchmarkServer.java` was touched. One
visibility-only edit was made to `ProviderBootstrap.java` — `class
ProviderBootstrap`, `NAMED_GROUPS`, `install()`, and `buildContext()`
widened from package-private to `public` so the new sibling package can
reuse the exact same hybrid-PQC handshake setup instead of forking a
second copy of it. No method body, field value, or call site in that file
changed. Re-verified `run-after.sh` and `run-nativekem.sh` both still pass
after this change (both do — see their output).

New files:

- `MiniJson.java` — dependency-free JSON reader/writer (same
  "simple, honest harness" ethos as `Stats.java`), used both to build the
  llama-server request body and to parse its `/completion` response.
- `LlamaServerClient.java` — plain-HTTP client (`java.net.http.HttpClient`,
  already in the JDK, no new dependency) for llama-server's `/completion`
  and `/health` endpoints.
- `AiConfig.java` — system-property-driven config record, same convention
  as `TlsConfig.java`, kept separate rather than adding fields there.
- `PqcAiTlsServer.java` — one-shot TLS server (mirrors `EchoTlsServer`'s
  structure closely): accepts one hybrid-PQC TLS connection, reads a
  prompt line, calls `LlamaServerClient` over loopback HTTP, writes one
  JSON reply line back over the same encrypted channel.
- `PqcAiTlsClient.java` — counterpart: connects, times the handshake and
  the full request-to-response round trip separately, prints both plus
  llama-server's own self-reported prompt/generation timings.

Driver script: `../run-ai.sh` (mirrors `run-after.sh`/`run-nativekem.sh`'s
structure exactly, including reusing their HelloRetryRequest check
verbatim to rule out a silent classical fallback on this new path too).

## 5. End-to-end verification (real run, this Mac)

```
$ ./run-ai.sh
...
[ai-pqc-kex] ai-client: handshake complete protocol=TLSv1.3 cipherSuite=TLS_CHACHA20_POLY1305_SHA256
[ai-pqc-kex] ai-client: sending prompt "In one short sentence, what is a lattice in cryptography?"
[ai-pqc-kex] ai-client: model replied " A lattice is a mathematical concept used in cryptography..."

[ai-pqc-kex] ai-client: LOCAL PROTOTYPE TIMING (Mac laptop, NOT the Azure Cobalt 100 headline number):
[ai-pqc-kex]   handshake time        = 75.9 ms
[ai-pqc-kex]   request-to-response   = 853.0 ms  (includes llama-server prompt eval 135.859ms + generation 703.154ms + loopback HTTP + JSON overhead)
[ai-pqc-kex]   handshake / inference ratio = 0.089  (i.e. handshake cost is 11.2x smaller than the AI workload it fronts)

VERIFIED: client received a real model reply through the encrypted channel.

VERIFIED: HelloRetryRequest observed (2 ClientHellos) - consistent
with X25519MLKEM768 (the first-preference group) being negotiated, not a
silent fallback to secp256r1.
```

A second run produced 78.1ms handshake / 1126.1ms request-to-response
(ratio 0.069, 14.4x) — consistent shape across runs: handshake cost is
roughly an order of magnitude smaller than one real inference request,
even for a ~1B model doing ~64 generated tokens on a laptop CPU.

**These numbers are local-machine (Apple M5, one un-warmed run each, no
concurrency, no dedicated benchmark harness) — they are a rough proxy for
the expected *shape* of the finding (handshake cost is noise next to real
AI workload latency), not a number to cite anywhere in WRITEUP.md.** The
real measurement is a later phase's job, on the actual Azure Cobalt
100/Neoverse-N2 target, ideally reusing `BenchmarkClient`/`BenchmarkServer`'s
percentile methodology rather than this prototype's single-shot timing.

## 6. Reproducing on Linux/aarch64 (for the next phase)

1. `apt install cmake ninja-build` (or equivalent) — Homebrew isn't
   available; use the distro's cmake/ninja instead of this doc's
   Homebrew-flavored setup.
2. Same `cmake -B build -G Ninja -DGGML_CPU_KLEIDIAI=ON ...` invocation
   should work unchanged — `GGML_CPU_KLEIDIAI` is a portable ggml CMake
   option, not Mac-specific. Consider enabling OpenMP this time (absent on
   this Mac build) if available, and re-run the same
   `GGML_MACHINE_SUPPORTS_dotprod`/`_i8mm` checks in the cmake configure
   output to confirm Neoverse-N2 support before trusting the build.
3. Same model file/URL — no change needed, same Q4_0 GGUF.
4. Same verification method: run `llama-server --verbose` once, grep for
   `KLEIDIAI = 1`, `primary q4 kernel feature`, and a non-zero
   `CPU_KLEIDIAI model buffer size` in the FINAL `load_tensors` line (not
   the interim 0.00 MiB pass) — do not trust default-verbosity output
   alone, per §3 above.
5. Same `run-ai.sh` — no code changes anticipated; only the `LATTICEJACK_LLAMA_URL`
   /model path and JDK 21 discovery in `scripts/require-jdk21.sh` are
   environment-specific, both already handled generically.
6. Replace the single-shot timing in `PqcAiTlsClient` with a real
   `BenchmarkClient`-style percentile run for the number that actually
   belongs in WRITEUP.md.
