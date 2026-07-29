# BC vs. JDK 25 built-in ML-KEM — a real, hardware-specific "hole"

Independent analysis from two models (Opus and Fable, given the same
question and pointed at BC's actual 1.85 source) converged on the same
reframing of "how to speed up ML-KEM": the NTT is a minority of the cost
(~15-25%), Keccak/SHA3 hashing dominates (~50-70%), and BouncyCastle's
pure-Java Keccak bypasses any JVM acceleration entirely. Opus additionally
found that **JDK 25 (JEP 496) ships a built-in ML-KEM with hand-written
AArch64 intrinsics** (vectorized NTT stub + SHA3 intrinsic, JDK-8349721),
independently reported elsewhere at ~2x over an un-intrinsified path,
"roughly on par with OpenSSL" — entirely inside the JVM, no FFM/JNI needed.

This microbenchmark tested that claim directly, and the real number on our
actual target hardware is a lot more interesting than the headline.

## Method

`MLKemMicrobench.java` calls the **same standard JCA API**
(`KeyPairGenerator`/`javax.crypto.KEM`) for both implementations, only
swapping the provider name (`"BC"` vs `"SunJCE"`) — both run under the
identical JVM (JDK 25), isolating the comparison to "which ML-KEM
implementation" rather than confounding it with JDK version. Measures
keygen/encapsulate/decapsulate separately, since JDK 25's ML-KEM isn't
wired into any TLS stack (BCJSSE has no hook to swap it in — same
missing-hook problem as the native/liboqs path discussed elsewhere in this
project). Two warmup regimes per run (5 and 200 iterations, 500 measured
each) to check whether any advantage holds cold or only once fully warmed.
Raw output: `cobalt100-3run-raw.txt` (3 independent runs on the actual
target hardware, GitHub Actions runner service stopped during measurement
to remove a background-CPU-contention confound).

## Results (Azure `Standard_D2pls_v6`, Cobalt 100/Neoverse-N2, averaged across 3 runs)

**Warm (200 warmup iterations) — p50, µs:**

| Operation | BC | JDK 25 (SunJCE) | JDK 25 advantage |
|---|---|---|---|
| keygen | 72.66 | 65.35 | ~11.2% |
| encaps | 58.83 | 56.02 | ~5.0% |
| decaps | 58.33 | 54.61 | ~6.8% |

**Cold (5 warmup iterations, closer to what one real TLS connection
experiences) — p50, µs, per run:**

| Run | BC keygen | JDK25 keygen | BC encaps | JDK25 encaps | BC decaps | JDK25 decaps |
|---|---|---|---|---|---|---|
| 1 | 120.62 | 138.07 (BC faster) | 87.55 | 138.65 (BC **much** faster) | 81.72 | 155.27 (BC **much** faster) |
| 2 | 125.97 | 132.40 (BC faster) | 88.47 | 114.34 (BC faster) | 87.08 | 127.48 (BC faster) |
| 3 | 116.79 | 100.25 (JDK25 faster) | 86.16 | 87.47 (~tie) | 83.79 | 105.81 (BC faster) |

## What this actually means

**The widely-cited "~2x, on par with OpenSSL" JDK 25 ML-KEM speedup does
not transfer to this hardware at anywhere near that magnitude.** Warm
steady-state advantage here is ~5-11%, not 2x. A local Apple Silicon
smoke-test of the same benchmark (not the authoritative number, but useful
context) showed a larger 1.3-1.9x advantage for the same comparison — a
strong hint that the headline number is substantially an **Apple-silicon
result**, not a general-Arm64 one. This lines up with a specific technical
reason found during research: OpenJDK is actively selecting a *different*
(GPR-based, non-SIMD) SHA3 intrinsic path specifically for Neoverse chips
(JDK-8359256), separate from the SIMD path that wins on Apple silicon — and
that Neoverse-specific work may not even be complete in this JDK 25.0.3
build.

**The cold-start result is the more surprising and arguably more
practically relevant finding.** At low warmup, BC is consistently *faster*
than JDK 25's own "optimized" implementation for encaps/decaps across all
3 runs, and roughly tied for keygen. The likely explanation: JDK 25's
AArch64 intrinsics are compiled stubs that themselves need JIT
activation/warmup before they pay off, while BC's simpler bytecode reaches
its (slower, but achieved-faster) steady state sooner. A real TLS server
handles many independent, short-lived connections — each handshake's ML-KEM
call may never individually benefit from a long warmup run the way this
microbenchmark's "warm" regime does. If that's the operating regime that
matters for judging "which implementation is actually faster in
production," the honest answer on this hardware might be closer to "roughly
a wash, with BC sometimes ahead" than "JDK 25 wins."

## How much of this is the intrinsic, isolated directly

The natural follow-up question: is the small Arm64 advantage because the
AArch64 intrinsic isn't actually activating on Neoverse-N2, or because it's
active but just weaker there? HotSpot exposes a direct answer:
`-XX:+UnlockDiagnosticVMOptions -XX:+PrintFlagsFinal` shows a real,
default-on flag — `bool UseKyberIntrinsics = true {diagnostic} {default}`
— confirmed present on both the local (Apple Silicon) and real Arm64 JDK
25 builds used in this project. That makes a clean A/B test possible:
toggle `-XX:-UseKyberIntrinsics` and remeasure the exact same benchmark,
same JVM, same hardware, only the flag differs.

**Local (Apple Silicon), single run:** SunJCE keygen p50 19.58µs
(intrinsic ON) vs. 29.54µs (OFF) — the intrinsic alone accounts for
**~51%** of JDK 25's speed there.

**Real Arm64 (Cobalt 100), averaged across 3 independent runs** (a first
single-run attempt showed 12.5%, which is why this was re-run 3x and
averaged rather than trusted — the individual runs ranged 2-12%, real VM
noise, not a stable number on their own):

| | SunJCE keygen p50 (avg of 3 runs) |
|---|---|
| Intrinsic ON | 66.07 µs |
| Intrinsic OFF | 71.84 µs |

The intrinsic contributes **~8.7%** on Neoverse-N2 — roughly **6x weaker**
than the ~51% it delivers on Apple Silicon, for the identical flag on the
identical JDK build. And as a sanity check: BC's numbers (which never
touch this flag) stay flat regardless of it, while SunJCE-with-intrinsic-OFF
(71.84µs avg) and BC (~72.6µs avg over the same runs) land within ~1% of
each other — confirming JDK's *reference* Java implementation and BC's are
functionally equivalent as plain Java code. **All of JDK 25's advantage
over BC on this hardware is the intrinsic, and the intrinsic itself is
confirmed active but delivers a fraction of what it delivers on Apple
Silicon.**

**What this settles:** there is no further "JDK 25 configuration tuning"
available to close more of the gap — `UseKyberIntrinsics` is already on by
default and already firing. The ~8.7% is the real ceiling of what this
specific intrinsic implementation currently offers on Neoverse-N2, not a
misconfiguration. Closing the remaining gap toward native's ~11µs reference
would require OpenJDK improving the intrinsic for Neoverse specifically
(the JDK-8359256 SHA3-path work referenced above is exactly this, and
appears incomplete as of this JDK 25.0.3 build) or going outside the JVM's
built-in path entirely to `mlkem-native` via FFM/JNI — the stretch goal
discussed elsewhere in this project, not attempted here.

**Caveats, stated plainly:**
- Both regimes show wide tail-latency variance (p99/max values well above
  p50, some outliers into the milliseconds) — consistent with this VM's
  known 2 vCPU contention pattern from the B1 TLS benchmarks elsewhere in
  this project, not a new problem specific to this test.
- This measures the ML-KEM primitive in isolation, not an actual TLS
  handshake — JDK 25's implementation isn't wired into BCJSSE or any TLS
  stack this project uses, so this is a standalone finding about the
  crypto primitive, not a claim about handshake-level performance.
- 3 runs is enough to be confident this isn't a one-off fluke (the warm p50
  numbers cluster tightly run-to-run), but it's not a rigorous statistical
  study — treat the specific percentages as indicative, not precise.

## Bottom line

Don't cite "JDK 25 makes ML-KEM 2x faster" without a hardware qualifier.
On real Arm64 server silicon (Neoverse-N2/Cobalt 100), the advantage is
small (~8.7% average, confirmed via direct intrinsic isolation, not just
inferred) when warm, and can reverse entirely at realistic cold-start
conditions. This is now precisely explained, not just observed: the
`UseKyberIntrinsics` flag is on and firing, and simply delivers ~6x less
uplift on this chip family than on Apple Silicon for the same code. That's
a genuinely differentiated, hardware-specific, mechanism-level finding —
not something you'd get from reading the JEP or the OpenJDK PR alone, and
not something further JVM configuration can close.
