---
name: pqc-authoring
description: Review new or changed Java TLS/mTLS handshake code during a classical-to-PQC migration, and flag any newly-introduced classical-only key exchange or certificate-signature configuration that would silently reintroduce quantum-vulnerable crypto into an otherwise-migrated codebase.
---

# PQC authoring guardrail

**What this is for:** the *prevention* half of a classical-to-PQC crypto
migration (the CBOM in `component-c/cbom/` is the *audit* half — this skill
stops new regressions before they land, the CBOM shows what's already
there). A migration is only as good as its ability to stay migrated: once
a Java TLS/mTLS service has been moved to hybrid PQC key exchange, it's
easy for a later change — a new connector, a copy-pasted example from an
older part of the codebase, a dependency upgrade that resets provider
ordering — to quietly reintroduce classical-only crypto without anyone
noticing, because a classical TLS handshake still *works*, it's just no
longer quantum-resistant. This skill's job is to catch that at review time.

**When to use this skill:** invoked to review a diff, a new file, or a
described code change touching TLS/mTLS setup, key exchange group
configuration, certificate/keystore generation, or JCA/JSSE provider
registration in a Java codebase that has (or is migrating to) hybrid PQC
key exchange.

## What to look for

Flag any of the following if newly introduced or modified in a way that
narrows the crypto configuration back toward classical-only:

1. **`SSLContext`/`SSLParameters` configuration that sets or defaults to a
   named-group list without a PQC/hybrid group.** In this project's own
   pattern (`ProviderBootstrap.java`), the hybrid group must be listed
   *and reachable*: `NAMED_GROUPS = {"X25519MLKEM768", "secp256r1"}`. A new
   connector or client that omits the PQC group, or that calls
   `SSLContext.getDefault()` / relies on a JDK/provider default instead of
   setting named groups explicitly, will silently negotiate classical-only
   — this project found and fixed exactly this class of bug once already
   (see `docs/bouncycastle-pqc-notes.md`), it is a real, not hypothetical,
   risk.
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
4. **Provider registration or ordering changes** (`Security.insertProviderAt`,
   `Security.addProvider`, changes to `java.security` provider config
   files) that could deprioritize or remove the provider supplying the PQC
   groups (e.g. BCJSSE in this project) in favor of a provider that only
   supports classical groups (e.g. plain SunJSSE).
5. **Certificate/keystore generation scripts** (`keytool`, or equivalent)
   using `-keyalg RSA` or omitting `-sigalg` for EC keys (a mismatched
   signature algorithm silently breaks named-group negotiation - this
   project's own `scripts/gen-classical-keys.sh` had exactly this bug,
   found via independent audit, documented in
   `docs/bouncycastle-pqc-notes.md`).
6. **New TLS connections/services elsewhere in the codebase** that don't
   go through the project's established `ProviderBootstrap`-equivalent
   bootstrap path — a second, independently-configured TLS client/server
   is a second place classical-only crypto can creep back in.

## What NOT to flag

- Classical algorithms used for anything **other than TLS/mTLS handshake
  key exchange or certificate signing for that handshake** — general file
  encryption, JWT signing, password hashing, unrelated application crypto.
  Flagging these is noise that trains reviewers to ignore the tool.
- **Certificate signature algorithms**, if this project's own migration
  scope hasn't extended to certificate authentication yet (see
  `MIGRATION.md` "Scope" — ML-DSA cert auth is explicitly deferred here,
  upstream not yet stable). Don't demand PQC signatures the migration
  plan has honestly scoped out; that's a different, larger, separately
  -tracked gap (visible in the CBOM, not this skill's job to force).
- **Test-only or example code clearly marked as such**, unless it's the
  kind of copy-pasteable example other developers are likely to lift
  verbatim into production paths.

## How to respond when something is flagged

1. State exactly what was found and why it matters — cite the specific
   line/file, not a generic warning.
2. Point to the working pattern already established in the codebase to
   fix it (e.g. this project's `ProviderBootstrap.NAMED_GROUPS` and the
   negotiated-group verification pattern in `run-after.sh`), rather than
   describing PQC configuration from scratch each time.
3. If the finding is genuinely ambiguous (e.g. a classical algorithm whose
   call site isn't clearly a handshake path), say so plainly and ask,
   rather than guessing — the same "don't overclaim a root cause you
   haven't earned" discipline this project applies throughout its own
   benchmark findings.

## Reusing this for another codebase

This skill's checklist (items 1-6 above) is written generically enough to
apply to any Java TLS/mTLS codebase migrating to hybrid PQC, not just this
project's own `EchoTlsServer`/`EchoTlsClient`. The specific line/file
citations above are this project's own examples — swap in the equivalent
bootstrap/config location for whatever codebase this is applied to.
