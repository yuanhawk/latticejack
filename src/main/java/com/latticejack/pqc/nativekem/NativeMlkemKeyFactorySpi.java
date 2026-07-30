package com.latticejack.pqc.nativekem;

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactorySpi;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.X509EncodedKeySpec;

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;

/**
 * Registered under {@code KeyFactory.ML-KEM-768}.
 *
 * Scope is deliberately narrow, matching what
 * org.bouncycastle.tls.crypto.impl.jcajce.KemUtil.decodePublicKey actually
 * exercises for a non-BC provider (read via the bctls sources jar to
 * confirm): its BC-specific fast path
 * (instanceof BouncyCastleProvider / MLKEMPublicKeySpec) never triggers for
 * this provider, so it always falls through to the generic path, which only
 * ever calls {@link #engineGeneratePublic(KeySpec)} with an
 * {@link X509EncodedKeySpec}. Private keys are never serialized in this
 * codepath (ephemeral, one handshake, passed directly in-process — see
 * {@link NativeMlkemPrivateKey}), so engineGeneratePrivate/engineGetKeySpec
 * are intentionally minimal/throwing rather than fully implemented.
 */
public final class NativeMlkemKeyFactorySpi extends KeyFactorySpi {

    public NativeMlkemKeyFactorySpi() {}

    @Override
    protected PublicKey engineGeneratePublic(KeySpec keySpec) throws InvalidKeySpecException {
        if (!(keySpec instanceof X509EncodedKeySpec x509Spec)) {
            throw new InvalidKeySpecException(
                    "NativeMlkemKeyFactorySpi only supports X509EncodedKeySpec, got: " + keySpec);
        }
        try {
            SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(x509Spec.getEncoded());
            byte[] rawKey = spki.getPublicKeyData().getOctets();
            return new NativeMlkemPublicKey(rawKey);
        } catch (RuntimeException e) {
            throw new InvalidKeySpecException("unable to decode X.509 ML-KEM-768 SubjectPublicKeyInfo", e);
        }
    }

    @Override
    protected PrivateKey engineGeneratePrivate(KeySpec keySpec) throws InvalidKeySpecException {
        // Unreachable in the TLS handshake codepath this provider targets —
        // see class Javadoc.
        throw new InvalidKeySpecException(
                "NativeMlkemKeyFactorySpi does not support reconstructing private keys from a KeySpec");
    }

    @Override
    protected <T extends KeySpec> T engineGetKeySpec(Key key, Class<T> keySpec) throws InvalidKeySpecException {
        throw new InvalidKeySpecException("NativeMlkemKeyFactorySpi does not support engineGetKeySpec");
    }

    @Override
    protected Key engineTranslateKey(Key key) throws InvalidKeyException {
        if (key instanceof NativeMlkemPublicKey || key instanceof NativeMlkemPrivateKey) {
            return key;
        }
        throw new InvalidKeyException("NativeMlkemKeyFactorySpi cannot translate key: " + key);
    }
}
