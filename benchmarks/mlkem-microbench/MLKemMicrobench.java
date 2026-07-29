import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Arrays;
import javax.crypto.KEM;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jcajce.spec.MLKEMParameterSpec;

/**
 * Standalone (JDK 25+ required, deliberately NOT part of the JDK-21-pinned
 * main project) comparison: BouncyCastle's pure-Java ML-KEM-768 vs JDK 25's
 * built-in one (JEP 496, with AArch64 intrinsics added in JDK 25 per
 * JDK-8349721 - see docs/bouncycastle-pqc-notes.md for the full writeup and
 * why this comparison matters).
 *
 * Both sides run under the SAME JVM (JDK 25) via the SAME standard JCA API
 * (KeyPairGenerator/KEM), just swapping the provider name ("BC" vs
 * "SunJCE") - this isolates the comparison to "which ML-KEM implementation"
 * rather than confounding it with "which JDK version," which is the
 * variable we actually care about here (does staying inside a
 * JVM-with-intrinsics close the gap, independent of testing methodology
 * differences).
 *
 * Measures keygen/encapsulate/decapsulate SEPARATELY (unlike the main B1
 * harness's TLS-handshake-level measurement) since JDK 25's ML-KEM isn't
 * wired into any TLS stack we use - see the class-level note in
 * ProviderBootstrap.java and docs/bouncycastle-pqc-notes.md for why that's
 * a separate, harder problem than this microbenchmark.
 *
 * Also runs at TWO warmup regimes (low and high) to hunt for exactly the
 * kind of "hole" worth finding: does JDK 25's advantage hold under a
 * cold-ish start (more representative of a real per-connection handshake,
 * which is JIT-warmup-light) or does it only show up once fully warmed,
 * which a real TLS handshake path may never reach for a given connection.
 */
public final class MLKemMicrobench {

    private static final int MEASURED = 500;

    public static void main(String[] args) throws Exception {
        Security.insertProviderAt(new BouncyCastleProvider(), 1);

        System.out.println("JVM: " + System.getProperty("java.vendor") + " "
                + System.getProperty("java.version") + " (" + System.getProperty("java.vm.name") + ")");
        System.out.println("OS/arch: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println();

        for (int warmup : new int[] {5, 200}) {
            System.out.println("### warmup=" + warmup + " measured=" + MEASURED + " ###");
            runProvider("BC", warmup);
            runProvider("SunJCE", warmup);
            System.out.println();
        }
    }

    private static void runProvider(String provider, int warmup) throws Exception {
        long[] keygenNanos = new long[MEASURED];
        long[] encapNanos = new long[MEASURED];
        long[] decapNanos = new long[MEASURED];

        for (int i = 0; i < warmup; i++) {
            oneCycle(provider);
        }

        for (int i = 0; i < MEASURED; i++) {
            KeyPairGenerator kpg = keyPairGenerator(provider);
            long t0 = System.nanoTime();
            KeyPair kp = kpg.generateKeyPair();
            keygenNanos[i] = System.nanoTime() - t0;

            KEM kem = KEM.getInstance("ML-KEM-768", provider);

            KEM.Encapsulator enc = kem.newEncapsulator(kp.getPublic());
            long t1 = System.nanoTime();
            KEM.Encapsulated encapsulated = enc.encapsulate();
            encapNanos[i] = System.nanoTime() - t1;

            KEM.Decapsulator dec = kem.newDecapsulator(kp.getPrivate());
            long t2 = System.nanoTime();
            byte[] secret = dec.decapsulate(encapsulated.encapsulation()).getEncoded();
            decapNanos[i] = System.nanoTime() - t2;

            if (!Arrays.equals(secret, encapsulated.key().getEncoded())) {
                throw new IllegalStateException(provider + ": KEM round-trip mismatch at iteration " + i);
            }
        }

        report(provider + " keygen", keygenNanos);
        report(provider + " encaps", encapNanos);
        report(provider + " decaps", decapNanos);
    }

    private static void oneCycle(String provider) throws Exception {
        KeyPairGenerator kpg = keyPairGenerator(provider);
        KeyPair kp = kpg.generateKeyPair();
        KEM kem = KEM.getInstance("ML-KEM-768", provider);
        KEM.Encapsulator enc = kem.newEncapsulator(kp.getPublic());
        KEM.Encapsulated encapsulated = enc.encapsulate();
        KEM.Decapsulator dec = kem.newDecapsulator(kp.getPrivate());
        dec.decapsulate(encapsulated.encapsulation());
    }

    private static KeyPairGenerator keyPairGenerator(String provider) throws Exception {
        if (provider.equals("BC")) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("ML-KEM", "BC");
            kpg.initialize(MLKEMParameterSpec.ml_kem_768);
            return kpg;
        }
        return KeyPairGenerator.getInstance("ML-KEM-768", provider);
    }

    private static void report(String label, long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double p50 = percentile(sorted, 50) / 1000.0;
        double p95 = percentile(sorted, 95) / 1000.0;
        double p99 = percentile(sorted, 99) / 1000.0;
        double mean = mean(nanos) / 1000.0;
        double min = sorted[0] / 1000.0;
        double max = sorted[sorted.length - 1] / 1000.0;
        System.out.printf("%-16s us: p50=%.2f p95=%.2f p99=%.2f mean=%.2f min=%.2f max=%.2f%n",
                label, p50, p95, p99, mean, min, max);
    }

    private static double percentile(long[] sorted, double p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(sorted.length - 1, idx));
        return sorted[idx];
    }

    private static double mean(long[] values) {
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return (double) sum / values.length;
    }
}
