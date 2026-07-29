# Worked example: catching a regression this project actually shipped once

Not a hypothetical. Earlier in this project's own history,
`SSLContext.getDefault()` was used instead of building the context
explicitly from the named BCJSSE provider. The symptom wasn't a crash —
the handshake *succeeded*, just silently over classical negotiation
instead of the intended hybrid group, because `getDefault()`'s auto
-bootstrap path resolves credentials through SunJSSE internals even when
BCJSSE is the top-priority provider. This is exactly the failure mode
item 1 in `SKILL.md` describes, and exactly why "the handshake worked" is
not sufficient evidence of a correct migration — see
`docs/bouncycastle-pqc-notes.md` for the full original debugging account.

## Input (the kind of change this skill should catch)

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

## What the skill should flag

> **Flagged: `SSLContext.getDefault()` in `AdminApiTlsServer.java`, line 3.**
> This project's hybrid PQC group requires building the `SSLContext`
> explicitly from the BCJSSE provider (see `ProviderBootstrap.buildContext()`)
> — `getDefault()`'s auto-bootstrap path resolves credentials through
> SunJSSE internals even when BCJSSE is registered as the top-priority
> provider, and BC's TLS engine can't use those credentials for any cipher
> suite. The handshake will likely still succeed (this is the dangerous
> part — no error, no exception), just silently negotiating classical-only
> key exchange instead of `X25519MLKEM768`. Fix: replace with an explicit
> `SSLContext` built via `KeyManagerFactory.getInstance("X.509", "BCJSSE")`
> / `TrustManagerFactory.getInstance("PKIX", "BCJSSE")` /
> `SSLContext.getInstance("TLS", "BCJSSE")`, matching
> `ProviderBootstrap.buildContext()`. Verify the fix the same way
> `run-after.sh` does — check for a HelloRetryRequest in the handshake
> trace, not just "it connected."

## Why this example, specifically

It's the strongest possible test for this skill: a real bug this project
hit, that produced *zero* runtime errors, and that a reviewer skimming for
"does the code compile and connect" would have missed. A guardrail that
only catches things that already fail loudly isn't earning its place in a
review flow — this is the class of silent regression it exists for.
