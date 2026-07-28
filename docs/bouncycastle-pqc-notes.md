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

## 2. Hybrid ML-KEM key exchange — supported, wired in this repo

BCJSSE negotiates **`X25519MLKEM768`** as a TLS 1.3 named group. `ProviderBootstrap`
(`src/main/java/com/latticejack/pqc/ProviderBootstrap.java`) installs it:

```java
BouncyCastleProvider bc = new BouncyCastleProvider();
Security.insertProviderAt(bc, 1);
Security.insertProviderAt(new BouncyCastleJsseProvider(bc), 2);
System.setProperty("jdk.tls.namedGroups", "X25519MLKEM768,x25519,secp256r1");
```

Passing the `BouncyCastleProvider` *instance* into the `BouncyCastleJsseProvider`
constructor (not a bare no-arg construction) matters for it to actually use BC's
crypto underneath.

**Caveat:** on Java 25, BCJSSE layered on default/SunJSSE crypto providers can
silently disable ML-KEM groups unless the BC crypto provider is also
top-priority — [bcgit/bc-java#2252](https://github.com/bcgit/bc-java/issues/2252).
We're targeting JDK 21 LTS, but if the project moves to 25 later, re-check this.
Reference integration example: [oscerd/camel-pqc-tls](https://github.com/oscerd/camel-pqc-tls)
(`camel.ssl.provider=BCJSSE`, `camel.ssl.namedGroups=X25519MLKEM768,secp256r1`; notes
you may need to drop `ECDH` from `jdk.tls.disabledAlgorithms`).

This is what `run-after.sh` exercises today — hybrid KEX only, classical
ECDSA certs.

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

For certificate generation once this is tackled: use the standardized name
**`ML-DSA-65`**, not `Dilithium3` — post-standardization ML-DSA uses different
encodings than pre-standard Dilithium (FIPS 204 / draft-ietf-lamps-dilithium-certificates).
BC's keytool extension supports ML-DSA-44/65/87.

## 3a. Live debugging findings (2026-07-28, this session)

Wiring the hybrid KEX path (`run-after.sh`, `ProviderBootstrap`) surfaced two
real issues not covered above. Not documented publicly anywhere found during
this session — recorded here so the next person doesn't re-derive them.

**Fixed: `SSLContext.getDefault()` doesn't bridge BC credentials correctly.**
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
See `ProviderBootstrap.buildContext()`. Confirmed this fixes the classical-suite
case entirely (handshake succeeds with BC's full default named-group list).

**Red herring, ruled out:** initially suspected the JDK's default
`jdk.tls.disabledAlgorithms` (which contains a blanket `ECDH` entry) was
being over-broadly matched by BC's constraint parser and disabling every
ECDHE-family group. Tested by clearing the property entirely — made no
difference. Not the cause; don't waste time on it again.

**Open, unresolved: `X25519MLKEM768` alone does not negotiate.** With the
`SSLContext.getDefault()` bug fixed above, a handshake using BC's full
default named-group list succeeds. But restricting `SSLParameters.setNamedGroups()`
to `{"X25519MLKEM768"}` only (both sides, TLS 1.3-only, one-way and mTLS both
tested) still fails with the same "no selectable cipher suite" alert. Since
BC's `NamedGroup` class defines the group (int 4588) and `bcprov-jdk18on`
does register ML-KEM asymmetric algorithm support
(`org.bouncycastle.jcajce.provider.asymmetric.MLKEM` and friends), the
crypto and group-ID plumbing exists — but something in `NamedGroupInfo`'s
"is this group active for this connection" computation isn't lighting up
for the hybrid entry specifically. Deliberately not worked around with a
classical-fallback group list (see `ProviderBootstrap.NAMED_GROUPS` comment)
because that would silently mask this rather than prove PQC negotiation.

**Next steps for Week 1 days 5-7:** trace `NamedGroupInfo` /
`NamedGroupInfo$All`'s activation logic directly (decompile or find source
matching 1.85), or file/search a BC issue with this exact repro (BC installed
via explicit SSLContext, default groups succeed, `X25519MLKEM768`-only fails).
Repro: `LATTICEJACK_DEBUG=1 ./run-after.sh` in this repo.

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
