package com.latticejack.pqc.nativekem;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;

import javax.crypto.DecapsulateException;
import javax.crypto.KEM;
import javax.crypto.KEMSpi;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Registered under {@code KEM.ML-KEM-768}.
 *
 * This is the SPI that actually drives encapsulation/decapsulation at
 * runtime on this project's pinned JDK 21 (scripts/require-jdk21.sh) —
 * NOT {@link NativeMlkemKeyGeneratorSpi}'s BC-specific
 * KeyGenerator+KEMGenerateSpec/KEMExtractSpec convention.
 *
 * Confirmed by reading the ACTUAL class bytes BC resolves at runtime, not
 * just the base sources: bctls-jdk18on-1.85 is a multi-release jar.
 * {@code org.bouncycastle.tls.crypto.impl.jcajce.KemUtil}'s base
 * (META-INF-root) version matches the KeyGenerator/KEMGenerateSpec
 * convention this package's sibling classes were originally built around —
 * but that version is only what a JDK 8-16 runtime sees. On JDK 17+ (this
 * project's JDK 21), the jar's {@code META-INF/versions/17/.../KemUtil.java}
 * override applies instead (standard JPMS multi-release-jar resolution),
 * and its {@code encapsulate}/{@code decapsulate}/{@code generateKeyPair}
 * branch on {@code org.bouncycastle.jcajce.util.SpiUtil.hasKEM()} — which
 * itself is a multi-release class in bcprov, whose own v17 override checks
 * for the real presence of {@code javax.crypto.KEMSpi} (available since
 * JDK 21, JEP 452) and returns true. With that true, KemUtil routes through
 * {@code KEMSpiUtil}, which calls the standard
 * {@code javax.crypto.KEM.getInstance(kemName)} — i.e. JCA service
 * {@code KEM.ML-KEM-768} — NOT {@code KeyGenerator.ML-KEM-768}. This was
 * confirmed empirically too: without this class, the handshake failed with
 * "java.security.InvalidKeyException: unsupported key type" thrown from
 * BC's OWN {@code MLKEMSpi.engineNewEncapsulator} (i.e. plain JCA
 * provider-precedence search for the {@code KEM} service type resolved to
 * BC, since this provider hadn't offered that service — the KeyGenerator
 * service this package originally registered was simply never consulted).
 *
 * {@link NativeMlkemKeyGeneratorSpi} is left in place (registered under
 * {@code KeyGenerator.ML-KEM-768}) for portability to a hypothetical
 * pre-JDK-17 runtime that would take KemUtil's base-jar-version path
 * instead — harmless dead code on this project's actual JDK 21, exercised
 * by neither the microbenchmark nor a real handshake here, but not
 * incorrect either since it implements the exact same underlying
 * mlkem-native calls via {@link NativeMlkem768}.
 */
public final class NativeMlkemKemSpi implements KEMSpi {

    public NativeMlkemKemSpi() {}

    @Override
    public EncapsulatorSpi engineNewEncapsulator(PublicKey publicKey, AlgorithmParameterSpec spec,
            SecureRandom secureRandom) throws InvalidAlgorithmParameterException, InvalidKeyException {
        if (spec != null) {
            throw new InvalidAlgorithmParameterException(
                    "NativeMlkemKemSpi does not support an AlgorithmParameterSpec, got: " + spec);
        }
        if (!(publicKey instanceof NativeMlkemPublicKey pub)) {
            throw new InvalidKeyException(
                    "expected a NativeMlkemPublicKey (produced by NativeMlkemKeyPairGeneratorSpi or "
                            + "NativeMlkemKeyFactorySpi), got: " + publicKey);
        }
        // Real randomness for encapsulation coins - see NativeMlkem768's
        // Javadoc for why this must be a genuine SecureRandom. JEP 452's
        // KEM.newEncapsulator(PublicKey) (no explicit SecureRandom) is
        // documented to supply a default SecureRandom to the SPI rather
        // than passing null, but fall back to a fresh one defensively
        // rather than NPE if some caller ever does pass null.
        SecureRandom effectiveRandom = secureRandom != null ? secureRandom : new SecureRandom();
        return new Encapsulator(pub, effectiveRandom);
    }

    @Override
    public DecapsulatorSpi engineNewDecapsulator(PrivateKey privateKey, AlgorithmParameterSpec spec)
            throws InvalidAlgorithmParameterException, InvalidKeyException {
        if (spec != null) {
            throw new InvalidAlgorithmParameterException(
                    "NativeMlkemKemSpi does not support an AlgorithmParameterSpec, got: " + spec);
        }
        if (!(privateKey instanceof NativeMlkemPrivateKey priv)) {
            throw new InvalidKeyException(
                    "expected a NativeMlkemPrivateKey (produced by NativeMlkemKeyPairGeneratorSpi), got: "
                            + privateKey);
        }
        return new Decapsulator(priv);
    }

    private static final class Encapsulator implements EncapsulatorSpi {
        private final NativeMlkemPublicKey publicKey;
        private final SecureRandom random;

        Encapsulator(NativeMlkemPublicKey publicKey, SecureRandom random) {
            this.publicKey = publicKey;
            this.random = random;
        }

        @Override
        public KEM.Encapsulated engineEncapsulate(int from, int to, String algorithm) {
            byte[] coins = new byte[NativeMlkem768.ENC_COINS_BYTES];
            random.nextBytes(coins);
            NativeMlkem768.Encapsulation enc = NativeMlkem768.encapsulate(publicKey.getRawKey(), coins);
            byte[] secretSlice = Arrays.copyOfRange(enc.sharedSecret, from, to);
            SecretKey key = new SecretKeySpec(secretSlice, algorithm != null ? algorithm : "Generic");
            return new KEM.Encapsulated(key, enc.ciphertext, null);
        }

        @Override
        public int engineSecretSize() {
            return NativeMlkem768.SSBYTES;
        }

        @Override
        public int engineEncapsulationSize() {
            return NativeMlkem768.CIPHERTEXTBYTES;
        }
    }

    private static final class Decapsulator implements DecapsulatorSpi {
        private final NativeMlkemPrivateKey privateKey;

        Decapsulator(NativeMlkemPrivateKey privateKey) {
            this.privateKey = privateKey;
        }

        @Override
        public SecretKey engineDecapsulate(byte[] encapsulation, int from, int to, String algorithm)
                throws DecapsulateException {
            if (encapsulation.length != NativeMlkem768.CIPHERTEXTBYTES) {
                throw new DecapsulateException("expected a " + NativeMlkem768.CIPHERTEXTBYTES
                        + "-byte ML-KEM-768 ciphertext, got " + encapsulation.length);
            }
            byte[] sharedSecret = NativeMlkem768.decapsulate(privateKey.getRawKey(), encapsulation);
            byte[] secretSlice = Arrays.copyOfRange(sharedSecret, from, to);
            return new SecretKeySpec(secretSlice, algorithm != null ? algorithm : "Generic");
        }

        @Override
        public int engineSecretSize() {
            return NativeMlkem768.SSBYTES;
        }

        @Override
        public int engineEncapsulationSize() {
            return NativeMlkem768.CIPHERTEXTBYTES;
        }
    }
}
