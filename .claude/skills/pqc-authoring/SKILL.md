---
name: pqc-authoring
description: Review new or changed Java TLS/mTLS handshake code during a classical-to-PQC (post-quantum cryptography) migration, and flag any newly-introduced classical-only key exchange, certificate-signature configuration, or provider-ordering change that would silently reintroduce quantum-vulnerable crypto into an otherwise-migrated codebase. Use when reviewing a diff, a new file, or a described change touching SSLContext/SSLParameters setup, TLS named-group configuration, keystore/certificate generation, or JCA/JSSE provider registration in any Java codebase that has (or is migrating to) hybrid PQC key exchange.
---

# PQC authoring guardrail

**What this is for:** the *prevention* half of a classical-to-PQC crypto
migration. If your project also maintains a CBOM (Cryptography Bill of
Materials) or another crypto inventory, that's the *audit* half — it shows
what's already deployed. This skill is complementary to that: it stops new
regressions from landing in the first place. A migration is only as good
as its ability to *stay* migrated: once a Java TLS/mTLS service has moved
to hybrid PQC key exchange, it's easy for a later change — a new
connector, a copy-pasted example from an older part of the codebase, a
dependency upgrade that resets provider ordering — to quietly reintroduce
classical-only crypto without anyone noticing, because a classical TLS
handshake still *works*, it's just no longer quantum-resistant. This
skill's job is to catch that at review time, before it merges.

**When to use this skill:** invoked to review a diff, a new file, or a
described code change touching TLS/mTLS setup, key exchange group
configuration, certificate/keystore generation, or JCA/JSSE provider
registration in a Java codebase that has (or is migrating to) hybrid PQC
key exchange.

## Before first use on a new codebase

This skill is generic by design — it doesn't assume any particular
project's file layout. Spend one short pass on the target codebase to
identify these four things before applying the checklist below, since
several items refer back to them:

1. **The project's TLS bootstrap path** — the function/class that builds
   `SSLContext`/`SSLParameters` explicitly, rather than relying on
   `SSLContext.getDefault()` or a JDK-wide provider default. If no such
   centralized path exists at all, note that explicitly: its absence is
   itself a risk factor for checklist item 6.
2. **The PQC-capable JCA/JSSE provider in use** — e.g. BouncyCastle's
   `BCJSSE`, or another provider supplying ML-KEM / hybrid groups — and
   which provider(s) it needs to outrank in provider-preference order to
   actually be selected.
3. **The hybrid named-group list the migration targets** — the specific
   TLS group identifier(s), e.g. `X25519MLKEM768`, `SecP256r1MLKEM768`, or
   a draft identifier like `X25519Kyber768Draft00` depending on provider
   and JDK version.
4. **The migration's stated scope** — a MIGRATION.md, ADR, or design doc
   describing what's in scope now versus explicitly deferred. Many
   migrations move key exchange to hybrid PQC first and leave
   certificate-signature algorithms classical for longer, since PQC
   signature schemes and CA/PKI tooling generally lag key exchange
   adoption. Don't invent scope — read what the project actually
   committed to.

If one or more of these can't be found, say so plainly rather than
guessing, and ask — the checklist below is meaningfully weaker without
them, and "I couldn't find a bootstrap path" is itself a useful finding.

## What to look for

Flag any of the following if newly introduced or modified in a way that
narrows the crypto configuration back toward classical-only:

1. **`SSLContext`/`SSLParameters` configuration that sets or defaults to a
   named-group list without a PQC/hybrid group.** The hybrid group(s)
   identified above must be listed *and reachable*. A new connector or
   client that omits the PQC group, or that calls `SSLContext.getDefault()`
   / relies on a JDK/provider default instead of setting named groups
   explicitly, will silently negotiate classical-only. This is a real,
   observed failure mode, not a hypothetical one — see the worked example
   in `examples/regression-patterns.md` for a documented case where this
   produced a handshake that succeeded with zero runtime errors while
   silently downgrading to classical key exchange.
2. **New `KeyPairGenerator.getInstance("RSA")` or `("EC")` calls for
   TLS/handshake key material** (as opposed to unrelated uses — signing a
   JWT, encrypting a file — which are out of scope for this check; don't
   flag those). Ask: is this key material used in a `KeyManagerFactory`,
   `SSLContext`, or handshake path? If yes, and there's no accompanying
   hybrid-KEX configuration, flag it.
3. **New `Cipher.getInstance("RSA/...")` usage in a key-exchange or
   handshake context** — RSA key transport is exactly the mechanism PQC
   migration replaces; flag any new use inside connection-establishment
   code, not general-purpose encryption elsewhere.
4. **Provider registration or ordering changes**
   (`Security.insertProviderAt`, `Security.addProvider`, changes to
   `java.security` provider config files) that could deprioritize or
   remove the provider supplying the PQC groups (identified above) in
   favor of a provider that only supports classical groups (e.g. plain
   SunJSSE).
5. **Certificate/keystore generation scripts** (`keytool`, or an
   equivalent PKI tool) using `-keyalg RSA`, or omitting an explicit
   signature algorithm for EC keys — a mismatched or missing signature
   algorithm can silently break named-group negotiation even when the key
   algorithm itself looks fine.
6. **New TLS connections/services elsewhere in the codebase that don't go
   through the project's established bootstrap path** (identified above)
   — a second, independently-configured TLS client/server is a second
   place classical-only crypto can creep back in, regardless of how
   carefully each individual call inside it is written.

## What NOT to flag

- Classical algorithms used for anything **other than TLS/mTLS handshake
  key exchange or certificate signing for that handshake** — general file
  encryption, JWT signing, password hashing, unrelated application crypto.
  Flagging these is noise that trains reviewers to ignore the tool.
- **Certificate signature algorithms**, if the project's own migration
  scope hasn't extended to certificate authentication yet (per the scope
  doc identified above). Don't demand PQC signatures a migration plan has
  honestly scoped out; that's a different, larger, separately-tracked gap.
- **Test-only or example code clearly marked as such**, unless it's the
  kind of copy-pasteable example other developers are likely to lift
  verbatim into production paths.

## How to respond when something is flagged

1. State exactly what was found and why it matters — cite the specific
   line/file, not a generic warning.
2. Point to the working pattern already established in the codebase to
   fix it (the bootstrap path, named-group list, and provider identified
   in "Before first use"), rather than describing PQC configuration from
   scratch each time. If the codebase has no established working pattern
   yet, say that too, and recommend establishing one rather than
   fixing only this instance.
3. If the finding is genuinely ambiguous (e.g. a classical algorithm whose
   call site isn't clearly a handshake path), say so plainly and ask,
   rather than guessing.

## Reference material

`examples/regression-patterns.md` (bundled alongside this file) walks
through real, previously-observed regression patterns exercising each
checklist item above — including the silent `SSLContext.getDefault()`
failure mode referenced in item 1, and a batch of three snippets reviewed
fresh in a single pass, two flagged correctly and one correctly withheld
as a true negative. These examples originate from a real hybrid-PQC TLS
migration (a project called Latticejack) and are cited there as
illustration only — this skill does not depend on that project's files or
layout, and applies to any Java TLS/mTLS codebase migrating to hybrid PQC.
