import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;

/**
 * B2 lever 7 (exploratory): same FFM integration as
 * benchmarks/mlkem-ffm-bench/ (B2 lever 5), but calling RustCrypto's
 * `ml-kem` crate (vendor/mlkem-rust-ffm/) instead of mlkem-native's C -
 * prompted directly by a question about whether Rust's memory safety
 * comes at a performance cost relative to mlkem-native's hand-tuned,
 * formally verified AArch64 assembly. Structurally identical to
 * MlkemFfmBench.java (same argument order, same correctness check, same
 * benchmark methodology) - only the library path and exported symbol
 * names differ (`mlkem_rust_*` here vs `PQCP_MLKEM_NATIVE_MLKEM768_*`
 * there), for a direct, apples-to-apples comparison.
 *
 * Requires the JDK-21-pinned FFM API's preview flag: --enable-preview.
 */
public class MlkemRustFfmBench {
    static final int PUBLICKEYBYTES = 1184;
    static final int SECRETKEYBYTES = 2400;
    static final int CIPHERTEXTBYTES = 1088;
    static final int SSBYTES = 32;

    static MethodHandle keypairHandle;
    static MethodHandle encHandle;
    static MethodHandle decHandle;

    public static void main(String[] args) throws Throwable {
        if (args.length < 1) {
            System.err.println("usage: MlkemRustFfmBench <path-to-libmlkem_rust_ffm.{dylib,so}>");
            System.exit(1);
        }
        String libPath = args[0];

        Linker linker = Linker.nativeLinker();
        Arena globalArena = Arena.ofShared();
        SymbolLookup lib = SymbolLookup.libraryLookup(libPath, globalArena);

        keypairHandle = linker.downcallHandle(
                lib.find("mlkem_rust_keypair").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        encHandle = linker.downcallHandle(
                lib.find("mlkem_rust_enc").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        decHandle = linker.downcallHandle(
                lib.find("mlkem_rust_dec").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        System.out.println("[mlkem-rust-ffm-bench] loaded " + libPath + ", symbols bound");

        testCorrectness();
        System.out.println("[mlkem-rust-ffm-bench] correctness: OK (encaps/decaps shared secrets agree, 50 trials)");

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
