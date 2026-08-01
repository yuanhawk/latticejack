# Regression patterns this checklist is meant to catch

These examples are drawn from a real Java hybrid-PQC TLS/mTLS migration
(a project called Latticejack, built for the Arm AI Optimization
Challenge). They're reproduced here, generalized, as illustration of what
each checklist item in `SKILL.md` catches in practice — you don't need
that project's code or files to use this skill; nothing below depends on
them. If you want the original, project-specific versions (with real file
citations), see `skills/pqc-authoring/examples/` in the Latticejack repo
itself.

## Pattern 1: the silent default-context bug (checklist item 1)

Not a hypothetical — this is a regression a real migration actually
shipped once. The symptom wasn't a crash: the handshake *succeeded*, just
silently over classical negotiation instead of the intended hybrid group,
because `SSLContext.getDefault()`'s auto-bootstrap path resolved
credentials through the JDK's built-in provider (SunJSSE) internals even
though a PQC-capable provider (BCJSSE, in this case) was registered at
higher priority. "The handshake worked" is not sufficient evidence of a
correct migration.

**Input (the kind of change this skill should catch):**

```java
// New connector added to the service, copied from an older example:
public class AdminApiTlsServer {
    public static void main(String[] args) throws Exception {
        SSLContext ctx = SSLContext.getDefault();  // <-- looks fine, isn't
        SSLServerSocketFactory factory = ctx.getServerSocketFactory();
        // ...
    }
}
```

**What the skill should flag:**

> **Flagged: `SSLContext.getDefault()` in `AdminApiTlsServer.java`.** This
> project's hybrid PQC group requires building the `SSLContext` explicitly
> from the PQC-capable provider (see the project's established bootstrap
> path) — `getDefault()`'s auto-bootstrap path can resolve credentials
> through the JDK's default provider internals even when the PQC provider
> is registered as top-priority, and that provider's TLS engine can't use
> those credentials for any cipher suite. The handshake will likely still
> succeed (this is the dangerous part — no error, no exception), just
> silently negotiating classical-only key exchange instead of the intended
> hybrid group. Fix: replace with an explicit `SSLContext` built via the
> project's established `KeyManagerFactory`/`TrustManagerFactory`/
> `SSLContext.getInstance(...)` pattern, naming the PQC provider
> explicitly at each step. Verify the fix by checking for a
> HelloRetryRequest (or provider-specific equivalent) in the handshake
> trace — not just "it connected."

Why this example specifically: it's the strongest test for this
checklist item because it produces *zero* runtime errors. A reviewer
skimming for "does the code compile and connect" would miss it entirely.
A guardrail that only catches things that already fail loudly isn't
earning its place in a review flow.

## Pattern 2: a batch of three unrelated changes (checklist items 2, 4, 5, 6)

Three snippets, reviewed together as one pass — two are real regressions,
one is a deliberate true-negative testing "What NOT to flag." Presented
this way because the ability to correctly withhold a flag on a
plausible-looking but out-of-scope case, in the same pass as catching real
positives, is a stronger test than any one hand-picked example alone.

**Snippet A — a new keystore-generation script for a new listener**

```bash
#!/usr/bin/env bash
set -euo pipefail
keytool -genkeypair -alias admin -keyalg RSA -keysize 2048 -validity 365 \
  -keystore keys/admin/admin.jks -storepass changeit \
  -dname "CN=admin.internal,O=Example"
```

**Snippet B — a new, independently-wired TLS listener**

```java
public class AdminMetricsExporter {
    public static SSLServerSocketFactory buildFactory(KeyStore ks, char[] pw) throws Exception {
        // A dedicated, independently-configured listener for the metrics
        // endpoint - doesn't reuse the shared bootstrap path, since
        // metrics felt low-stakes enough not to bother wiring it through.
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, pw);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx.getServerSocketFactory();
    }
}
```

**Snippet C — an RSA keypair with no TLS involvement at all**

```java
public class AdminTokenSigner {
    private final KeyPair signingKey;

    public AdminTokenSigner() throws Exception {
        // RSA keypair used only to sign/verify opaque admin session tokens
        // (JWT-style) issued after a successful login - never touches the
        // TLS handshake itself, which is unchanged from the existing,
        // already-migrated listener.
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        this.signingKey = kpg.generateKeyPair();
    }
}
```

**What the skill found, applied fresh to the three snippets above:**

> **Flagged: Snippet A.** `-keyalg RSA` for a keystore backing a new TLS
> listener (per Snippet B's use of `admin.jks`) is checklist item 5.
> Beyond the key algorithm itself, note there's no `-sigalg` specified at
> all, which is the more dangerous half of this class of bug — it breaks
> named-group negotiation silently rather than loudly. Fix: match the
> project's actual working keystore-generation invocation (EC key,
> explicit `-sigalg`) rather than reintroducing RSA for a new listener's
> cert from scratch.
>
> **Flagged: Snippet B.** Two independent problems, both checklist item 1:
> `SSLContext.getInstance("TLS")` with no named-group configuration at all
> defaults to the JDK/provider's own preference order, which won't include
> the hybrid group unless the PQC provider is explicitly selected the way
> the project's bootstrap path does it — and separately,
> `KeyManagerFactory.getInstance("SunX509")` requests the JDK default
> provider's algorithm by name, not the PQC provider's, so even fixing the
> first issue alone wouldn't be enough. This is also checklist item 6 on
> its own terms, independent of whether either individual call is wrong: a
> second, independently-configured TLS listener is a second place
> classical-only crypto can creep back in, by construction — the comment
> in the snippet ("didn't bother wiring it through the shared path") is
> itself the risk describing itself. Fix: route through the project's
> shared bootstrap factory rather than building a parallel `SSLContext` by
> hand, and verify with the same handshake-trace check used elsewhere in
> the project (HelloRetryRequest observed, not just "the endpoint
> responds").
>
> **Not flagged: Snippet C.** `KeyPairGenerator.getInstance("RSA")` is
> checklist item 2's literal trigger phrase, but the exception in "What
> NOT to flag" applies directly: this key material signs opaque session
> tokens after login, not TLS/mTLS handshake or certificate material for
> that handshake — the listener that actually terminates the login
> connection is unchanged from the existing, already-migrated path (per
> the snippet's own comment, which is worth treating as a claim to verify
> against the actual login-handling code rather than trusting outright —
> that confirmation is the honest next step in a real review, not a rubber
> stamp). Flagging RSA-for-JWT-signing here would be exactly the noise
> this skill's scoping exists to avoid, and would train a reviewer to
> ignore RSA-related flags generally — a more expensive failure mode
> long-term than missing one narrow instance.

## What this demonstrates, and what it doesn't

**Demonstrates:** the checklist catches structurally different regression
classes — a keystore-generation bug, a silent default-context bug, and a
second independently-wired listener — and correctly withholds a flag on a
plausible-looking but genuinely out-of-scope RSA usage rather than
over-flagging.

**Doesn't demonstrate:** checklist item 3 (`Cipher.getInstance("RSA/...")`
in a handshake context) and part of item 4 (a provider-ordering change
that specifically *deprioritizes* rather than omits the PQC provider)
aren't exercised by these examples. Apply the same "don't overclaim"
discipline to this skill's own coverage that the skill asks reviewers to
apply to their findings.
