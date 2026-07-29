# Component C: authoring guardrail + CBOM

The reuse/adoption half of this project, per `arm-hackathon-plan.md` §3:
prevention (stop new classical-crypto regressions from creeping back into
an already-migrated codebase) plus audit (a standards-based, machine
-readable record of exactly what's migrated and what isn't). Framed for
the audience this is actually meant for: Java shops in regulated
industries — finance, insurance — where a full rewrite is never realistic
and migration has to be incremental, auditable, and defensible to a
compliance review, not just "we upgraded a library."

## `skills/pqc-authoring/` — the prevention half

A [Claude Code Skill](../skills/pqc-authoring/SKILL.md) that reviews new
or changed Java TLS/mTLS code for regressions back toward classical-only
crypto — the specific failure mode this project hit once already
(`SSLContext.getDefault()` silently negotiating classical instead of the
intended hybrid group, with *no runtime error at all*; see
`docs/bouncycastle-pqc-notes.md`). See
[`skills/pqc-authoring/examples/worked-example.md`](../skills/pqc-authoring/examples/worked-example.md)
for that exact bug worked through as a test case, proving the skill's
checklist catches a real regression, not just a hypothetical one.

Deliberately scoped narrow: it flags classical crypto in TLS/handshake
contexts specifically, not general-purpose application crypto elsewhere
(JWT signing, file encryption, password hashing) — a guardrail that flags
everything trains reviewers to ignore it. It also explicitly does *not*
demand PQC certificate signatures, since this project's own migration
scope hasn't extended there yet (ML-DSA cert auth deferred, see
`MIGRATION.md` "Scope") - the guardrail respects what the migration has
honestly scoped in vs. out, rather than manufacturing false-positive
pressure toward a target the project itself hasn't committed to.

## `component-c/cbom/` — the audit half

Emits a [CycloneDX](https://cyclonedx.org/) 1.6 Cryptography Bill of
Materials for either the "before" or "after" configuration:

```bash
./run cbom before   # classical: X25519, ECDSA-P256
./run cbom after     # hybrid: X25519MLKEM768 (combiner) + ML-KEM-768 + X25519 + ECDSA-P256
```

**Validated against the real CycloneDX 1.6 JSON schema** (downloaded
directly from `CycloneDX/specification`, not assumed) - both
`before.cbom.json` and `after.cbom.json` pass `jsonschema.validate()`
against it, not just "looks like plausible JSON."

**Honest by construction, not by discipline**: the "after" CBOM still
lists `ECDSA-P256` as a signature asset with `nistQuantumSecurityLevel: 0`
- because certificate authentication genuinely hasn't been migrated in
this project. A CBOM that quietly dropped that asset the moment the key
exchange went PQC would misrepresent the actual migration state, exactly
where an auditable record needs to not do that. Diffing the two files
shows precisely what changed and what didn't, asset by asset:

| Asset | Before | After |
|---|---|---|
| Key exchange | X25519 (classical) | X25519MLKEM768 (hybrid, quantum-safe) |
| Certificate signatures | ECDSA-P256 (classical) | ECDSA-P256 (classical, **unmigrated**) |

Each asset's `evidence.occurrences` field cites exactly where in the
codebase that fact comes from (e.g. `ProviderBootstrap.java`'s
`NAMED_GROUPS`, `scripts/gen-classical-keys.sh`'s exact `keytool`
invocation) - not asserted, traceable.

**Not built:** a general-purpose Java crypto static analyzer, or
reconciliation against a real scanner (CryptoScan/CBOMkit, per the plan's
"optionally" language) - both are legitimate next steps if this were
pursued further, explicitly out of scope for this component per the
plan's own scoping of the (separate, optional) AI-hook stretch goal that
would need that level of static analysis to be useful.
