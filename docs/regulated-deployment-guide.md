# Choosing an implementation: financial/regulated Java shops

This project's optimization work (B2, eight levers) exists because of a
specific, deliberate choice made in `arm-hackathon-plan.md` §3a: **stay in
Java, with at most a narrow native crypto backend behind a thin boundary —
never a full rewrite.** This document explains why that choice is
specifically the right one for regulated financial-sector Java shops (not
just "Java is what we know"), what this project's own evidence says about
where the real performance cost actually lives, and how to choose an
implementation depending on which regulatory regime applies. Written for
the audience this project is actually aimed at, per its own Track 2
framing: teams that cannot realistically rewrite core systems in Rust or
C, not teams choosing a language on a green field.

## Why Java, not a pure-native rewrite — for this audience specifically

**The codebase is the asset, and it's already certified.** A bank's core
transaction/settlement path is rarely a clean green-field service. It's
usually a JVM codebase (Java, Kotlin, or Scala on the JVM) that has
accumulated years of change-control history, model-risk sign-off, and
compliance scope boundaries (PCI-DSS, SOC 2, internal audit) *attached to
that specific codebase*. A crypto library swap inside an existing,
already-certified system is a contained, auditable change. A rewrite in
Rust or C to chase native-assembly speed re-opens the entire certification
boundary — not because Rust is less trustworthy, but because "we replaced
the language the system is written in" is a materially different, much
larger change to explain to an auditor than "we replaced one crypto
provider inside an unchanged system." This project's whole Component A —
a migration that changes the crypto provider and nothing else about the
service — is deliberately the shape of change a regulated shop can
actually ship.

**The operational maturity is JVM-specific and took years to build.**
GC-tuning runbooks, APM agents (Datadog/Dynatrace/AppDynamics JVM
integrations), incident-response playbooks, capacity-planning models — all
built around the JVM's specific failure modes. Moving core paths off the
JVM means rebuilding that operational maturity from zero, on top of the
crypto migration, not instead of it.

**And critically — this project's own evidence says the JVM isn't even
where the real cost is**, so a rewrite would often be solving the wrong
problem. `benchmarks/arm-performix-profile/README.md`'s hardware-profiled
finding: for a warm, long-running service, only ~5.55% of CPU time is
BouncyCastle's own crypto and protocol code; ~73% is JVM/JIT overhead, and
the actual ML-KEM math per `benchmarks/mlkem-microbench/` is tens of
microseconds — negligible against typical financial transaction latencies
(milliseconds to seconds, for anything that isn't sub-millisecond HFT
market-making, which is its own distinct case, addressed below). Rewriting
the *whole system* in Rust to speed up a component that's already a few
percent of total cost doesn't pay for its own risk. **This project's
levers 4 and 5 exist because they target the two costs that actually
measured large: JVM startup (lever 4, ~7.9x, a JVM-tooling change with
zero crypto-provider risk) and, for the minority of deployments where
per-handshake crypto cost genuinely dominates, a narrow native crypto
backend behind a single provider class (lever 5's still-open `KEMSpi`
integration) — not a system rewrite either way.**

## Specific implementation recommendation

Three tiers, by deployment shape - not one answer for every service:

**1. Short-lived / high-churn processes (API gateways, autoscaled
containers, batch jobs, anything paying JVM startup cost repeatedly):**
adopt hybrid PQC via BC (Component A's pattern) and deploy with **GraalVM
native-image** (lever 4). This is a build/deployment-tooling change — no
crypto code changes, no new provider, no change to what an auditor needs
to re-review beyond "we changed how the jar is packaged." Biggest win
this project measured (~7.9x cold-start), and the lowest-risk one to ship.

**2. Long-running, high-connection-churn servers** (a payment gateway or
market-data feed terminating thousands of mTLS connections/sec, where
per-handshake crypto cost compounds over the service's lifetime past
lever 4/5's ~14,050-handshake crossover point): the concrete next step is
finishing what `benchmarks/mlkem-ffm-bench/README.md` explicitly flags as
not yet done — implementing a real `javax.crypto.KEMSpi` (or the BC
-internal equivalent) backed by `mlkem-native` via the FFM binding already
built here, registered as a standard JCA provider. This keeps the change
boundary to **one new provider class**, auditable in isolation, with
everything else in the service unchanged Java. Not a rewrite of the
service - a provider swap, the same shape of change Component A already
demonstrated works.

**3. Genuinely latency-critical paths (sub-millisecond HFT, market
-making):** even here, the recommendation is a narrow native crypto
backend behind a Java-callable boundary (tier 2's approach), not a system
rewrite — the FFM integration measured in lever 5 already gets within
~15% of the raw C ceiling, and lever 8 showed a *Rust*-wrapped equivalent
gets within 3.3% of it. The performance-critical unit is the KEM
operation, not the surrounding service; isolate the native swap to that
boundary specifically.

## Regulatory-context matrix: FIPS, FedRAMP, and what this project can and can't tell you

**Read this section as a decision framework, not a certification claim.**
Crypto-module validation status changes over time and by jurisdiction;
verify current status against the authoritative source
([NIST's CMVP validated modules list](https://csrc.nist.gov/projects/cryptographic-module-validation-program/validated-modules))
at decision time, not against this document. Two distinctions this
document keeps separate on purpose, because they're often conflated:

- **Algorithm standardization** (ML-KEM is FIPS 203, finalized August
  2024 - the *math* is a settled NIST standard) is not the same as
  **module validation** (a specific *software implementation* of that
  algorithm passing NIST's CMVP certification process). A standardized
  algorithm implemented in an unvalidated module does not satisfy a
  FIPS-140 requirement.
- **Formal verification** (what `mlkem-native` has - CBMC memory-safety
  proofs, HOL-Light constant-time proofs, per
  `benchmarks/mlkem-native-bench/README.md`) is a different, and in some
  ways stronger, mathematical guarantee than FIPS validation - but it is
  **not** FIPS validation. This project has not confirmed `mlkem-native`
  is on NIST's CMVP list, and it should not be assumed to be without
  checking directly.

| Context | What's actually required | What this project used / recommends | Arm optimization still available |
|---|---|---|---|
| **General enterprise Java, no FIPS/FedRAMP mandate** | No specific crypto-module certification | Plain BouncyCastle (`bcprov-jdk18on`, `bctls-jdk18on`) - exactly what this project used throughout, matches Component A as-shipped | All eight levers apply as measured; L4 broadly, L5/7/8's FFM pattern for high-throughput services |
| **Internal policy referencing FIPS 140 (common in financial regulators even absent a strict federal mandate)** | A FIPS-140-validated crypto module for the algorithms in scope | **BC-FIPS**, not plain BC - a separate BouncyCastle distribution; whether its ML-KEM implementation is currently CMVP-validated needs a live check, this project did not use or test BC-FIPS. Earlier research this session (not independently re-verified) flagged a specific compatibility friction between BC-FIPS and GraalVM native-image (tracked upstream as bc-java issue #4135 per that research) - treat this as a real, unresolved risk requiring its own feasibility gate, the same way this project gated lever 5's native-acceleration path on a day-8 feasibility check before committing to it | **L6 (Vector API, pure Java) is the safest lever in a FIPS-constrained environment** - it never touches the validated crypto module's boundary or swaps any provider, so it doesn't reopen the module-validation question at all. L4 (native-image) needs its own feasibility check against BC-FIPS specifically, not assumed to work from this project's plain-BC results. L5/7/8's native-swap pattern is the highest-risk option here, since it introduces an entirely new crypto module (mlkem-native/pqcrypto/RustCrypto) that would need its *own* independent FIPS validation before use - none of the three are validated today to this project's knowledge |
| **FedRAMP (US federal cloud authorization, protecting federal data)** | FedRAMP-authorized cloud/region **and** FIPS-140-validated cryptography (NIST SP 800-53 control SC-13) - both are hard requirements, not preferences | Whatever crypto module is used must appear on the current CMVP validated modules list for the specific deployment - check at decision time, don't assume from this document | Arm is available in FedRAMP-authorized environments (e.g. Graviton in AWS GovCloud - confirm current regional/service availability at decision time); the same optimization *shape* (native-image for cold start, narrow validated-module-respecting native swap for warm throughput) applies, but is entirely gated on the module-validation question being resolved first - optimization is a secondary concern to compliance eligibility here |
| **Non-US regulatory regimes (EU eIDAS, UK NCSC guidance, etc.)** | Jurisdiction-specific certification schemes, out of this project's own research scope | Consult the specific regulator's crypto-module requirements directly - this document deliberately does not fabricate cross-jurisdictional certification claims it hasn't verified | Same Arm-optimization principles apply once that jurisdiction's module-validation question is separately resolved |

## The honest summary

This project's own crypto (plain BC) is not FIPS-validated as tested -
that's consistent with everything else in this project's ethos of stating
scope plainly (see `MIGRATION.md` "Scope" and `WRITEUP.md`'s "What's not
done"). What *is* transferable to a regulated deployment, independent of
which specific crypto module ends up compliant, is the **optimization
shape**: attack JVM startup cost with tooling changes that don't touch the
crypto boundary (lowest risk, works regardless of which validated module
you land on), and isolate any native-crypto swap to the smallest possible
boundary (a single provider class), never a system rewrite. That
separation - compliance decision here, optimization strategy there - is
what lets a regulated team make the crypto-module decision on compliance
grounds alone, without the optimization roadmap forcing their hand.
