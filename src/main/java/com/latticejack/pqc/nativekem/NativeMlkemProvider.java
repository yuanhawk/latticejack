package com.latticejack.pqc.nativekem;

import java.security.Provider;

/**
 * A {@link Provider} that routes ML-KEM-768 key generation, encapsulation,
 * and decapsulation through mlkem-native's NEON-optimized C implementation
 * (via {@link NativeMlkem768}'s FFM bindings) instead of BouncyCastle's own
 * (pure-Java) ML-KEM.
 *
 * Registers the JCA service/algorithm names
 * org.bouncycastle.tls.crypto.impl.jcajce.KemUtil resolves during a TLS
 * handshake — confirmed by reading that class's ACTUAL runtime source, not
 * just its base sources: bctls-jdk18on-1.85 is a multi-release jar, and its
 * {@code META-INF/versions/17/.../KemUtil.java} override (which this
 * project's pinned JDK 21 resolves, per standard MRJAR rules) branches on
 * {@code SpiUtil.hasKEM()} — itself a multi-release class in bcprov whose
 * v17 override probes for the real {@code javax.crypto.KEMSpi} (present
 * since JDK 21 / JEP 452) and returns true. So the names actually resolved
 * at runtime here are {@code KeyPairGenerator.ML-KEM-768} (the SPECIFIC
 * name — not the base-version jar's generic "ML-KEM") and
 * {@code KEM.ML-KEM-768} (the standard JEP 452 service, NOT
 * {@code KeyGenerator.ML-KEM-768}) — see {@link NativeMlkemKemSpi}'s
 * Javadoc for the full trace, including the empirical confirmation (an
 * {@code InvalidKeyException: unsupported key type} thrown from BC's own
 * {@code MLKEMSpi} when this provider only offered the base-version
 * service names). {@code KeyFactory.ML-KEM-768} is unchanged between both
 * jar versions and still needed either way. The generic
 * {@code KeyPairGenerator.ML-KEM} and {@code KeyGenerator.ML-KEM-768}
 * services are also still registered, for portability to a hypothetical
 * pre-JDK-17 runtime that would take the base-version codepath — inert on
 * this project's actual JDK 21.
 *
 * BC's own "BC" provider stays registered for every other algorithm
 * (X25519, ECDSA, AES-GCM, SHA-2, HKDF, etc.) — this provider intentionally
 * offers nothing else.
 *
 * Installing this provider alone does nothing: BCJSSE's TLS crypto backend
 * only resolves ML-KEM services through ordinary JCA provider-precedence
 * search (KeyPairGenerator.getInstance(alg) / KEM.getInstance(alg), no
 * explicit provider argument) when {@code BouncyCastleJsseProvider} is
 * constructed with its no-arg constructor — the pinned single-arg
 * {@code BouncyCastleJsseProvider(bc)} constructor bypasses provider search
 * entirely and can never see this class. See
 * {@code ProviderBootstrap.install()}, which switches constructors under
 * {@code -Dlatticejack.tls.nativekem=true}, and this provider must
 * additionally be inserted AHEAD of "BC" in the provider list (lower index
 * = higher priority) for that search to actually prefer it.
 */
public final class NativeMlkemProvider extends Provider {
    private static final long serialVersionUID = 1L;

    public static final String NAME = "LatticejackNativeMLKEM";

    public NativeMlkemProvider() {
        super(NAME, "0.1", "Latticejack native mlkem-native (NEON) ML-KEM-768 JCA bridge, via FFM");

        // Specific-name KeyPairGenerator + KEM: what this project's pinned
        // JDK 21 actually resolves via BC's META-INF/versions/17 KemUtil
        // override (SpiUtil.hasKEM() == true) - see class Javadoc.
        putService(new Service(this, "KeyPairGenerator", "ML-KEM-768",
                NativeMlkemKeyPairGeneratorSpi.class.getName(), null, null));
        putService(new Service(this, "KEM", "ML-KEM-768",
                NativeMlkemKemSpi.class.getName(), null, null));

        // Generic-name KeyPairGenerator + KeyGenerator: what KemUtil's
        // base-jar-version (pre-JDK-17) codepath would resolve instead -
        // inert on this project's actual JDK 21, kept for portability.
        putService(new Service(this, "KeyPairGenerator", "ML-KEM",
                NativeMlkemKeyPairGeneratorSpi.class.getName(), null, null));
        putService(new Service(this, "KeyGenerator", "ML-KEM-768",
                NativeMlkemKeyGeneratorSpi.class.getName(), null, null));

        // Unchanged between both jar versions - always needed.
        putService(new Service(this, "KeyFactory", "ML-KEM-768",
                NativeMlkemKeyFactorySpi.class.getName(), null, null));
    }
}
