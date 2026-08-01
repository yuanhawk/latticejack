package com.latticejack.pqc.nativekem;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Encode/decode round-trip for the X.509 SubjectPublicKeyInfo contract
 * {@code NativeMlkemPublicKey}/{@code NativeMlkemKeyFactorySpi} must
 * satisfy for org.bouncycastle.tls.crypto.impl.jcajce.KemUtil's generic
 * fallback path (see both classes' Javadoc) — the whole native-KEM
 * handshake silently depends on this round-trip being exact, and until
 * this test, it was only ever exercised transitively inside a full BCJSSE
 * handshake, where a mismatch would have surfaced as an opaque "no
 * selectable cipher suite" rather than a clear assertion failure.
 *
 * Deliberately requires no native library and no
 * -Dlatticejack.nativekem.lib system property: this exercises only
 * {@link NativeMlkemPublicKey} and {@link NativeMlkemKeyFactorySpi}, never
 * {@link NativeMlkem768}, so it runs on any machine, unlike the FFM-backed
 * classes in this package.
 */
class NativeMlkemPublicKeyRoundTripTest {

    private static final int PUBLICKEYBYTES = 1184; // NativeMlkem768.PUBLICKEYBYTES, duplicated to avoid loading that class (see above)

    @Test
    void encodedPublicKeyDecodesBackToTheSameRawBytes() throws Exception {
        byte[] raw = randomBytes(PUBLICKEYBYTES, 42);
        NativeMlkemPublicKey original = new NativeMlkemPublicKey(raw);

        byte[] encoded = original.getEncoded();
        assertEquals("X.509", original.getFormat());

        NativeMlkemKeyFactorySpi factory = new NativeMlkemKeyFactorySpi();
        NativeMlkemPublicKey decoded =
                (NativeMlkemPublicKey) factory.engineGeneratePublic(new X509EncodedKeySpec(encoded));

        assertArrayEquals(raw, decoded.getRawKey());
        assertEquals("ML-KEM-768", decoded.getAlgorithm());
    }

    @Test
    void twoDifferentKeysProduceDifferentEncodings() {
        byte[] rawA = randomBytes(PUBLICKEYBYTES, 1);
        byte[] rawB = randomBytes(PUBLICKEYBYTES, 2);

        byte[] encodedA = new NativeMlkemPublicKey(rawA).getEncoded();
        byte[] encodedB = new NativeMlkemPublicKey(rawB).getEncoded();

        assertThrows(AssertionError.class, () -> assertArrayEquals(encodedA, encodedB));
    }

    @Test
    void rejectsAKeySpecThatIsNotX509Encoded() {
        NativeMlkemKeyFactorySpi factory = new NativeMlkemKeyFactorySpi();
        assertThrows(InvalidKeySpecException.class,
                () -> factory.engineGeneratePublic(new java.security.spec.PKCS8EncodedKeySpec(new byte[] {1, 2, 3})));
    }

    @Test
    void rejectsGarbageDerThatIsNotAValidSubjectPublicKeyInfo() {
        NativeMlkemKeyFactorySpi factory = new NativeMlkemKeyFactorySpi();
        byte[] garbage = randomBytes(64, 7);
        assertThrows(InvalidKeySpecException.class,
                () -> factory.engineGeneratePublic(new X509EncodedKeySpec(garbage)));
    }

    private static byte[] randomBytes(int len, long seed) {
        byte[] b = new byte[len];
        new Random(seed).nextBytes(b);
        return b;
    }
}
