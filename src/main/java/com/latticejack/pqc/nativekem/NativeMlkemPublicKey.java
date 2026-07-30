package com.latticejack.pqc.nativekem;

import java.io.IOException;
import java.security.PublicKey;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.util.Arrays;

/**
 * Minimal {@link PublicKey} wrapper around a raw ML-KEM-768 public key
 * (1184 bytes).
 *
 * getEncoded()/getFormat() intentionally round-trip through
 * KemUtil.encodePublicKey's GENERIC fallback path ("X.509".equals(format)
 * then SubjectPublicKeyInfo.getInstance(getEncoded()).getPublicKeyData()
 * .getOctets()) rather than BC's own MLKEMPublicKey-specific fast path:
 * this key is never an instanceof
 * org.bouncycastle.jcajce.interfaces.MLKEMPublicKey, so BC's fast path
 * never triggers for it, by construction — see
 * org.bouncycastle.tls.crypto.impl.jcajce.KemUtil (read via the bctls
 * sources jar) for the exact two-path contract this must satisfy.
 */
public final class NativeMlkemPublicKey implements PublicKey {
    private static final long serialVersionUID = 1L;

    private final byte[] rawKey;

    public NativeMlkemPublicKey(byte[] rawKey) {
        this.rawKey = Arrays.clone(rawKey);
    }

    /** The raw (non-DER) 1184-byte ML-KEM-768 public key, for native calls. */
    byte[] getRawKey() {
        return rawKey;
    }

    @Override
    public String getAlgorithm() {
        return "ML-KEM-768";
    }

    @Override
    public String getFormat() {
        return "X.509";
    }

    @Override
    public byte[] getEncoded() {
        try {
            AlgorithmIdentifier algId = new AlgorithmIdentifier(NISTObjectIdentifiers.id_alg_ml_kem_768);
            SubjectPublicKeyInfo spki = new SubjectPublicKeyInfo(algId, rawKey);
            return spki.getEncoded(ASN1Encoding.DER);
        } catch (IOException e) {
            throw new IllegalStateException("unable to DER-encode ML-KEM-768 SubjectPublicKeyInfo", e);
        }
    }
}
