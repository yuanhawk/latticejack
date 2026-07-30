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

    // Real randomness for keygen coins - see NativeMlkem768's Javadoc for
    // why this must be a genuine SecureRandom, not the library's own
    // internal (test-only, deterministic) randombytes(). Defaults to a
    // fresh SecureRandom if generateKeyPair() is ever called without
    // initialize() having run first (shouldn't happen given KemUtil's own
    // call sequence, but matches standard JCA SPI convention of not NPEing
    // on a skipped initialize()).
    private SecureRandom random = new SecureRandom();

    public NativeMlkemKeyPairGeneratorSpi() {}

    @Override
    public void initialize(int keysize, SecureRandom random) {
        // Neither KemUtil codepath calls this overload (both always supply
        // an AlgorithmParameterSpec via the other initialize()) - but still
        // honor the SecureRandom if given one, for defensiveness.
        if (random != null) {
            this.random = random;
        }
    }

    @Override
    public void initialize(AlgorithmParameterSpec params, SecureRandom random)
            throws InvalidAlgorithmParameterException {
        if (params instanceof NamedParameterSpec named && "ML-KEM-768".equalsIgnoreCase(named.getName())) {
            // fall through to the random assignment below
        } else if (params instanceof MLKEMParameterSpec spec && "ML-KEM-768".equalsIgnoreCase(spec.getName())) {
            // fall through to the random assignment below
        } else {
            throw new InvalidAlgorithmParameterException(
                    "NativeMlkemKeyPairGeneratorSpi only supports NamedParameterSpec/MLKEMParameterSpec for "
                            + "ML-KEM-768, got: " + params);
        }
        if (random != null) {
            this.random = random;
        }
    }

    @Override
    public KeyPair generateKeyPair() {
        byte[] coins = new byte[NativeMlkem768.KEYPAIR_COINS_BYTES];
        random.nextBytes(coins);
        NativeMlkem768.KeyPair kp = NativeMlkem768.keypair(coins);
        return new KeyPair(new NativeMlkemPublicKey(kp.publicKey), new NativeMlkemPrivateKey(kp.secretKey));
    }
}
