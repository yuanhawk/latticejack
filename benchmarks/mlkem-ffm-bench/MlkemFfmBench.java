import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.Random;

/**
 * B2 lever 5, closed: calls mlkem-native's real ML-KEM-768 C implementation
 * from Java via the Foreign Function &amp; Memory API (java.lang.foreign) -
 * no JNI, no native-image, works against a regular JVM. This is what
 * benchmarks/mlkem-native-bench/ explicitly did NOT do (that measured the
 * standalone C ceiling only) - the actual integration, with the real FFI
 * crossing cost included, answering "how much of the ~4.3-4.6x ceiling
 * survives once you actually call it from Java."
 *
 * Requires the JDK-21-pinned FFM API's preview flag (finalized in JDK 22,
 * still preview in 21): compile and run with --enable-preview.
 *
 * Requires libmlkem768ffm.{dylib,so} built as a SHARED library from
 * mlkem-native's static archive (mlkem-native's own build only produces
 * .a files) - see README.md "Reproducing" for the exact build command.
 * Links in mlkem-native's own randombytes() TEST double
 * (test/notrandombytes/notrandombytes.c.o) - NOT cryptographically secure,
 * fine for a throughput benchmark that never uses the resulting keys for
 * anything, not appropriate for any real deployment (same disclosed
 * shortcut upstream's own benchmark binary takes).
 */
public class MlkemFfmBench {
    static final int PUBLICKEYBYTES = 1184;
    static final int SECRETKEYBYTES = 2400;
    static final int CIPHERTEXTBYTES = 1088;
    static final int SSBYTES = 32;

    static MethodHandle keypairHandle;
    static MethodHandle encHandle;
    static MethodHandle decHandle;

    public static void main(String[] args) throws Throwable {
        if (args.length < 1) {
            System.err.println("usage: MlkemFfmBench <path-to-libmlkem768ffm.{dylib,so}>");
            System.exit(1);
        }
        String libPath = args[0];

        Linker linker = Linker.nativeLinker();
        Arena globalArena = Arena.ofShared();
        SymbolLookup lib = SymbolLookup.libraryLookup(libPath, globalArena);

        keypairHandle = linker.downcallHandle(
                lib.find("PQCP_MLKEM_NATIVE_MLKEM768_keypair").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        encHandle = linker.downcallHandle(
                lib.find("PQCP_MLKEM_NATIVE_MLKEM768_enc").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        decHandle = linker.downcallHandle(
                lib.find("PQCP_MLKEM_NATIVE_MLKEM768_dec").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        System.out.println("[mlkem-ffm-bench] loaded " + libPath + ", symbols bound");

        testCorrectness();
        System.out.println("[mlkem-ffm-bench] correctness: OK (encaps/decaps shared secrets agree, 50 trials)");

        benchmark();
    }

    static void testCorrectness() throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            for (int trial = 0; trial < 50; trial++) {
                MemorySegment pk = arena.allocate(PUBLICKEYBYTES);
                MemorySegment sk = arena.allocate(SECRETKEYBYTES);
                MemorySegment ct = arena.allocate(CIPHERTEXTBYTES);
                MemorySegment ssEnc = arena.allocate(SSBYTES);
                MemorySegment ssDec = arena.allocate(SSBYTES);

                int rc1 = (int) keypairHandle.invoke(pk, sk);
                int rc2 = (int) encHandle.invoke(ct, ssEnc, pk);
                int rc3 = (int) decHandle.invoke(ssDec, ct, sk);
                if (rc1 != 0 || rc2 != 0 || rc3 != 0) {
                    throw new AssertionError("native call returned nonzero: " + rc1 + "/" + rc2 + "/" + rc3);
                }

                byte[] a = ssEnc.toArray(ValueLayout.JAVA_BYTE);
                byte[] b = ssDec.toArray(ValueLayout.JAVA_BYTE);
                if (!Arrays.equals(a, b)) {
                    throw new AssertionError("shared secret mismatch on trial " + trial);
                }
            }
        }
    }

    static final int WARMUP = 500;
    static final int MEASURED = 5000;

    static void benchmark() throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pk = arena.allocate(PUBLICKEYBYTES);
            MemorySegment sk = arena.allocate(SECRETKEYBYTES);
            MemorySegment ct = arena.allocate(CIPHERTEXTBYTES);
            MemorySegment ss = arena.allocate(SSBYTES);

            for (int i = 0; i < WARMUP; i++) {
                keypairHandle.invoke(pk, sk);
            }
            long[] kgNs = new long[MEASURED];
            for (int i = 0; i < MEASURED; i++) {
                long start = System.nanoTime();
                keypairHandle.invoke(pk, sk);
                kgNs[i] = System.nanoTime() - start;
            }

            for (int i = 0; i < WARMUP; i++) {
                encHandle.invoke(ct, ss, pk);
            }
            long[] encNs = new long[MEASURED];
            for (int i = 0; i < MEASURED; i++) {
                long start = System.nanoTime();
                encHandle.invoke(ct, ss, pk);
                encNs[i] = System.nanoTime() - start;
            }

            for (int i = 0; i < WARMUP; i++) {
                decHandle.invoke(ss, ct, sk);
            }
            long[] decNs = new long[MEASURED];
            for (int i = 0; i < MEASURED; i++) {
                long start = System.nanoTime();
                decHandle.invoke(ss, ct, sk);
                decNs[i] = System.nanoTime() - start;
            }

            Arrays.sort(kgNs);
            Arrays.sort(encNs);
            Arrays.sort(decNs);
            System.out.println();
            System.out.printf("keygen (via FFM) us: p50=%.2f p10=%.2f p90=%.2f%n",
                    kgNs[MEASURED / 2] / 1000.0, kgNs[MEASURED / 10] / 1000.0, kgNs[MEASURED * 9 / 10] / 1000.0);
            System.out.printf("encaps (via FFM) us: p50=%.2f p10=%.2f p90=%.2f%n",
                    encNs[MEASURED / 2] / 1000.0, encNs[MEASURED / 10] / 1000.0, encNs[MEASURED * 9 / 10] / 1000.0);
            System.out.printf("decaps (via FFM) us: p50=%.2f p10=%.2f p90=%.2f%n",
                    decNs[MEASURED / 2] / 1000.0, decNs[MEASURED / 10] / 1000.0, decNs[MEASURED * 9 / 10] / 1000.0);
        }
    }
}
