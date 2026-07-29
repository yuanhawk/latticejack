import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.util.Arrays;
import java.util.Random;

/**
 * B2 lever 6 (exploratory): does Java's Vector API (jdk.incubator.vector,
 * pure Java - no JNI/FFM, no native-image) narrow the gap between BC/JDK25's
 * scalar ML-KEM and mlkem-native's hand-tuned NEON assembly, for the
 * NTT-shaped part of the cost?
 *
 * NOT a byte-exact reimplementation of FIPS 203's ML-KEM NTT: that transform
 * is "incomplete" (7 layers, not 8) specifically because 512 does not divide
 * Q-1 for Q=3329, and getting its exact zeta ordering right from scratch is
 * real cryptographic engineering with real correctness risk this benchmark
 * doesn't need to take on. Instead this implements a standard, complete,
 * textbook radix-2 Cooley-Tukey NTT over Q=12289 (the classic NTT-friendly
 * prime used by NewHope/early Kyber - 12288 = Q-1 is divisible by 512, so a
 * full negacyclic-capable transform exists) - same size (256), same
 * computational shape (modular multiply-add butterflies over an array), same
 * asymptotic cost, just not ML-KEM's literal field. This tests the
 * *mechanism* (does Vector API give real SIMD speedup for this kind of
 * workload on this hardware), not a drop-in ML-KEM replacement.
 *
 * Further conservative simplifications, disclosed rather than hidden:
 * - Uses long[] arrays throughout (not int[]/short[]), even though
 *   coefficients fit in 14 bits, to avoid int-to-long widening-conversion
 *   calls in the Vector API surface (fewer novel API points to get wrong).
 *   This "wastes" SIMD width relative to a tuned 16-or-32-bit
 *   implementation - LongVector on 128-bit NEON gets 2 lanes, vs. 8 for a
 *   short-lane implementation.
 * - Barrett reduction (not raw `%`) is used throughout, including in the
 *   scalar baseline, so the comparison isolates "vectorized vs not," not
 *   "division vs multiplication" - NEON has no vector integer divide, so a
 *   naive `%`-based vector implementation wouldn't actually vectorize the
 *   reduction step at all, understating what real optimization work would
 *   achieve.
 * - Not constant-time. This is a throughput benchmark, not production code.
 *
 * Correctness is verified before any timing is trusted: Barrett reduction
 * checked against Java's own `%` for many random inputs, forward+inverse
 * NTT checked to round-trip to the identity, and the vector implementation
 * checked to produce bit-identical output to the scalar one - the same
 * discipline this project has applied to every other benchmark.
 */
public class VectorNttBench {
    static final long Q = 12289;
    static final int N = 256;
    static final int LOG2N = 8;

    static final int BARRETT_SHIFT = 32;
    static final long BARRETT_M = (1L << BARRETT_SHIFT) / Q;

    static long[][] twiddleFwd = new long[LOG2N + 1][];
    static long[][] twiddleInv = new long[LOG2N + 1][];
    static long nInv;

    static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;

    public static void main(String[] args) {
        System.out.println("[vector-api-ntt] Q=" + Q + " N=" + N
                + " LongVector.SPECIES_PREFERRED=" + SPECIES
                + " (lanes=" + SPECIES.length() + ")");

        long g = findGenerator();
        System.out.println("[vector-api-ntt] generator g=" + g);
        precomputeTwiddles(g);

        testBarrett();
        testRoundTripScalar();
        testVectorMatchesScalar();
        System.out.println("[vector-api-ntt] all correctness checks passed");

        benchmark();
    }

    // ---- modular arithmetic (scalar, precomputation-only, not hot path) ----

    static long modpow(long base, long exp, long mod) {
        long result = 1;
        base %= mod;
        while (exp > 0) {
            if ((exp & 1) == 1) result = (result * base) % mod;
            base = (base * base) % mod;
            exp >>= 1;
        }
        return result;
    }

    static long modinverse(long a, long mod) {
        return modpow(a, mod - 2, mod); // Fermat, mod is prime
    }

    static long findGenerator() {
        long qm1 = Q - 1; // 12288 = 2^12 * 3
        for (long g = 2; g < Q; g++) {
            if (modpow(g, qm1 / 2, Q) != 1 && modpow(g, qm1 / 3, Q) != 1) {
                return g;
            }
        }
        throw new IllegalStateException("no generator found");
    }

    static void precomputeTwiddles(long g) {
        // principal N-th root of unity
        long root = modpow(g, (Q - 1) / N, Q);
        long rootInv = modinverse(root, Q);
        nInv = modinverse(N, Q);

        for (int len = 2; len <= N; len <<= 1) {
            long wLenFwd = modpow(root, N / len, Q);
            long wLenInv = modpow(rootInv, N / len, Q);
            long[] twF = new long[len / 2];
            long[] twI = new long[len / 2];
            long accF = 1, accI = 1;
            for (int k = 0; k < len / 2; k++) {
                twF[k] = accF;
                twI[k] = accI;
                accF = barrettReduce(accF * wLenFwd);
                accI = barrettReduce(accI * wLenInv);
            }
            twiddleFwd[Integer.numberOfTrailingZeros(len)] = twF;
            twiddleInv[Integer.numberOfTrailingZeros(len)] = twI;
        }
    }

    static long barrettReduce(long p) {
        // p must be in [0, Q*Q)
        long t = (p * BARRETT_M) >>> BARRETT_SHIFT;
        long r = p - t * Q;
        if (r >= Q) r -= Q;
        return r;
    }

    static void bitReverse(long[] a) {
        int n = a.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                long tmp = a[i];
                a[i] = a[j];
                a[j] = tmp;
            }
        }
    }

    // ---- scalar NTT ----

    static void nttScalar(long[] a, long[][] twiddles) {
        bitReverse(a);
        for (int len = 2; len <= N; len <<= 1) {
            long[] tw = twiddles[Integer.numberOfTrailingZeros(len)];
            int half = len / 2;
            for (int i = 0; i < N; i += len) {
                for (int j = 0; j < half; j++) {
                    long u = a[i + j];
                    long v = barrettReduce(a[i + j + half] * tw[j]);
                    a[i + j] = u + v >= Q ? u + v - Q : u + v;
                    a[i + j + half] = u - v < 0 ? u - v + Q : u - v;
                }
            }
        }
    }

    static void inttScalar(long[] a) {
        nttScalar(a, twiddleInv);
        for (int i = 0; i < N; i++) {
            a[i] = barrettReduce(a[i] * nInv);
        }
    }

    // ---- vector NTT ----

    static void nttVector(long[] a, long[][] twiddles) {
        bitReverse(a);
        long qVecScalar = Q;
        for (int len = 2; len <= N; len <<= 1) {
            long[] tw = twiddles[Integer.numberOfTrailingZeros(len)];
            int half = len / 2;
            for (int i = 0; i < N; i += len) {
                int j = 0;
                int upper = SPECIES.loopBound(half);
                for (; j < upper; j += SPECIES.length()) {
                    LongVector u = LongVector.fromArray(SPECIES, a, i + j);
                    LongVector vRaw = LongVector.fromArray(SPECIES, a, i + j + half);
                    LongVector twV = LongVector.fromArray(SPECIES, tw, j);

                    LongVector prod = vRaw.mul(twV);
                    LongVector t = prod.mul(BARRETT_M).lanewise(VectorOperators.LSHR, BARRETT_SHIFT);
                    LongVector v = prod.sub(t.mul(qVecScalar));
                    VectorMask<Long> needSub = v.compare(VectorOperators.GE, qVecScalar);
                    v = v.sub(qVecScalar, needSub);

                    LongVector sum = u.add(v);
                    VectorMask<Long> sumOverflow = sum.compare(VectorOperators.GE, qVecScalar);
                    sum = sum.sub(qVecScalar, sumOverflow);

                    LongVector diff = u.sub(v);
                    VectorMask<Long> diffNeg = diff.compare(VectorOperators.LT, 0L);
                    diff = diff.add(qVecScalar, diffNeg);

                    sum.intoArray(a, i + j);
                    diff.intoArray(a, i + j + half);
                }
                for (; j < half; j++) {
                    long u = a[i + j];
                    long v = barrettReduce(a[i + j + half] * tw[j]);
                    a[i + j] = u + v >= Q ? u + v - Q : u + v;
                    a[i + j + half] = u - v < 0 ? u - v + Q : u - v;
                }
            }
        }
    }

    static void inttVector(long[] a) {
        nttVector(a, twiddleInv);
        for (int i = 0; i < N; i++) {
            a[i] = barrettReduce(a[i] * nInv);
        }
    }

    // ---- correctness checks ----

    static void testBarrett() {
        Random r = new Random(42);
        for (int i = 0; i < 200_000; i++) {
            long p = (long) (r.nextDouble() * Q * Q);
            long expected = p % Q;
            long got = barrettReduce(p);
            if (got != expected) {
                throw new AssertionError("Barrett mismatch: p=" + p + " expected=" + expected + " got=" + got);
            }
        }
        System.out.println("[vector-api-ntt] Barrett reduction: OK (200000 random checks)");
    }

    static void testRoundTripScalar() {
        Random r = new Random(7);
        for (int trial = 0; trial < 100; trial++) {
            long[] a = new long[N];
            for (int i = 0; i < N; i++) a[i] = r.nextInt((int) Q);
            long[] orig = a.clone();
            nttScalar(a, twiddleFwd);
            inttScalar(a);
            if (!Arrays.equals(a, orig)) {
                throw new AssertionError("scalar NTT round-trip failed on trial " + trial);
            }
        }
        System.out.println("[vector-api-ntt] scalar forward+inverse round-trip: OK (100 random trials)");
    }

    static void testVectorMatchesScalar() {
        Random r = new Random(99);
        for (int trial = 0; trial < 200; trial++) {
            long[] a = new long[N];
            for (int i = 0; i < N; i++) a[i] = r.nextInt((int) Q);
            long[] aScalar = a.clone();
            long[] aVector = a.clone();
            nttScalar(aScalar, twiddleFwd);
            nttVector(aVector, twiddleFwd);
            if (!Arrays.equals(aScalar, aVector)) {
                throw new AssertionError("vector NTT diverged from scalar on trial " + trial);
            }
        }
        // also check the vector round-trips correctly on its own
        Random r2 = new Random(123);
        for (int trial = 0; trial < 100; trial++) {
            long[] a = new long[N];
            for (int i = 0; i < N; i++) a[i] = r2.nextInt((int) Q);
            long[] orig = a.clone();
            nttVector(a, twiddleFwd);
            inttVector(a);
            if (!Arrays.equals(a, orig)) {
                throw new AssertionError("vector NTT round-trip failed on trial " + trial);
            }
        }
        System.out.println("[vector-api-ntt] vector output matches scalar bit-for-bit: OK (200 random trials)");
        System.out.println("[vector-api-ntt] vector forward+inverse round-trip: OK (100 random trials)");
    }

    // ---- benchmark ----

    static final int WARMUP = 2000;
    static final int MEASURED = 20000;

    static void benchmark() {
        Random r = new Random(1);
        long[] base = new long[N];
        for (int i = 0; i < N; i++) base[i] = r.nextInt((int) Q);

        long[] scalarNs = new long[MEASURED];
        long[] vectorNs = new long[MEASURED];

        long[] buf = new long[N];

        for (int i = 0; i < WARMUP; i++) {
            System.arraycopy(base, 0, buf, 0, N);
            nttScalar(buf, twiddleFwd);
        }
        for (int i = 0; i < MEASURED; i++) {
            System.arraycopy(base, 0, buf, 0, N);
            long start = System.nanoTime();
            nttScalar(buf, twiddleFwd);
            scalarNs[i] = System.nanoTime() - start;
        }

        for (int i = 0; i < WARMUP; i++) {
            System.arraycopy(base, 0, buf, 0, N);
            nttVector(buf, twiddleFwd);
        }
        for (int i = 0; i < MEASURED; i++) {
            System.arraycopy(base, 0, buf, 0, N);
            long start = System.nanoTime();
            nttVector(buf, twiddleFwd);
            vectorNs[i] = System.nanoTime() - start;
        }

        Arrays.sort(scalarNs);
        Arrays.sort(vectorNs);
        System.out.println();
        System.out.printf("scalar NTT (n=%d): p50=%d ns p10=%d ns p90=%d ns%n",
                N, scalarNs[MEASURED / 2], scalarNs[MEASURED / 10], scalarNs[MEASURED * 9 / 10]);
        System.out.printf("vector NTT (n=%d): p50=%d ns p10=%d ns p90=%d ns%n",
                N, vectorNs[MEASURED / 2], vectorNs[MEASURED / 10], vectorNs[MEASURED * 9 / 10]);
        double speedup = (double) scalarNs[MEASURED / 2] / vectorNs[MEASURED / 2];
        System.out.printf("vector vs scalar (p50): %.3fx%n", speedup);
    }
}
