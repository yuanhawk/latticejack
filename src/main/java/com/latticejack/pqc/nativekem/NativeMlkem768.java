package com.latticejack.pqc.nativekem;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

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
 * SECURITY NOTE (carried over from MlkemFfmBench's own Javadoc, still true
 * here): the prebuilt vendor/mlkem-native/libmlkem768ffm.dylib this project
 * ships links mlkem-native's own randombytes() TEST double
 * (test/notrandombytes/notrandombytes.c.o), NOT a cryptographically secure
 * RNG. That is an accepted shortcut for a hackathon reference/benchmark
 * build — verified here via run-nativekem.sh's end-to-end handshake and
 * trace-marker check — but this specific .dylib must never be pointed at
 * from a real deployment; a production build of libmlkem768ffm would need
 * to link a real CSPRNG's randombytes() instead. A cleaner alternative to
 * relinking the library: the shipped build also exports
 * PQCP_MLKEM_NATIVE_MLKEM768_{keypair,enc}_derand (confirmed present via
 * `nm`), which take explicit caller-supplied coins instead of calling
 * randombytes() internally — wiring those to the SecureRandom this
 * package's SPIs already receive (and currently ignore) would let Java
 * supply the randomness directly, no relink required. Not done here.
 */
final class NativeMlkem768 {
    static final int PUBLICKEYBYTES = 1184;
    static final int SECRETKEYBYTES = 2400;
    static final int CIPHERTEXTBYTES = 1088;
    static final int SSBYTES = 32;

    private static final boolean TRACE = Boolean.getBoolean("latticejack.nativekem.trace");

    private static final MethodHandle KEYPAIR_HANDLE;
    private static final MethodHandle ENC_HANDLE;
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

        KEYPAIR_HANDLE = linker.downcallHandle(
                lib.find("PQCP_MLKEM_NATIVE_MLKEM768_keypair").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        ENC_HANDLE = linker.downcallHandle(
                lib.find("PQCP_MLKEM_NATIVE_MLKEM768_enc").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS));
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

    static KeyPair keypair() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pk = arena.allocate(PUBLICKEYBYTES);
            MemorySegment sk = arena.allocate(SECRETKEYBYTES);
            int rc = (int) KEYPAIR_HANDLE.invoke(pk, sk);
            if (rc != 0) {
                throw new IllegalStateException("mlkem-native keypair() returned nonzero rc=" + rc);
            }
            if (TRACE) {
                System.out.println("[native-mlkem-provider] keypair via mlkem-native FFM");
            }
            return new KeyPair(pk.toArray(ValueLayout.JAVA_BYTE), sk.toArray(ValueLayout.JAVA_BYTE));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("mlkem-native keypair() call failed", t);
        }
    }

    static Encapsulation encapsulate(byte[] publicKey) {
        if (publicKey.length != PUBLICKEYBYTES) {
            throw new IllegalArgumentException(
                    "expected a " + PUBLICKEYBYTES + "-byte ML-KEM-768 public key, got " + publicKey.length);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pk = arena.allocate(PUBLICKEYBYTES);
            MemorySegment.copy(publicKey, 0, pk, ValueLayout.JAVA_BYTE, 0, PUBLICKEYBYTES);
            MemorySegment ct = arena.allocate(CIPHERTEXTBYTES);
            MemorySegment ss = arena.allocate(SSBYTES);
            int rc = (int) ENC_HANDLE.invoke(ct, ss, pk);
            if (rc != 0) {
                throw new IllegalStateException("mlkem-native enc() returned nonzero rc=" + rc);
            }
            if (TRACE) {
                System.out.println("[native-mlkem-provider] encaps via mlkem-native FFM");
            }
            return new Encapsulation(ct.toArray(ValueLayout.JAVA_BYTE), ss.toArray(ValueLayout.JAVA_BYTE));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("mlkem-native enc() call failed", t);
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
