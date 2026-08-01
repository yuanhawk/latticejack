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
for that exact bug worked through as a narrated test case — an authored
document explaining the skill, not a captured transcript of it actually
running. That transcript also now exists, separately:
[`skills/pqc-authoring/examples/executed-review-transcript.md`](../skills/pqc-authoring/examples/executed-review-transcript.md)
is a real, single-pass review of three snippets written specifically for
that run (never seen by the skill's author beforehand in this form),
exercising checklist items the worked example doesn't, correctly flagging
two real regressions and correctly declining to flag a true negative
(RSA used for JWT signing — explicitly out of scope) in the same pass.

Deliberately scoped narrow: it flags classical crypto in TLS/handshake
contexts specifically, not general-purpose application crypto elsewhere
(JWT signing, file encryption, password hashing) — a guardrail that flags
everything trains reviewers to ignore it. It also explicitly does *not*
demand PQC certificate signatures, since this project's own migration
scope hasn't extended there yet (ML-DSA cert auth deferred, see
`MIGRATION.md` "Scope") - the guardrail respects what the migration has
honestly scoped in vs. out, rather than manufacturing false-positive
pressure toward a target the project itself hasn't committed to.

### Using this skill in your own project

The skill above lives at `skills/pqc-authoring/` in *this* repo, cited
throughout with this project's own file names
(`ProviderBootstrap.java`, `run-after.sh`, `MIGRATION.md`) — useful as
worked documentation, but not, as-is, something Claude Code auto-loads in
a session against a different codebase. A second, generalized copy lives
at [`.claude/skills/pqc-authoring/`](../.claude/skills/pqc-authoring/SKILL.md):
same checklist, but every project-specific citation replaced with a
"identify your own X first" step, so it reads correctly cold, in a
different repo, with none of this project's files present. That directory
*is* a real Claude Code
[Agent Skill](https://code.claude.com/docs/en/skills), installable as-is.

Two files kept deliberately separate rather than one made a symlink of
the other, because they now say different things: the root-level version
correctly hardcodes this project's real bootstrap function and file
citations (that's what makes the worked example concrete and checkable),
while the `.claude/skills/` version has to *not* hardcode any of that to
be genuinely reusable elsewhere. A symlink would force both to stay
byte-identical, which is incompatible with that split.

**Mechanics of installing it in another project** (verified against the
current Claude Code skills documentation, not assumed):

1. Copy the whole directory — `SKILL.md` plus its `examples/` subfolder —
   into the target repo at `.claude/skills/pqc-authoring/`. That's the
   entire installation step; there's no build, no manifest, no separate
   registration file. Claude Code discovers any skill on this path
   automatically:

   ```bash
   cp -R .claude/skills/pqc-authoring  /path/to/other-repo/.claude/skills/pqc-authoring
   ```

2. Commit `.claude/skills/pqc-authoring/` to the target repo's version
   control, the same as any other project config, so every teammate's
   Claude Code session (and CI-driven sessions) picks it up — not just
   the machine that copied it in.
3. Claude Code loads project skills from `.claude/skills/` in the
   directory a session starts in, and in every parent directory up to the
   repo root, at session start. If the `.claude/skills/` directory didn't
   exist yet when the session started, restart Claude Code once after
   adding it; if the directory already existed and you're just adding or
   editing a skill inside it, Claude Code picks up the change live,
   mid-session, no restart needed.
4. The directory name (`pqc-authoring`) becomes both the skill's identity
   and its slash command: type `/pqc-authoring` to invoke it directly on
   a diff or description. It also loads automatically without being
   typed — Claude matches the `description` field in `SKILL.md`'s
   frontmatter against what you're doing, so asking Claude to "review
   this TLS change" or "check this keystore script" in a repo with the
   skill installed can trigger it with no explicit invocation at all.
5. For a skill you want available in *every* project on your machine
   rather than one repo at a time, drop the same directory at
   `~/.claude/skills/pqc-authoring/` instead of (or in addition to) a
   per-project `.claude/skills/` — personal-scope skills are available
   session over session, project after project, without being committed
   anywhere.

No plugin packaging, marketplace listing, or `.claude-plugin/plugin.json`
is required for this — that machinery exists for skills meant to bundle
agents/hooks/MCP servers together or be distributed through
`/plugin install`, which is more than this skill needs. A plain directory
under `.claude/skills/` is a complete, standalone, spec-compliant [Agent
Skill](https://agentskills.io) on its own.

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
