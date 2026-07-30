package com.latticejack.pqc.nativekem;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;

/**
 * FFM (java.lang.foreign) bindings to pq-code-package/mlkem-native's
 * NEON-optimized C ML-KEM-768 implementation, mirroring the exact symbol
 * names, buffer sizes, and Linker/SymbolLookup/MethodHandle wiring pattern
 * already verified working in benchmarks/mlkem-ffm-bench/MlkemFfmBench.java
 * — see that file for provenance; nothing here reinvents it.
 *
 * The shared library path is NOT hardcoded: it comes from the
 * -Dlatticejack.nativekem.lib=&lt;path&gt; system property, read once in this
 * class's static initializer (so a missing/bad property fails loudly and
 * immediately the first time any Spi in this package is used, rather than
 * silently falling through to BC).
 *
 * RANDOMNESS: this class only binds and calls the {@code _derand} entry
 * points ({@code PQCP_MLKEM_NATIVE_MLKEM768_keypair_derand}/
 * {@code _enc_derand}, confirmed present via `nm` and their exact coin
 * sizes confirmed against vendor/mlkem-native/mlkem/src/kem.h:
 * keypair_derand takes 2*MLKEM_SYMBYTES=64 coin bytes (the CPA keygen seed
 * d and the implicit-rejection value z), enc_derand takes MLKEM_SYMBYTES=32
 * coin bytes) — never the plain {@code keypair}/{@code enc} entry points
 * that call the library's own internal randombytes() and were used here
 * previously. That matters because
 * vendor/mlkem-native/libmlkem768ffm.{dylib,so}'s prebuilt binary links
 * mlkem-native's test-only randombytes() double
 * (test/notrandombytes/notrandombytes.c.o, deterministic, NOT a CSPRNG) —
 * an earlier version of this class called the plain entry points and
 * inherited that determinism directly into real TLS handshake key
 * material, flagged as the most significant finding of an independent
 * Opus audit of this feature (see benchmarks/nativekem-e2e-bench/README.md
 * for the full history). The {@code _derand} functions take their coins as
 * an explicit parameter and never call randombytes() internally at all
 * (confirmed: {@code keypair_derand}'s 2*MLKEM_SYMBYTES coins cover both
 * values the plain path would otherwise have sourced from randombytes()),
 * so which double the shared library happens to link is now irrelevant to
 * this integration's correctness — the callers in this package
 * ({@link NativeMlkemKeyPairGeneratorSpi}, {@link NativeMlkemKemSpi},
 * {@link NativeMlkemKeyGeneratorSpi}) supply real coins from the
 * {@code SecureRandom} the JCA/JCE SPI contract already hands them.
 */
final class NativeMlkem768 {
    static final int PUBLICKEYBYTES = 1184;
    static final int SECRETKEYBYTES = 2400;
    static final int CIPHERTEXTBYTES = 1088;
    static final int SSBYTES = 32;
    static final int KEYPAIR_COINS_BYTES = 64; // 2 * MLKEM_SYMBYTES (seed d + implicit-rejection value z)
    static final int ENC_COINS_BYTES = 32; // MLKEM_SYMBYTES

    private static final boolean TRACE = Boolean.getBoolean("latticejack.nativekem.trace");

    private static final MethodHandle KEYPAIR_DERAND_HANDLE;
    private static final MethodHandle ENC_DERAND_HANDLE;
    private static final MethodHandle DEC_HANDLE;

    static {
        String libPath = System.getProperty("latticejack.nativekem.lib");
        if (libPath == null || libPath.isEmpty()) {
            throw new IllegalStateException(
                    "-Dlatticejack.nativekem.lib=<path to libmlkem768ffm.{dylib,so}> is required "
                            + "when -Dlatticejack.tls.nativekem=true");
        }

        Linker linker = Linker.nativeLinker();
        // Shared, not confined: this Arena only ever backs the three
        // downcall symbol handles below, which are used for the lifetime of
        // the JVM process (a one-shot handshake process here, but the
        // pattern holds for a long-lived server too) - never closed.
        Arena libArena = Arena.ofShared();
        SymbolLookup lib = SymbolLookup.libraryLookup(libPath, libArena);

        KEYPAIR_DERAND_HANDLE = linker.downcallHandle(
                lib.find("PQCP_MLKEM_NATIVE_MLKEM768_keypair_derand").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS));
        ENC_DERAND_HANDLE = linker.downcallHandle(
                lib.find("PQCP_MLKEM_NATIVE_MLKEM768_enc_derand").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        DEC_HANDLE = linker.downcallHandle(
                lib.find("PQCP_MLKEM_NATIVE_MLKEM768_dec").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS));
    }

    private NativeMlkem768() {}

    static final class KeyPair {
        final byte[] publicKey;
        final byte[] secretKey;

        KeyPair(byte[] publicKey, byte[] secretKey) {
            this.publicKey = publicKey;
            this.secretKey = secretKey;
        }
    }

    static final class Encapsulation {
        final byte[] ciphertext;
        final byte[] sharedSecret;

        Encapsulation(byte[] ciphertext, byte[] sharedSecret) {
            this.ciphertext = ciphertext;
            this.sharedSecret = sharedSecret;
        }
    }

    /**
     * @param coins {@link #KEYPAIR_COINS_BYTES} bytes of real randomness
     *              from a {@link java.security.SecureRandom} — callers own
     *              generating it (see the SPIs in this package). Zeroed
     *              before this method returns, on both the success and
     *              failure path, since it's ephemeral single-use secret
     *              material with no reason to linger on the Java heap.
     */
    static KeyPair keypair(byte[] coins) {
        if (coins.length != KEYPAIR_COINS_BYTES) {
            throw new IllegalArgumentException(
                    "expected " + KEYPAIR_COINS_BYTES + " coin bytes, got " + coins.length);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pk = arena.allocate(PUBLICKEYBYTES);
            MemorySegment sk = arena.allocate(SECRETKEYBYTES);
            MemorySegment coinsSeg = arena.allocate(KEYPAIR_COINS_BYTES);
            MemorySegment.copy(coins, 0, coinsSeg, ValueLayout.JAVA_BYTE, 0, KEYPAIR_COINS_BYTES);
            int rc = (int) KEYPAIR_DERAND_HANDLE.invoke(pk, sk, coinsSeg);
            if (rc != 0) {
                throw new IllegalStateException("mlkem-native keypair_derand() returned nonzero rc=" + rc);
            }
            if (TRACE) {
                System.out.println("[native-mlkem-provider] keypair via mlkem-native FFM (derand, real SecureRandom coins)");
            }
            return new KeyPair(pk.toArray(ValueLayout.JAVA_BYTE), sk.toArray(ValueLayout.JAVA_BYTE));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("mlkem-native keypair_derand() call failed", t);
        } finally {
            Arrays.fill(coins, (byte) 0);
        }
    }

    /**
     * @param coins {@link #ENC_COINS_BYTES} bytes of real randomness from a
     *              {@link java.security.SecureRandom} — callers own
     *              generating it. Zeroed before this method returns.
     */
    static Encapsulation encapsulate(byte[] publicKey, byte[] coins) {
        if (publicKey.length != PUBLICKEYBYTES) {
            throw new IllegalArgumentException(
                    "expected a " + PUBLICKEYBYTES + "-byte ML-KEM-768 public key, got " + publicKey.length);
        }
        if (coins.length != ENC_COINS_BYTES) {
            throw new IllegalArgumentException(
                    "expected " + ENC_COINS_BYTES + " coin bytes, got " + coins.length);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pk = arena.allocate(PUBLICKEYBYTES);
            MemorySegment.copy(publicKey, 0, pk, ValueLayout.JAVA_BYTE, 0, PUBLICKEYBYTES);
            MemorySegment ct = arena.allocate(CIPHERTEXTBYTES);
            MemorySegment ss = arena.allocate(SSBYTES);
            MemorySegment coinsSeg = arena.allocate(ENC_COINS_BYTES);
            MemorySegment.copy(coins, 0, coinsSeg, ValueLayout.JAVA_BYTE, 0, ENC_COINS_BYTES);
            int rc = (int) ENC_DERAND_HANDLE.invoke(ct, ss, pk, coinsSeg);
            if (rc != 0) {
                throw new IllegalStateException("mlkem-native enc_derand() returned nonzero rc=" + rc);
            }
            if (TRACE) {
                System.out.println("[native-mlkem-provider] encaps via mlkem-native FFM (derand, real SecureRandom coins)");
            }
            return new Encapsulation(ct.toArray(ValueLayout.JAVA_BYTE), ss.toArray(ValueLayout.JAVA_BYTE));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("mlkem-native enc_derand() call failed", t);
        } finally {
            Arrays.fill(coins, (byte) 0);
        }
    }

    static byte[] decapsulate(byte[] secretKey, byte[] ciphertext) {
        if (secretKey.length != SECRETKEYBYTES) {
            throw new IllegalArgumentException(
                    "expected a " + SECRETKEYBYTES + "-byte ML-KEM-768 secret key, got " + secretKey.length);
        }
        if (ciphertext.length != CIPHERTEXTBYTES) {
            throw new IllegalArgumentException(
                    "expected a " + CIPHERTEXTBYTES + "-byte ML-KEM-768 ciphertext, got " + ciphertext.length);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sk = arena.allocate(SECRETKEYBYTES);
            MemorySegment.copy(secretKey, 0, sk, ValueLayout.JAVA_BYTE, 0, SECRETKEYBYTES);
            MemorySegment ct = arena.allocate(CIPHERTEXTBYTES);
            MemorySegment.copy(ciphertext, 0, ct, ValueLayout.JAVA_BYTE, 0, CIPHERTEXTBYTES);
            MemorySegment ss = arena.allocate(SSBYTES);
            int rc = (int) DEC_HANDLE.invoke(ss, ct, sk);
            if (rc != 0) {
                throw new IllegalStateException("mlkem-native dec() returned nonzero rc=" + rc);
            }
            if (TRACE) {
                System.out.println("[native-mlkem-provider] decaps via mlkem-native FFM");
            }
            return ss.toArray(ValueLayout.JAVA_BYTE);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("mlkem-native dec() call failed", t);
        }
    }
}
