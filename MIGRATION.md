# Migrating a Java TLS/mTLS service to hybrid post-quantum crypto

A step-by-step path from classical mTLS (RSA/ECDSA/X25519) to hybrid
post-quantum mTLS (X25519MLKEM768 key exchange), as implemented in this
repo. This is the concrete migration procedure; `docs/bouncycastle-pqc-notes.md`
is the deeper research/investigation log behind it — read this first, that
one when you need the "why."

## Scope

**In scope, working today:** hybrid TLS 1.3 key exchange
(`X25519MLKEM768`), verified negotiating (not silently falling back to
classical), with your existing classical ECDSA certificates unchanged.

**Out of scope, deferred:** ML-DSA (post-quantum) certificate-based
authentication. Experimental and not enabled by default upstream in
BouncyCastle as of this writing ([bcgit/bc-java#2102](https://github.com/bcgit/bc-java/issues/2102)).
Key exchange is the quantum-relevant half of this migration regardless —
it defends against harvest-now-decrypt-later, which doesn't need your
*authentication* to also be post-quantum today. See
`docs/bouncycastle-pqc-notes.md` §3/§3a for the full reasoning.

## What changes

| | Before | After |
|---|---|---|
| Key exchange | X25519 (JDK default) | **X25519MLKEM768** (hybrid), `secp256r1` required as fallback — see Gotcha 3 |
| Certificate signatures | ECDSA P-256 | Unchanged (ECDSA P-256) |
| TLS provider | JDK default (SunJSSE) | BouncyCastle BCJSSE 1.85 |
| New dependencies | — | `org.bouncycastle:bcprov-jdk18on:1.85`, `org.bouncycastle:bctls-jdk18on:1.85` |

## Migration checklist

1. **Add the BouncyCastle dependencies** (`pom.xml`):
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
   Verify the current version before you copy this — this corner of BC
   moves fast (`docs/bouncycastle-pqc-notes.md` §1).

2. **Pin JDK 21.** Don't trust `java`/`mvn` on `PATH` to already be JDK 21 —
   see Gotcha 1. `scripts/require-jdk21.sh` in this repo is a drop-in you
   can copy.

3. **Register BC as the top-priority provider, with the instance shared
   between the crypto and JSSE providers** (`ProviderBootstrap.install()`):
   ```java
   BouncyCastleProvider bc = new BouncyCastleProvider();
   Security.insertProviderAt(bc, 1);
   Security.insertProviderAt(new BouncyCastleJsseProvider(bc), 2);
   ```
   Passing the same `bc` instance into `BouncyCastleJsseProvider`'s
   constructor (not a bare no-arg one) matters.

4. **Build the `SSLContext` explicitly — do not use `SSLContext.getDefault()`
   / `SSLServerSocketFactory.getDefault()`.** See Gotcha 2; this is the
   single most important step and the easiest one to skip if you're used to
   the JDK-default auto-bootstrap pattern.
   ```java
   KeyManagerFactory kmf = KeyManagerFactory.getInstance("X.509", "BCJSSE");
   kmf.init(keyStore, password);
   TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX", "BCJSSE");
   tmf.init(trustStore);
   SSLContext context = SSLContext.getInstance("TLS", "BCJSSE");
   context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
   ```
   See `ProviderBootstrap.buildContext()` for the full version (keystore
   loading, PKCS12 handling).

5. **Set named groups with the hybrid group preferred, plus whatever
   classical group your existing certs' signature scheme needs** — see
   Gotcha 3, this is not optional:
   ```java
   SSLParameters params = socket.getSSLParameters();
   params.setNamedGroups(new String[] {"X25519MLKEM768", "secp256r1"});
   socket.setSSLParameters(params);
   ```

6. **Verify the negotiated group before trusting "handshake complete."**
   BCJSSE exposes no direct accessor for it (checked
   `BCExtendedSSLSession`/`BCSSLConnection` — neither has one). Enable BC's
   `java.util.logging` output (`scripts/bc-logging.properties`,
   `-Djava.util.logging.config.file=...`) and check for a
   HelloRetryRequest — two `ClientHello extensions` log lines, not one.
   Since the hybrid group is listed first, an HRR means the client's cheap
   first guess didn't match and a second, ML-KEM-inclusive `key_share` was
   sent. `run-after.sh` automates exactly this check and fails loudly if it
   doesn't see one — copy that pattern rather than trusting a green
   "handshake complete" log line on its own. A PQC label on a handshake
   that silently negotiated classical is worse than no migration at all.

7. **Existing certificates and keystores need no changes** for this step —
   key exchange is negotiated at handshake time, independent of what's in
   your keystore. (If you're generating fresh test certs, see Gotcha 4.)

## Gotchas (the actual value of this doc — found by direct debugging,
not documented anywhere public as of this writing)

1. **`java`/`mvn` on `PATH` may silently be different JDK major versions
   from each other, and neither may be the one you think.** Observed on
   the dev machine used to build this: bare `java` resolved to JDK 11,
   bare `mvn` resolved to a *different* JDK (26, a Homebrew dependency of
   the `maven` formula). Pin `JAVA_HOME` explicitly rather than trusting
   ambient `PATH` state — `scripts/require-jdk21.sh` does this and fails
   with a clear error (with install commands for macOS/Amazon
   Linux/Ubuntu) if JDK 21 genuinely isn't available.

2. **`SSLContext.getDefault()` doesn't bridge BC's credentials, even with
   BC as the top-priority provider.** It resolves the KeyManager through
   SunJSSE's internal `SunX509KeyManagerImpl`, and BC's TLS engine can't
   use those credentials for cipher-suite selection — every handshake
   fails with `handshake_failure: found no selectable cipher suite`,
   including fully classical suites with no PQC involved at all. Build the
   `SSLContext` explicitly via the `"BCJSSE"` provider for every piece
   (`KeyManagerFactory`, `TrustManagerFactory`, `SSLContext` itself) —
   step 4 above.

3. **An ECDSA certificate's signing curve must be an active named group,
   or the handshake fails with a confusing, unrelated-looking error.**
   BCJSSE only offers an ECDSA signature scheme when its matching curve is
   among the active named groups. If your cert chain is signed with (say)
   `SHA384withECDSA` and you restrict named groups to a list that doesn't
   include `secp384r1`, the server can't validate **its own certificate
   chain** — and the resulting error is `"found no selectable cipher
   suite"`, which looks exactly like a key-exchange failure but is
   actually a signature-scheme credential failure. This produced a long,
   initially-wrong debugging trail in this project (`docs/bouncycastle-pqc-notes.md`
   §3a item 3) before an independent audit found the real cause was our
   own test-certificate generation script, not BouncyCastle. Two
   practical implications:
   - **Make sure your CA and leaf certs are actually signed with a hash
     matching the CA's key** (`keytool -gencert` needs an explicit
     `-sigalg` — it does NOT inherit the one from `-genkeypair`, and
     silently picks a mismatched default otherwise). Verify with
     `openssl x509 -in cert.crt -noout -text | grep "Signature Algorithm"`.
   - **When restricting named groups for an ECDSA-authenticated
     connection, the signing curve must be in the list.** For this repo's
     P-256 chain, that means `secp256r1` has to be offered alongside
     `X25519MLKEM768` — it is not a "silent classical fallback" loophole,
     it's a hard requirement of ECDSA credential selection, and the
     verification in step 6 exists precisely to prove the hybrid group
     still won even with the classical one present.

4. **`keytool`'s default keystore format is PKCS12, not JKS, regardless of
   the `.jks` file extension you give it.** `KeyStore.getInstance("JKS")`
   against a keytool-generated `.jks` file throws a confusing "keystore
   password was incorrect" error that's actually a format mismatch, not a
   real password problem. Use `KeyStore.getInstance("pkcs12")` (or
   `KeyStore.getInstance(KeyStore.getDefaultType())`, which resolves the
   same way on modern JDKs) instead.

## What this doesn't cover

Arm64-specific tuning/benchmarking of this path (JVM/GC/session-resumption
config, native-acceleration options) is Component B, tracked separately per
`arm-hackathon-plan.md` — this document is the migration mechanics only.
