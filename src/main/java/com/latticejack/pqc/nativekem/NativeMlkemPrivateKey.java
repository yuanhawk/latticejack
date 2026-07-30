package com.latticejack.pqc.nativekem;

import java.security.PrivateKey;

import org.bouncycastle.util.Arrays;

/**
 * Minimal {@link PrivateKey} wrapper around a raw ML-KEM-768 secret key
 * (2400 bytes).
 *
 * Never serialized: this key is only ever created by
 * {@link NativeMlkemKeyPairGeneratorSpi#generateKeyPair()} and consumed
 * directly, in-process, by {@link NativeMlkemKeyGeneratorSpi} (the
 * KEMExtractSpec/decapsulate path) within the same ephemeral handshake — so
 * getEncoded()/getFormat() intentionally return {@code null}, the
 * {@link PrivateKey} javadoc convention for a key whose encoding is not
 * available, rather than exposing raw key material through the standard
 * encoding API.
 */
public final class NativeMlkemPrivateKey implements PrivateKey {
    private static final long serialVersionUID = 1L;

    private final byte[] rawKey;

    public NativeMlkemPrivateKey(byte[] rawKey) {
        this.rawKey = Arrays.clone(rawKey);
    }

    /** The raw 2400-byte ML-KEM-768 secret key, for native calls. */
    byte[] getRawKey() {
        return rawKey;
    }

    @Override
    public String getAlgorithm() {
        return "ML-KEM-768";
    }

    @Override
    public String getFormat() {
        return null;
    }

    @Override
    public byte[] getEncoded() {
        return null;
    }
}
