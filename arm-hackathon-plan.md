# Arm AI Optimization Challenge — Submission Plan
## Track 2 (Migration/Adoption): PQC Migration for Java on Arm64

**Deadline:** 15 Aug 2026, 07:00 GMT+8 (~19 days out)
**Prize target:** Best in Track (Cloud AI, $1,000) as the realistic goal; Overall ($3,000) as the stretch.
**Why this project:** It puts the judged technical core (Java security) inside your genuine expertise, competes in a less-crowded track, gives a legitimate Arm64 story via the JVM on Graviton/Ampere, and reuses your existing authoring-skill + CBOM work as migration artifacts.

---

## 1. The one-sentence pitch

A migration toolkit that moves an existing Java TLS/mTLS service from classical crypto (RSA/ECDSA/ECDH) to post-quantum (hybrid ML-KEM/ML-DSA) **and then optimizes that PQC path specifically for Arm64 (AWS Graviton / Ampere)** — with a measured optimization delta, an authoring-time guardrail skill, and a CBOM crosscheck, so a Java team gets both a repeatable migration and the Arm-tuned configuration to run it efficiently in production.

Two claims, both honest and both load-bearing:
1. **Migration (Track 2 spirit):** PQC migration in Java is undocumented and risky; here is a repeatable path.
2. **Optimization (the 40-pt criterion):** the migrated PQC path, run on Arm64, is *tuned* for Arm — not merely deployed there — and the improvement is measured as a before/after delta on the same Arm hardware.

The framing to avoid: **not** "PQC is slow and I made it fast" (false for compute on Arm, and experts will catch it). The optimization claim is narrower and true: "here is the Arm64-tuned configuration of a PQC-migrated Java service, and here is the measured improvement over the naive migrated config." You optimize the *deployment*, not the algorithm's math.

---

## 2. Why this clears Stage One (the pass/fail gate)

Stage One checks: reasonably fits the theme + reasonably applies the required platform/tools (Arm-powered, efficiency-minded design). This project passes because:

- It runs on and is benchmarked on **Arm64** (Graviton/Ampere) — Cloud AI track explicitly names Arm64 cloud.
- It demonstrates **efficiency-minded design**: measured latency/throughput/cost-per-handshake on Arm, with tuning of JVM and provider choices for Arm64.
- It produces **migration templates + an Arm64-optimized configuration with a measured delta**, which the rubric names directly as valued artifacts.
- The optimization work is a *core technical act*, not a deployment detail — which is what the "AI Optimization Challenge" theme and the 40-pt criterion actually reward.

**Stage One risk to neutralize:** a pure-crypto/migration tool with Arm mentioned in passing could fail the gate. Mitigation: the Arm64 optimization-with-delta is the headline result, built from week 1, not an appendix. If a judge asks "what did you optimize *for Arm*," the answer is a concrete tuned config and a number.

---

## 3. Scope — three components, baseline-first

Sequenced so you always have something submittable. Each stage is a fallback if later stages run out of time.

### Component A — The migration reference (the spine; must-have)
A working Java service (Spring Boot or plain JSSE) that does a TLS 1.3 / mTLS handshake, provided in two configurations:
- **Before:** classical (X25519 + ECDSA, or RSA).
- **After:** hybrid PQC (X25519MLKEM768 key exchange + ML-DSA authentication) via BouncyCastle (BCJSSE provider) and/or the JDK PQC provider where available.

Deliverable: a repo where `./run before` and `./run after` both stand up a working handshake, plus a documented, step-by-step migration path between them. This alone is a valid Track 2 submission.

### Component B — The Arm64 optimization (THE CENTERPIECE; attacks the 40-pt criterion)
This is the component that makes it an *Arm optimization* project rather than a *Java migration that happens to run on Arm*. It has two layers:

**B1 — Characterization (the baseline measurement).** Run A on Arm64 and record, before (classical) vs after (PQC-migrated, naive config):
- Handshake latency (p50, p95, p99 — the tail matters for ML-DSA rejection sampling).
- Throughput (handshakes/sec).
- Bytes-on-wire per handshake (where PQC's real cost shows).
- CPU cost and, if capturable, cost-per-million-handshakes in cloud $ terms.

**B2 — Optimization (the delta that earns the 40 points).** Take the naive PQC-migrated config and *tune it specifically for Arm64*, then measure the improvement over the naive config on the same hardware. Candidate optimization levers, in your skill range (JVM/config/provider, not assembly):
- **JVM tuning for Graviton:** GC choice and heap sizing for handshake-heavy workloads, JIT warmup handling, tiered compilation flags; Graviton3/4 have specific cache and SVE/NEON vector characteristics worth exploiting via up-to-date JVM builds.
- **Provider/JCA configuration:** provider ordering, session resumption / TLS session cache tuning to amortize the expensive PQC handshake, cipher-suite and group prioritization, keystore/entropy source config.
- **Native acceleration path (the one permitted C/Rust component — see §3a):** route the JVM's PQC operations through an Arm64-optimized native backend — **liboqs (C, with Arm64 assembly) via a JNI shim**, or a small Rust cdylib as an alternative — and measure the native route vs pure-Java. This is the highest-value lever if reachable — it directly ties the improvement to Arm64 crypto kernels — but verify feasibility by day 8 (§3a gate). Treat as B2-stretch; fall back to pure JVM/config levers if not cleanly reachable.
- **Connection-level:** thread-pool sizing and native-image (GraalVM) vs HotSpot on Arm64, if time allows.

Deliverable: a **before/naive/tuned** results table on Arm64 with a clear optimization delta (e.g., "handshake p99 reduced N%, throughput up M% via Arm64-tuned config X"), plus an x86-vs-Arm64 cross-reference showing the Arm efficiency angle. Every tuning choice documented with its measured effect. Use Arm Performix for the Arm benchmarking numbers.

**Why this closes the gap:** the 40-pt criterion rewards "clearly leverage Arm-powered platforms... efficiency-minded design." A measured optimization delta from Arm-specific tuning *is* that leverage, in a register (JVM/config) you can execute — unlike the assembly-level kernel work you correctly ruled out. Pick 1–2 levers you can land solidly rather than attempting all of them thinly.

### Component C — The authoring guardrail + CBOM crosscheck (the reuse + adoption value; differentiator)
- The `pqc-authoring` skill (already drafted) repurposed as the *prevention* half: it stops a developer from writing new RSA/ECDSA handshake code while migrating.
- A CBOM (CycloneDX) emitted from the codebase before and after, so the migration is auditable: the "after" CBOM shows quantum-safe assets where the "before" showed vulnerable ones. Optionally reconcile against a scanner (CryptoScan/CBOMkit) to show the authoring inventory matches the CI scan.
- **Optional AI hook (only if time allows):** a small on-device/Arm-hosted model that triages scanner findings ("is this RSA in a live handshake or a test fixture?"). This is where the AI-optimization angle enters honestly — but it is a stretch goal, not load-bearing. Do not let it jeopardize A and B.

---

## 3a. Language strategy (decided — do not re-litigate mid-build)

**Everything is Java, with exactly one permitted exception: a single native crypto-acceleration backend in C or Rust, gated behind a day-8 feasibility check.**

Rationale, so the decision stays settled:
- The project's edge is the *Java* migration story for legacy financial/enterprise codebases. Those shops cannot adopt a Rust/C service — that would be a rewrite they will never fund. The value is that the path works *inside the JVM they're stuck with*. Rewriting core components in another language destroys the Track 2 migration/adoption thesis (a Rust service is not a migration path for a Java shop).
- "Java is slow" mostly dissolves for this workload: a TLS handshake is dominated by the crypto primitive, and the JVM tax is JIT warmup + GC — which is exactly what the B2 JVM/config levers address. The slowness is tunable *without leaving Java*, and that tuning is the 40-pt centerpiece. A rewrite would rob B2 of its purpose.
- On pure scoring: a JVM-native optimization delta hits *both* "leverages Arm / efficiency-minded design" (40-pt) *and* "migration/adoption value" (Track 2). A language rewrite hits the first but loses the second.

**The one permitted native component (B2-stretch only):**
- **What:** an Arm64-optimized PQC crypto backend — the concrete shape is **liboqs (C, with Arm64 assembly for ML-KEM/ML-DSA)** called from the JVM via a thin JNI shim. Rust is an acceptable alternative for the shim/wrapper if you prefer it (e.g. a small Rust cdylib over liboqs, or the RustCrypto `ml-kem`/`ml-dsa` crates), but C+liboqs is the lower-risk path because liboqs already ships the Arm64 assembly you want the credit for.
- **Why it's allowed:** it does **not replace** the Java service — it's the accelerated *backend the Java path calls*. The deliverable is still "how a Java shop gets fast PQC on Arm64." This keeps the migration narrative intact while giving a real Arm64-kernel optimization delta (the highest-value version of B2).
- **The gate:** confirm feasibility by **day 8**. JNI boundary work and build integration are their own time sinks and edge toward the low-level territory outside your core strength. If it's not cleanly reachable, **fall back to pure JVM/config levers** (session resumption, GC/heap, warmup) — which still produce a real delta. Do not let this lever block the achievable ones, and do not let it quietly expand into a rewrite.

**Everything else stays Java (or language-agnostic):** Component A (migration reference) and B1 (characterization) are pure Java by definition. Component C is instructions + JSON (CBOM), language-agnostic. No other component is a candidate for C/Rust.

---

## 4. Day-by-day plan (19 days)

**Week 1 — Baseline that already runs (de-risk everything)**
- Days 1–2: Stand up the classical Java TLS/mTLS service. Get it building and handshaking locally. Pick Spring Boot or raw JSSE (raw JSSE is less magic, easier to benchmark cleanly).
- Days 3–4: Provision an Arm64 instance (Graviton on AWS, or Ampere on Oracle/Azure/GCP). Get the classical service running on it and benchmarked. **Milestone: a measured baseline on Arm — this is already submittable.**
- Days 5–7: Bring in BouncyCastle BCJSSE; get the hybrid X25519MLKEM768 + ML-DSA handshake working in the "after" config. Confirm interop and correctness (test vectors / actual handshake success).

**Week 2 — The Arm64 optimization (the core result, the 40-pt work)**
- Days 8–9: B1 characterization — full before/naive benchmark on Arm64. Latency percentiles, throughput, bytes-on-wire, CPU. Get the ML-DSA tail-latency story. **Milestone: the baseline table.**
- Days 10–12: B2 optimization — pick 1–2 Arm64 tuning levers (start with JVM/GC + session-resumption config; attempt the native-acceleration lever only if feasibility confirmed) and iterate to a measured delta over the naive config. **Milestone: a documented optimization delta on Arm64 — this is what earns the 40 points.**
- Days 13–14: x86-vs-Arm64 cross-reference + migration templates. Turn the working path and the tuned config into reusable artifacts: config snippets, a migration checklist, the tuning rationale, and the "gotchas" a Java team hits (provider ordering, key sizes, MTU/handshake-size surprises).

**Week 3 — Package, differentiate, submit**
- Days 15–16: Component C — wire in the authoring skill + CBOM before/after. If (and only if) A and B are solid, attempt the triage-model AI hook.
- Day 17: README + write-up (see §6). License file (Apache-2.0) visible in the About section.
- Day 18: Record the <3-min demo video (see §7).
- Day 19: Final submission, buffer for the inevitable last-minute breakage. **Submit the draft early on Devpost and keep updating** — a submitted draft beats a perfect thing that missed the deadline.

**Fallback ladder (if you fall behind):** A + B1 (migration + characterization) = valid, honest submission. A + B1 + B2 (measured Arm64 optimization delta) = competitive submission that earns the 40-pt criterion. + Component C (guardrail/CBOM) = strong. + AI hook = stretch for Overall. **The one thing you must protect is B2 — the optimization delta — because it's what converts this from a migration project into an Arm-optimization project. If forced to choose, drop the AI hook and Component C before dropping B2.**

---

## 5. Line-by-line map to the judging rubric

### Technological Implementation — 40 pts (and the tie-breaker) — NOW DIRECTLY ATTACKED
- Working before/after handshake in Java = quality software development.
- **A measured Arm64 optimization delta (B2) = "clearly leverages Arm-powered platforms... efficiency-minded design."** This is the phrase the criterion hinges on, and B2 answers it with a concrete tuned config + a number, not just "it runs on Graviton."
- Correct hybrid PQC via a real provider, with test-verified handshakes = sound, well-executed.
- **This is your strength (Java security + JVM/config tuning) — lean in. Tie-breaks resolve on this criterion first, so B2 is the deepest, most-protected part of the build.**
- **Gap-closure note:** without B2 this criterion was only a partial fit (Arm-as-venue). With B2 it becomes a genuine fit (Arm-as-optimization-target) in a register you can execute. That single change is the highest-leverage move in the whole plan.

### "WOW" factor — 25 pts
- The WOW is the *optimization delta*: "PQC-migrated Java on Graviton, then tuned for Arm64 to recover N% of the handshake cost." That's a before/naive/tuned story with a payoff, more compelling than a flat measurement.
- Plus the honest cost characterization (bytes-on-wire, ML-DSA tail latency) most teams have never seen — stands out against hype.
- Optional AI triage hook adds novelty *if* it lands.

### Potential Impact — 20 pts
- Migration templates + checklist = exactly the "migration templates" the rubric names.
- Java + Arm64 (Graviton/Ampere) is a huge, growing production surface — high reuse value.
- CBOM crosscheck gives teams an auditable migration record.

### UX / Developer Experience — 15 pts
- Clean `run before` / `run after` + clear setup instructions that validate on Arm64.
- Well-structured docs and a migration checklist a developer can actually follow.
- "Could this be taken further or reused" — yes, it's a template other Java teams apply.

---

## 6. Repo + write-up requirements (from the rules)

**Repo must:**
- Be public, with an **Apache-2.0** (or MIT) LICENSE file *visible in the About section* — the rules specifically say detectable at the top of the repo page.
- Contain all source, assets, and setup instructions to build/run/validate.
- Show significant work during the submission window. Since your skill/CBOM work predates the hackathon, **explain in the write-up what was newly built during the period** (the migration reference, the Arm64 benchmark, the integration) — the rules require this for pre-existing components.
- Build on open source (BouncyCastle, liboqs, PQClean, CryptoScan) *and add enhancing work on top* — which the IP clause explicitly permits.

**Write-up must cover (their exact headers):**
- **Project Overview:** what it is, why it's interesting, why it should win. Lead with the migration-gap + real-Arm-data hook.
- **Functionality / Output:** the before/after service, the benchmark results, the migration templates, the CBOM.
- **Setup Instructions:** step-by-step to build/run/validate **on Arm64** — name the instance type, the provider setup, the commands. Judges may test on Arm, so this must actually work.

---

## 7. The demo video (<3 min, optional but high-leverage)

Judges aren't required to watch past 3 min, so front-load. Suggested arc:
- 0:00–0:20 — the problem: Java teams must migrate off RSA/ECDSA, no clean path, no real cost data.
- 0:20–1:10 — the migration: show `run before` (classical handshake) then `run after` (hybrid PQC handshake) succeeding, on Arm64.
- 1:10–2:10 — the data: the before/after benchmark table/charts on Arm64. Call out the honest cost story (bytes-on-wire, tail latency) and the Arm efficiency angle.
- 2:10–2:50 — the reusable artifacts: migration template, authoring guardrail, CBOM before/after.
- Must show the project actually running on the target device; no copyrighted music.

---

## 8. Key technical decisions to lock early

- **Provider:** BouncyCastle BCJSSE is the safest bet for hybrid PQC in Java today (ahead of the JDK). Confirm the current BC version supports X25519MLKEM768 + ML-DSA in JSSE at project start — verify against current docs, don't trust a stale version number.
- **Arm64 instance:** AWS Graviton (c7g/c8g) is the most recognizable "Arm64 cloud" and pairs cleanly with the Cloud AI track. Ampere (Oracle free tier / Azure / GCP) is a cheaper alternative. Have the instance before week 2.
- **Benchmark tool:** a simple, honest harness you control beats a heavyweight framework. Measure percentiles, not just averages — the ML-DSA tail is part of the story. Use Arm Performix for the Arm-side numbers the challenge expects.
- **Correctness first:** a handshake that *looks* PQC but silently falls back to classical is a disqualifier-level embarrassment. Verify the negotiated group/signature algorithm explicitly in your tests.

---

## 9. Honest risk register

- **Stage One / "not Arm enough":** mitigated by making the Arm64 benchmark a headline, built in week 1, not an appendix.
- **BouncyCastle API drift:** the exact JSSE names for hybrid groups move between versions. Verify at project start; budget a day for provider-config debugging (provider ordering is a classic Java footgun).
- **B2 optimization yields no delta:** the real risk of the centerpiece — you tune and nothing meaningfully improves. Mitigations: (1) start with levers known to matter for handshake-heavy JVM workloads (session resumption, GC/heap) which reliably move throughput; (2) session-cache tuning almost always helps because it amortizes the expensive PQC handshake — that's a near-guaranteed delta; (3) if a lever shows no effect, that's still a *reportable finding* ("Arm64 tuning X did/didn't help, here's why"), which the write-up can frame as honest engineering rather than a null result. You will have *a* delta from session resumption even in the worst case.
- **Native-acceleration lever may be unreachable:** wiring liboqs (C, Arm64 assembly) or a Rust cdylib under the JSSE path via JNI may not be feasible in-scope. This is the *only* permitted non-Java component (§3a). Verify by day 8; if not reachable, fall back to pure-JVM/config levers. Don't let this stretch lever block the achievable ones — and don't let it creep into a rewrite of the Java core.
- **Scope creep via the AI hook:** the triage model is the most "on-theme AI" piece but also the riskiest and least in your wheelhouse. It is explicitly a stretch goal. Protect A and B2.
- **Crowded WOW:** many entries will show quantized models. Your differentiation is the migration angle + honest Arm cost data + Java-security depth — make that legible, don't bury it.
- **Pre-existing-work clause:** be transparent in the write-up about what predates the hackathon vs what's new. Understating this risks disqualification; overstating wastes your strongest reuse.

---

## 10. Immediate next actions

1. Confirm Arm64 access (which cloud, which instance) — this gates everything in week 2.
2. Confirm current BouncyCastle version + the exact JSSE identifiers for X25519MLKEM768 and ML-DSA.
3. Decide Spring Boot vs raw JSSE for the reference service (raw JSSE recommended for clean benchmarking).
4. Create the repo with the Apache-2.0 license visible, and register the Devpost draft submission now to lock your entry.
5. **Early feasibility check on the native-acceleration lever** (liboqs Arm64 under JSSE): confirm by day 8 whether it's reachable, so you know whether B2 leans on native crypto or on pure JVM/config tuning. This decides how ambitious the optimization centerpiece can be.
