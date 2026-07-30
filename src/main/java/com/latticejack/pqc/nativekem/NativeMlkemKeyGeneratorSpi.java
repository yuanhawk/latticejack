package com.latticejack.pqc.nativekem;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidParameterException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.bouncycastle.jcajce.spec.KEMExtractSpec;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;

/**
 * Registered under {@code KeyGenerator.ML-KEM-768}. This is BC's own
 * KEM-over-KeyGenerator SPI convention, NOT the standard JEP 452
 * javax.crypto.KEM API — org.bouncycastle.tls.crypto.impl.jcajce.KemUtil
 * drives encapsulation/decapsulation by calling
 * {@code KeyGenerator.getInstance("ML-KEM-768")} then
 * {@code init(KEMGenerateSpec | KEMExtractSpec)} then
 * {@code generateKey()} (read via the bctls sources jar to confirm this
 * exact shape).
 *
 * Both spec types request {@code withNoKdf()} (raw shared secret, algorithm
 * name "DEF", 256 bits) — this Spi doesn't need to branch on KDF settings at
 * all, since it only ever sees that one configuration from KemUtil.
 */
public final class NativeMlkemKeyGeneratorSpi extends KeyGeneratorSpi {

    private AlgorithmParameterSpec pending;

    public NativeMlkemKeyGeneratorSpi() {}

    @Override
    protected void engineInit(SecureRandom random) {
        throw new InvalidParameterException(
                "NativeMlkemKeyGeneratorSpi requires a KEMGenerateSpec/KEMExtractSpec, not a bare SecureRandom");
    }

    @Override
    protected void engineInit(AlgorithmParameterSpec params, SecureRandom random)
            throws InvalidAlgorithmParameterException {
        if (!(params instanceof KEMGenerateSpec) && !(params instanceof KEMExtractSpec)) {
            throw new InvalidAlgorithmParameterException(
                    "NativeMlkemKeyGeneratorSpi only supports KEMGenerateSpec/KEMExtractSpec, got: " + params);
        }
        this.pending = params;
    }

    @Override
    protected void engineInit(int keysize, SecureRandom random) {
        throw new InvalidParameterException(
                "NativeMlkemKeyGeneratorSpi requires a KEMGenerateSpec/KEMExtractSpec, not a keysize");
    }

    @Override
    protected SecretKey engineGenerateKey() {
        AlgorithmParameterSpec spec = this.pending;
        if (spec == null) {
            throw new IllegalStateException(
                    "engineInit(AlgorithmParameterSpec, SecureRandom) was not called before engineGenerateKey()");
        }

        if (spec instanceof KEMGenerateSpec generate) {
            if (!(generate.getPublicKey() instanceof NativeMlkemPublicKey pub)) {
                throw new IllegalArgumentException(
                        "expected a NativeMlkemPublicKey (produced by NativeMlkemKeyPairGeneratorSpi or "
                                + "NativeMlkemKeyFactorySpi), got: " + generate.getPublicKey());
            }
            NativeMlkem768.Encapsulation enc = NativeMlkem768.encapsulate(pub.getRawKey());
            SecretKey shared = new SecretKeySpec(enc.sharedSecret, "DEF");
            return new SecretKeyWithEncapsulation(shared, enc.ciphertext);
        }

        if (spec instanceof KEMExtractSpec extract) {
            if (!(extract.getPrivateKey() instanceof NativeMlkemPrivateKey priv)) {
                throw new IllegalArgumentException(
                        "expected a NativeMlkemPrivateKey (produced by NativeMlkemKeyPairGeneratorSpi), got: "
                                + extract.getPrivateKey());
            }
            byte[] ciphertext = extract.getEncapsulation();
            byte[] sharedSecret = NativeMlkem768.decapsulate(priv.getRawKey(), ciphertext);
            SecretKey shared = new SecretKeySpec(sharedSecret, "DEF");
            return new SecretKeyWithEncapsulation(shared, ciphertext);
        }

        throw new IllegalStateException("unreachable: unrecognized AlgorithmParameterSpec " + spec);
    }
}
