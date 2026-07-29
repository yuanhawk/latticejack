package com.latticejack.pqc;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.Security;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;

/**
 * Installs BouncyCastle (BC Java 1.85, confirmed docs/bouncycastle-pqc-notes.md)
 * and builds a BCJSSE {@link SSLContext} explicitly.
 *
 * Explicit construction matters: {@code SSLServerSocketFactory.getDefault()} /
 * SSLContext.getDefault()'s auto-bootstrap path resolves credentials through
 * SunJSSE's internal KeyManager even when BCJSSE is the top-priority provider,
 * and BC's TLS engine can't use those credentials for ANY cipher suite —
 * every handshake fails with "found no selectable cipher suite", including
 * fully classical ones (found by direct debugging, not documented anywhere
 * public as of this writing). Building the KeyManagerFactory/TrustManagerFactory/
 * SSLContext from the "BCJSSE" provider by name, as done here, fixes it.
 *
 * ML-DSA certificate authentication is NOT wired here: upstream BC has it
 * implemented but not enabled by default (bcgit/bc-java#2102, still open as
 * of this writing), so certs stay classical ECDSA on both sides for now —
 * this is a hybrid-KEX-only "after", not the full migration target.
 *
 * Callers MUST guard calls to this class behind the same -Dlatticejack.tls.pqc
 * check rather than calling it unconditionally: the JVM verifier resolves
 * BouncyCastleProvider/BouncyCastleJsseProvider as soon as THIS class is
 * loaded, regardless of which branch runs, so an unconditional call would
 * break the classical "before" run when the BC jars aren't on its classpath
 * (run-before.sh intentionally omits them).
 */
final class ProviderBootstrap {
    private ProviderBootstrap() {}

    /**
     * Hybrid group first (preferred), secp256r1 second - NOT a "silent
     * classical fallback" loophole, but a hard requirement: BCJSSE only
     * offers an ECDSA signature scheme when its matching curve is active
     * among the named groups, so an ECDSA leaf certificate's own signature
     * scheme (and its issuer's, up the chain) needs secp256r1 present or
     * the server can't even validate its own certificate. Confirmed via
     * an independent audit (docs/bouncycastle-pqc-notes.md §3a) that with
     * BOTH groups offered, BC selects X25519MLKEM768 preferentially (a
     * HelloRetryRequest for it is visible in the handshake trace) - verify
     * this explicitly per-run rather than trusting group ORDER alone, via
     * LATTICEJACK_DEBUG=1 and the check run-after.sh does automatically.
     */
    static final String[] NAMED_GROUPS = {"X25519MLKEM768", "secp256r1"};

    static void install() {
        // Under GraalVM native-image, -Djava.util.logging.config.file is read
        // by LogManager's static initializer, which BC's own classes trigger
        // during --initialize-at-build-time=org.bouncycastle (baked into the
        // image before the runtime property can take effect) - so wire the
        // same FINEST-on-org.bouncycastle config (scripts/bc-logging.properties)
        // programmatically instead, via plain instance-method calls that work
        // regardless of when LogManager itself was class-initialized.
        if (Boolean.getBoolean("latticejack.tls.debug")) {
            java.util.logging.Logger bcLogger = java.util.logging.Logger.getLogger("org.bouncycastle");
            bcLogger.setLevel(java.util.logging.Level.FINEST);
            java.util.logging.ConsoleHandler handler = new java.util.logging.ConsoleHandler();
            handler.setLevel(java.util.logging.Level.FINEST);
            handler.setFormatter(new java.util.logging.SimpleFormatter());
            bcLogger.addHandler(handler);
            bcLogger.setUseParentHandlers(false);
        }
        // Reuse an already-registered instance if present rather than always
        // instantiating fresh: under GraalVM native-image, a build-time Feature
        // (see graalvm-native-work/feature-src/BouncyCastleFeature.java, not
        // committed here - it's build tooling, not app code) registers a specific
        // Provider instance via Security.addProvider() during image generation,
        // baking it into the image heap as build-time-verified. A fresh
        // `new BouncyCastleProvider()` here would be a different object with no
        // cached verification result, so javax.crypto.JceSecurity.getVerificationResult()
        // throws under SVM even though the same code runs fine on HotSpot. No-op on
        // plain HotSpot beyond avoiding a redundant registration.
        BouncyCastleProvider bc = (BouncyCastleProvider) Security.getProvider("BC");
        if (bc == null) {
            bc = new BouncyCastleProvider();
            Security.insertProviderAt(bc, 1);
        }
        BouncyCastleJsseProvider bcJsse = (BouncyCastleJsseProvider) Security.getProvider("BCJSSE");
        if (bcJsse == null) {
            bcJsse = new BouncyCastleJsseProvider(bc);
            Security.insertProviderAt(bcJsse, 2);
        }
        System.out.println("[provider-bootstrap] installed BouncyCastle " + bc.getVersionStr()
                + " + BCJSSE, namedGroups=" + String.join(",", NAMED_GROUPS));
    }

    /** Builds an SSLContext from BCJSSE explicitly - see class Javadoc for why this is required. */
    static SSLContext buildContext() throws Exception {
        String storeType = System.getProperty("javax.net.ssl.keyStoreType", KeyStore.getDefaultType());

        KeyManager[] keyManagers = null;
        String keyStorePath = System.getProperty("javax.net.ssl.keyStore");
        if (keyStorePath != null) {
            char[] password = System.getProperty("javax.net.ssl.keyStorePassword", "").toCharArray();
            KeyStore ks = KeyStore.getInstance(storeType);
            try (InputStream in = new FileInputStream(keyStorePath)) {
                ks.load(in, password);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("X.509", "BCJSSE");
            kmf.init(ks, password);
            keyManagers = kmf.getKeyManagers();
        }

        TrustManager[] trustManagers = null;
        String trustStorePath = System.getProperty("javax.net.ssl.trustStore");
        if (trustStorePath != null) {
            char[] password = System.getProperty("javax.net.ssl.trustStorePassword", "").toCharArray();
            KeyStore ts = KeyStore.getInstance(storeType);
            try (InputStream in = new FileInputStream(trustStorePath)) {
                ts.load(in, password);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX", "BCJSSE");
            tmf.init(ts);
            trustManagers = tmf.getTrustManagers();
        }

        SSLContext context = SSLContext.getInstance("TLS", "BCJSSE");
        context.init(keyManagers, trustManagers, null);
        return context;
    }
}
