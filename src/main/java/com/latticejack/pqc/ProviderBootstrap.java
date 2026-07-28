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
     * Deliberately a single entry, not a preference list with classical
     * fallback: a handshake that silently falls back to a classical group
     * would look PQC-migrated while not being one - "a disqualifier-level
     * embarrassment" per arm-hackathon-plan.md §8. Restricting to exactly
     * this group means success is unambiguous proof of hybrid negotiation.
     *
     * KNOWN GAP (tracked, not yet resolved): with only this group offered,
     * the handshake currently fails - confirmed via direct debugging that
     * BC's credential/context wiring is otherwise correct (a handshake with
     * BC's full default group list succeeds), so the gap is specific to
     * X25519MLKEM768 negotiation itself. See docs/bouncycastle-pqc-notes.md
     * for the investigation and next steps.
     */
    static final String[] NAMED_GROUPS = {"X25519MLKEM768"};

    static void install() {
        BouncyCastleProvider bc = new BouncyCastleProvider();
        Security.insertProviderAt(bc, 1);
        Security.insertProviderAt(new BouncyCastleJsseProvider(bc), 2);
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
