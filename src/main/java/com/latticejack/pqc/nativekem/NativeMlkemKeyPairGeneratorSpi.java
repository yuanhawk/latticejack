package com.latticejack.pqc.nativekem;

import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGeneratorSpi;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.NamedParameterSpec;

import org.bouncycastle.jcajce.spec.MLKEMParameterSpec;

/**
 * Registered under BOTH {@code KeyPairGenerator.ML-KEM-768} (the specific
 * name) and {@code KeyPairGenerator.ML-KEM} (the generic name) — which one
 * actually matters depends on which version of a multi-release
 * bctls-jdk18on-1.85 jar the running JDK resolves (see
 * {@link NativeMlkemKemSpi}'s Javadoc for the full mechanism, confirmed by
 * reading the ACTUAL class bytes for both the base and META-INF/versions/17
 * overrides of {@code KemUtil}/{@code KEMSpiUtil}, not just the base
 * sources):
 * <ul>
 * <li>On this project's pinned JDK 21 (scripts/require-jdk21.sh), BC's own
 * {@code SpiUtil.hasKEM()} is true, so
 * {@code KEMSpiUtil.generateKeyPair} runs, which calls
 * {@code createKeyPairGenerator("ML-KEM-768")} (the SPECIFIC name) then
 * {@code initialize(new NamedParameterSpec("ML-KEM-768"), random)} — the
 * standard {@link NamedParameterSpec}, NOT BC's own
 * {@link MLKEMParameterSpec}. This is the path actually exercised at
 * runtime here (confirmed empirically: without the "ML-KEM-768"-keyed
 * registration and NamedParameterSpec support added here, the handshake
 * failed).</li>
 * <li>The generic-name registration + {@link MLKEMParameterSpec} handling
 * is kept for portability to a hypothetical pre-JDK-17 runtime that would
 * take KemUtil's base-jar-version path
 * ({@code createKeyPairGenerator(crypto, kemName)} always requests the
 * generic "ML-KEM" then initializes with
 * {@code MLKEMParameterSpec.fromName(kemName)}) — harmless dead code on
 * this project's actual JDK 21.</li>
 * </ul>
 * Either way, {@link #generateKeyPair()} is unconditionally ML-KEM-768
 * regardless of which initialize() overload/spec type was used to get here
 * — this provider doesn't wire ML-KEM-512/1024.
 */
public final class NativeMlkemKeyPairGeneratorSpi extends KeyPairGeneratorSpi {

    public NativeMlkemKeyPairGeneratorSpi() {}

    @Override
    public void initialize(int keysize, SecureRandom random) {
        // Neither KemUtil codepath calls this overload (both always supply
        // an AlgorithmParameterSpec via the other initialize()) — nothing
        // to do; generateKeyPair() below is unconditionally ML-KEM-768
        // regardless.
    }

    @Override
    public void initialize(AlgorithmParameterSpec params, SecureRandom random)
            throws InvalidAlgorithmParameterException {
        if (params instanceof NamedParameterSpec named && "ML-KEM-768".equalsIgnoreCase(named.getName())) {
            return;
        }
        if (params instanceof MLKEMParameterSpec spec && "ML-KEM-768".equalsIgnoreCase(spec.getName())) {
            return;
        }
        throw new InvalidAlgorithmParameterException(
                "NativeMlkemKeyPairGeneratorSpi only supports NamedParameterSpec/MLKEMParameterSpec for "
                        + "ML-KEM-768, got: " + params);
    }

    @Override
    public KeyPair generateKeyPair() {
        NativeMlkem768.KeyPair kp = NativeMlkem768.keypair();
        return new KeyPair(new NativeMlkemPublicKey(kp.publicKey), new NativeMlkemPrivateKey(kp.secretKey));
    }
}
