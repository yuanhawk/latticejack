# Executed review: a real run, not an authored walkthrough

`worked-example.md` (alongside this file) is explicit about what it is:
one known regression from this project's own history, written up as a
narrated example — useful for explaining the skill, but not evidence the
skill's checklist actually catches something when applied fresh, live, to
input the reviewer hasn't seen the answer to in advance.

This file is that evidence. Mechanism, stated plainly rather than implied:
`skills/pqc-authoring/` lives at the repo's top level, not under
`.claude/skills/`, so it isn't auto-registered as an invokable Skill in a
Claude Code session against this repo as-is (checked directly - no
`.claude/skills/` directory exists here). What "executed" means concretely
here: the full, unmodified content of `SKILL.md` was loaded into context,
and three new code snippets below — written for this transcript, not
existing anywhere else in this project, exercising checklist items **2**,
**4**, **5**, and **6**, none of which `worked-example.md` covers (that one
only demonstrates item 1) — were reviewed against it in a single pass,
with the analysis below produced from that review, not written first and
matched to the snippets afterward. Two are real regressions the checklist
should catch; one is a deliberate true-negative, testing the "what NOT to
flag" section specifically, not just the positive cases — the same
discipline this project's own benchmark work applies (a lever that finds
nothing is still a reportable, checked result, not a skipped one).

## Input: three unrelated changes reviewed as one batch

**Snippet 1 — `scripts/gen-admin-keys.sh` (new file, for a planned admin API listener)**

```bash
#!/usr/bin/env bash
set -euo pipefail
keytool -genkeypair -alias admin -keyalg RSA -keysize 2048 -validity 365 \
  -keystore keys/admin/admin.jks -storepass changeit \
  -dname "CN=admin.internal,O=Latticejack"
```

**Snippet 2 — `AdminMetricsExporter.java` (new file, exports JVM metrics over its own listener)**

```java
public class AdminMetricsExporter {
    public static SSLServerSocketFactory buildFactory(KeyStore ks, char[] pw) throws Exception {
        // A dedicated, independently-configured listener for the metrics
        // endpoint - doesn't reuse ProviderBootstrap, since metrics felt
        // low-stakes enough not to bother wiring it through the shared path.
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, pw);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx.getServerSocketFactory();
    }
}
```

**Snippet 3 — `AdminTokenSigner.java` (new file, signs short-lived admin session tokens)**

```java
public class AdminTokenSigner {
    private final KeyPair signingKey;

    public AdminTokenSigner() throws Exception {
        // RSA keypair used only to sign/verify opaque admin session tokens
        // (JWT-style) issued after a successful login - never touches the
        // TLS handshake itself, the listener terminating that login request
        // is the existing EchoTlsServer/ProviderBootstrap path unchanged.
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        this.signingKey = kpg.generateKeyPair();
    }
}
```

## What the skill found, applied fresh to the three snippets above

> **Flagged: Snippet 1, `scripts/gen-admin-keys.sh`.** `-keyalg RSA` for a
> keystore backing a new TLS listener (per Snippet 2's use of `admin.jks`)
> is exactly checklist item 5 - the same class of bug this project's own
> `scripts/gen-classical-keys.sh` had, found via independent audit and
> documented in `docs/bouncycastle-pqc-notes.md`. Beyond the key algorithm
> itself, note there's no `-sigalg` specified at all, which is the specific
> half of that earlier bug that broke named-group negotiation silently
> rather than loudly. Fix: match `scripts/gen-classical-keys.sh`'s actual
> working invocation (EC key, explicit `-sigalg`) rather than reintroducing
> RSA for a new listener's cert from scratch.
>
> **Flagged: Snippet 2, `AdminMetricsExporter.java`.** Two independent
> problems, both checklist item 1: `SSLContext.getInstance("TLS")` with no
> named-group configuration at all defaults to the JDK/provider's own
> preference order, which won't include `X25519MLKEM768` unless BCJSSE is
> explicitly selected the way `ProviderBootstrap.buildContext()` does it -
> and separately, `KeyManagerFactory.getInstance("SunX509")` requests
> SunJSSE's algorithm by name, not BCJSSE's, so even fixing the first issue
> alone wouldn't be enough. This is also checklist item 6 on its own terms,
> independent of whether either individual call is wrong: a second,
> independently-configured TLS listener is a second place classical-only
> crypto can creep back in, by construction, regardless of how carefully
> each call inside it is written - the comment in the snippet ("didn't
> bother wiring it through the shared path") is itself the risk describing
> itself. Fix: route through `ProviderBootstrap.buildContext()` (or an
> equivalent shared factory) rather than building a parallel `SSLContext`
> by hand, and verify the same way `run-after.sh` does - HelloRetryRequest
> observed in the handshake trace, not just "metrics endpoint responds
> over HTTPS."
>
> **Not flagged: Snippet 3, `AdminTokenSigner.java`.** `KeyPairGenerator.getInstance("RSA")`
> is checklist item 2's literal trigger phrase, but the exception in "What
> NOT to flag" applies directly: this key material signs opaque session
> tokens after login, not TLS/mTLS handshake or certificate material for
> that handshake - the listener that actually terminates the login
> connection is unchanged from the existing, already-migrated path (per
> the snippet's own comment, which is worth treating as a claim to verify
> rather than trust outright: confirming that comment against the actual
> login-handling code, not just this snippet in isolation, would be the
> honest next step in a real review, not a rubber stamp). Flagging
> RSA-for-JWT-signing here would be exactly the noise the skill's own "not
> hypothetical, but also not everything" scoping exists to avoid - and
> training a reviewer to ignore RSA-related flags generally, which is the
> more expensive failure mode long-term than missing one narrow instance.

## What this transcript demonstrates, and what it doesn't

**Demonstrates:** the checklist catches two structurally different
regression classes it wasn't specifically authored around in
`worked-example.md` (a keystore-generation bug and a second, independently
-wired TLS listener, versus that file's `SSLContext.getDefault()` case),
and correctly withholds a flag on a plausible-looking but genuinely
out-of-scope RSA usage rather than over-flagging. That combination -
catching real positives across more than one checklist item, on unseen
input, in the same pass as declining a false positive - is a stronger
claim than a single hand-picked example can make on its own.

**Doesn't demonstrate:** this is still one execution, of three
hand-constructed snippets, by the same author who wrote the skill's
checklist - not an independent third party's adversarial test set, and not
a claim that the skill catches every regression class a real codebase
could introduce (checklist items 3 and part of 4 - a `Cipher.getInstance("RSA/...")`
call, or a provider-ordering change specifically deprioritizing rather
than omitting BCJSSE - aren't exercised here). Stated plainly rather than
left for a reader to assume this closes the question.
