# BouncyCastle PQC/JSSE research notes

Captured 2026-07-28, for arm-hackathon-plan.md §8/§10 item 2. Verified against
current docs/release notes/GitHub issues, not assumed from memory — re-check
before relying on this past a few weeks, this corner of BC moves fast.

## 1. Current version / Maven coordinates

BC Java **1.85** (released ~2026-07-12) is current, same version number
across the `jdk18on` artifact family (JDK 8+, including 21):

```xml
<dependency>
  <groupId>org.bouncycastle</groupId>
  <artifactId>bcprov-jdk18on</artifactId>
  <version>1.85</version>
</dependency>
<dependency>
  <groupId>org.bouncycastle</groupId>
  <artifactId>bctls-jdk18on</artifactId>
  <version>1.85</version>
</dependency>
```

`bctls-jdk18on` contains `org.bouncycastle.jsse.provider.BouncyCastleJsseProvider`
(BCJSSE). Add `bcpkix-jdk18on` later if/when cert-building helpers are needed
for Component C or ML-DSA cert generation.

Sources: [bcprov-jdk18on on Maven Central](https://central.sonatype.com/artifact/org.bouncycastle/bcprov-jdk18on),
[bctls-jdk18on on mvnrepository](https://mvnrepository.com/artifact/org.bouncycastle/bctls-jdk18on).

## 2. Hybrid ML-KEM key exchange — supported, working in this repo

BCJSSE negotiates **`X25519MLKEM768`** as a TLS 1.3 named group.
`ProviderBootstrap` (`src/main/java/com/latticejack/pqc/ProviderBootstrap.java`)
installs BC and builds an explicit `SSLContext`:

```java
BouncyCastleProvider bc = new BouncyCastleProvider();
Security.insertProviderAt(bc, 1);
Security.insertProviderAt(new BouncyCastleJsseProvider(bc), 2);
```

Passing the `BouncyCastleProvider` *instance* into the `BouncyCastleJsseProvider`
constructor (not a bare no-arg construction) matters for it to actually use BC's
crypto underneath. `NAMED_GROUPS = {"X25519MLKEM768", "secp256r1"}` is applied
per-socket via `SSLParameters.setNamedGroups()`; see §3a for why `secp256r1`
must be present and isn't a "silent fallback" loophole, and for how the
negotiated group is verified (BCJSSE exposes no direct accessor for it).

This is what `run-after.sh` exercises today, and it works end to end —
hybrid KEX confirmed negotiating, classical ECDSA certs.

**Caveat, not yet hit but worth knowing:** on Java 25, BCJSSE layered on
default/SunJSSE crypto providers can silently disable ML-KEM groups unless
the BC crypto provider is also top-priority —
[bcgit/bc-java#2252](https://github.com/bcgit/bc-java/issues/2252). We're on
JDK 21 LTS (see `scripts/require-jdk21.sh`, which pins this specifically
because of toolchain drift found during this investigation — §3a); re-check
if the project ever moves to 25+. Reference integration example:
[oscerd/camel-pqc-tls](https://github.com/oscerd/camel-pqc-tls)
(`camel.ssl.provider=BCJSSE`, `camel.ssl.namedGroups=X25519MLKEM768,secp256r1`).

## 3. ML-DSA for TLS/mTLS certificate auth — experimental, NOT wired yet

BC added `draft-ietf-tls-mldsa` support in **BC Java 1.82 / LTS 2.73.9**
("enabling experimentation with ML-DSA in client-side authentication" —
[release notes](https://www.bouncycastle.org/resources/new-releases-bouncy-castle-java-1-82-and-bouncy-castle-java-lts-2-73-9/)).

As of issue close date 2026-05-11, a BC maintainer confirmed:
> draft-ietf-tls-mldsa-00 is implemented, although the ML-DSA signature
> schemes are not enabled by default
— [bcgit/bc-java#2102](https://github.com/bcgit/bc-java/issues/2102)

A naive attempt currently produces `handshake_failure` (no selectable cipher
suite). There is no documented public system property to flip this on; it
likely needs digging into BC's internal TLS crypto config classes/tests.
**This is real remaining work**, not a config flag — budget accordingly
(arm-hackathon-plan.md Week 1 days 5-7, and the plan's own risk register §9
already flags BC API drift as a risk).

**Decision: deferred to a stretch goal, not part of Component A's shipped
deliverable.** Component A's "after" milestone is hybrid PQC key exchange
(`X25519MLKEM768`) with classical ECDSA authentication, not full ML-DSA
mTLS. Rationale: hybrid KEX is the quantum-relevant half of the migration
anyway — it defends against harvest-now-decrypt-later, which is the honest
headline of PQC migration, and authentication only needs to be
post-quantum once quantum computers actually exist, not retroactively — so
KEX-first is the *correct* migration ordering, not a corner cut. ML-DSA
cert auth is experimental even upstream (not enabled by default, open BC
issue, no documented flag), an unbounded research task that would risk
blowing the days 5-7 budget this project protects for Component B (the
plan's actual scoring centerpiece, per its own fallback ladder in §9). If
time allows later, revisit with a hard time-box; if it doesn't land in that
box, this stays the documented, honest scope boundary rather than an
open-ended blocker.

For certificate generation once this is tackled: use the standardized name
**`ML-DSA-65`**, not `Dilithium3` — post-standardization ML-DSA uses different
encodings than pre-standard Dilithium (FIPS 204 / draft-ietf-lamps-dilithium-certificates).
BC's keytool extension supports ML-DSA-44/65/87.

## 3a. Live debugging findings (2026-07-28/29, this session)

Wiring the hybrid KEX path (`run-after.sh`, `ProviderBootstrap`) surfaced
three real issues. Not documented publicly anywhere found during this
session — recorded here so the next person doesn't re-derive them. The last
one below is the one to read carefully: the investigation initially reached
the wrong conclusion, and it's worth understanding why, since the wrong
conclusion ("BC is broken") was more seductive than the right one ("our test
certs were broken") right up until an independent audit reproduced it from
scratch and found the actual cause.

**1. Fixed: `SSLContext.getDefault()` doesn't bridge BC credentials correctly.**
Using the normal JSSE auto-bootstrap path (`SSLServerSocketFactory.getDefault()`
/ `SSLContext.getDefault()`, driven by `javax.net.ssl.keyStore` system
properties) with BC installed as the top-priority provider produces
`org.bouncycastle.tls.TlsFatalAlert: handshake_failure(40); ... found no
selectable cipher suite` for **every** cipher suite offered — including fully
classical ones with no PQC involvement at all. Root cause: that path resolves
the KeyManager through SunJSSE's internal `SunX509KeyManagerImpl`, and BC's
TLS engine can't use those credentials for suite selection regardless of
provider priority. **Fix:** build the `SSLContext` explicitly using BC's own
provider by name for every piece:
```java
KeyManagerFactory.getInstance("X.509", "BCJSSE");
TrustManagerFactory.getInstance("PKIX", "BCJSSE");
SSLContext.getInstance("TLS", "BCJSSE");
```
See `ProviderBootstrap.buildContext()`.

**2. Fixed: toolchain wasn't actually pinned to JDK 21.** Bare `java` on the
dev machine resolved to JDK 11; bare `mvn` resolved to a *different* JDK
(26.0.1, a Homebrew dependency) — neither the JDK 21 LTS this project
targets, and nothing in `run-before.sh`/`run-after.sh` pinned it. This was a
live suspect for JSSE/BCJSSE behavior differences (see the JDK 25+ BCJSSE
regression noted in §2) until ruled out directly. **Fix:** `scripts/require-jdk21.sh`,
sourced by both run scripts, locates a real JDK 21 and exports `JAVA_HOME`/`PATH`
before anything else runs. (Re-testing the group-negotiation gap below under
correctly-pinned JDK 21 made no difference — it wasn't a JDK-version issue.)

**3. NOT a BC bug: bad test certificates, not BouncyCastle, caused the
"X25519MLKEM768 doesn't negotiate" symptom.** This took the longest to run
down and is worth documenting in full because the wrong turns are as
instructive as the right answer.

*Symptom:* with the `SSLContext.getDefault()` bug above fixed, a handshake
using BC's own uncontrolled default named-group list succeeded. But calling
`SSLParameters.setNamedGroups({"X25519MLKEM768"})` — restricting to the
hybrid group alone — failed with `handshake_failure`/"no selectable cipher
suite," even for the small number of TLS 1.3 cipher suites that don't
actually depend on the key-exchange group at all.

*Wrong turn:* further testing seemed to show the breakage wasn't
PQC-specific at all — even `setNamedGroups({"x25519","secp256r1"})`, a
purely classical list with zero hybrid content, broke the same way, while a
pure no-op `getSSLParameters()`/`setSSLParameters()` round-trip (no call to
`setNamedGroups` at all) succeeded. Switching from the standard
`javax.net.ssl.SSLParameters.setNamedGroups()` to BC's own native
`org.bouncycastle.jsse.BCSSLParameters.setNamedGroups()` API reproduced the
identical failure. This looked like a genuine, reproducible upstream bug —
"calling `setNamedGroups()` at all breaks cipher-suite selection, regardless
of content" — precise enough and surprising enough to be worth filing
against `bcgit/bc-java`.

*Actual cause, found by an independent audit that deliberately tried to
disprove the finding before it got filed publicly:* BCJSSE only offers an
ECDSA signature scheme when its **matching curve is among the active named
groups** (long-standing, if under-documented, BCJSSE behavior — see
[bc-java#1053](https://github.com/bcgit/bc-java/issues/1053),
[bc-java#2034](https://github.com/bcgit/bc-java/issues/2034),
[bc-java#724](https://github.com/bcgit/bc-java/issues/724)). Our test CA
generation script (`scripts/gen-classical-keys.sh`) had a real bug: its
`keytool -gencert` call didn't pass `-sigalg`, so keytool silently defaulted
to signing the leaf certs with `SHA384withECDSA` even though the CA is a
P-256 (`secp256r1`) key — a mismatched combination confirmed via
`openssl x509 -text` on the generated certs. BC maps that chain signature to
the `ecdsa_secp384r1_sha384` scheme, which is only offered when **secp384r1**
is an active group. Restricting groups to anything that doesn't include
secp384r1 (whether `{X25519MLKEM768}` alone or `{x25519,secp256r1}`) meant
the server couldn't validate its own certificate chain — "found no
selectable cipher suite" is a *credential/signature-scheme* selection
failure, not a key-exchange failure, and it happens to produce an identical
symptom regardless of which named groups are involved. That's exactly why
the "even classical-only breaks it" result looked so damning: the actual
variable was never the named-groups content, it was whether secp384r1
happened to still be in the list.

*Fix:* `scripts/gen-classical-keys.sh` now passes `-sigalg SHA256withECDSA`
to `-gencert` as well as `-genkeypair`, so the whole chain signs with a hash
matching its P-256 keys. With that fixed, `NAMED_GROUPS = {"X25519MLKEM768",
"secp256r1"}` (hybrid first, `secp256r1` present so the ECDSA leaf's own
signature scheme is available) negotiates the hybrid group preferentially —
confirmed via a HelloRetryRequest in the handshake trace (see below) — with
**no BC bug, no filed issue, and no workaround needed.**

*Verifying the negotiated group, since a "PQC" handshake that silently used
a classical group would be worse than no handshake at all
(arm-hackathon-plan.md §8):* BCJSSE exposes no direct accessor for the
negotiated named group (checked `BCExtendedSSLSession` and `BCSSLConnection`
— neither has one), and it logs via `java.util.logging`, not
`-Djavax.net.debug`. `run-after.sh` enables BC's logging
(`scripts/bc-logging.properties`) and asserts a HelloRetryRequest occurred
(two `ClientHello extensions` log lines, not one): since the hybrid group is
listed first, an HRR means the client's cheap first guess didn't match what
the server wanted and a second, larger (ML-KEM-inclusive) `key_share` was
sent — the same signal the independent audit used to confirm hybrid
negotiation. This is circumstantial rather than a direct "group=X25519MLKEM768"
read-out, but it's the best available signal without patching BC, and it's
what actually gates `run-after.sh`'s exit code now (see the script for the
exact check).

**Lesson for next time:** when a debugging session accumulates a "clean, if
surprising" root-cause story for a widely-used library (rather than for our
own new code), that's precisely when it's most worth an independent,
skeptical re-derivation before acting on it publicly — the reproducible
symptom was real and consistent at every step; the *interpretation* was
wrong for a while anyway.

## 4. Other caveats

- No special JDK vendor requirement for BC — any OpenJDK 21 build works,
  including on Arm64/Graviton. No PQC-specific blocker at this layer.
- The JDK 25 BCJSSE priority issue (#2252 above) only matters if/when this
  project moves off JDK 21.

## 5. Vanilla JDK PQC (no BouncyCastle) — not usable yet for this project

- **JEP 496** (ML-KEM) and **JEP 497** (ML-DSA): raw `KeyPairGenerator`/`KEM`/
  `Signature`/`KeyFactory` APIs only, landed as preview in JDK 24, finalized in
  **JDK 25**. No TLS integration.
- **JEP 527** ("Post-Quantum Hybrid Key Exchange for TLS 1.3"): the actual JSSE
  integration, targeting **JDK 27**, currently EA (build 6+ as of
  [Inside.java May 2026](https://inside.java/2026/05/17/quality-heads-up/)).
  Adds `X25519MLKEM768` (default-enabled), `SecP256r1MLKEM768`,
  `SecP384r1MLKEM1024` via standard `SSLParameters.setNamedGroups()` /
  `jdk.tls.namedGroups` ([JEP 527](https://openjdk.org/jeps/527)).
- No vanilla-JDK ML-DSA-for-TLS-signatures JEP exists yet, on any version.

**Conclusion: on JDK 21, BouncyCastle is mandatory for any PQC-TLS at all.**
JDK 27 EA would eventually give native hybrid KEX without BC, but still no
ML-DSA cert auth — not a near-term alternative for this project.
