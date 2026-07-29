package com.latticejack.pqc;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * B1 characterization harness client (arm-hackathon-plan.md §3 Component B1):
 * handshake latency percentiles, throughput under concurrency, and
 * bytes-on-wire, against the SAME before/after configs EchoTlsClient
 * demonstrates correctness for. Deliberately simple/dependency-free per the
 * plan's "a simple, honest harness you control beats a heavyweight
 * framework" guidance (§8) - a sorted-array percentile calc (Stats), not
 * HdrHistogram, which is plenty accurate at these sample sizes.
 *
 * Four modes (-Dlatticejack.bench.mode): "latency" (default), "throughput",
 * "bytes", "resumption" - run as separate passes (see run-benchmark.sh), not
 * combined, so each metric is measured without another metric's
 * instrumentation skewing it. In particular, "bytes" mode routes through a
 * local ByteCountingRelay, which adds a loopback hop that would bias latency
 * numbers if measured in the same pass.
 *
 * "resumption" is Component B2's first Arm64/JVM tuning lever
 * (arm-hackathon-plan.md §3 Component B2): TLS session resumption lets a
 * repeat connection skip the expensive asymmetric key exchange (ML-KEM for
 * the PQC config) entirely via a lightweight PSK-derived handshake, which
 * the plan calls a "near-guaranteed" win worth measuring specifically for
 * the PQC path, where the asymmetric operation is most expensive.
 */
public final class BenchmarkClient {

    public static void main(String[] args) throws Exception {
        boolean pqc = Boolean.getBoolean("latticejack.tls.pqc");
        SSLContext context;
        if (pqc) {
            ProviderBootstrap.install();
            context = ProviderBootstrap.buildContext();
        } else {
            context = SSLContext.getDefault();
        }

        String label = System.getProperty("latticejack.tls.label", "unlabeled");
        String host = System.getProperty("latticejack.tls.host", "localhost");
        int port = Integer.parseInt(System.getProperty("latticejack.bench.port", "8500"));
        int warmup = Integer.parseInt(System.getProperty("latticejack.bench.warmup", "20"));
        int iterations = Integer.parseInt(System.getProperty("latticejack.bench.iterations", "200"));
        int concurrency = Integer.parseInt(System.getProperty("latticejack.bench.concurrency", "8"));
        String mode = System.getProperty("latticejack.bench.mode", "latency");
        String csvPath = System.getProperty("latticejack.bench.csv");

        SSLSocketFactory factory = context.getSocketFactory();

        switch (mode) {
            case "latency" -> runLatency(factory, pqc, label, host, port, warmup, iterations, csvPath);
            case "throughput" -> runThroughput(factory, pqc, label, host, port, warmup, iterations, concurrency);
            case "bytes" -> runBytes(factory, pqc, label, host, port, iterations, csvPath);
            case "resumption" -> runResumption(factory, pqc, label, host, port, iterations, csvPath);
            default -> throw new IllegalArgumentException("unknown -Dlatticejack.bench.mode: " + mode
                    + " (expected latency, throughput, bytes, or resumption)");
        }
    }

    private static void applyPqcGroups(SSLSocket socket, boolean pqc) throws Exception {
        if (pqc) {
            SSLParameters params = socket.getSSLParameters();
            params.setNamedGroups(ProviderBootstrap.NAMED_GROUPS);
            socket.setSSLParameters(params);
        }
    }

    private static void oneHandshake(SSLSocketFactory factory, boolean pqc, String host, int port) throws Exception {
        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port)) {
            applyPqcGroups(socket, pqc);
            socket.startHandshake();
        }
    }

    // ---- latency + CPU ----

    private static void runLatency(SSLSocketFactory factory, boolean pqc, String label, String host, int port,
            int warmup, int iterations, String csvPath) throws Exception {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        boolean cpuSupported = threadBean.isThreadCpuTimeSupported();
        if (cpuSupported) {
            threadBean.setThreadCpuTimeEnabled(true);
        }

        System.out.println("[" + label + "] latency: " + warmup + " warmup + " + iterations + " measured handshakes");
        for (int i = 0; i < warmup; i++) {
            oneHandshake(factory, pqc, host, port);
        }

        long[] latencyNanos = new long[iterations];
        long[] cpuNanos = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long cpuBefore = cpuSupported ? threadBean.getCurrentThreadCpuTime() : -1;
            long start = System.nanoTime();
            oneHandshake(factory, pqc, host, port);
            latencyNanos[i] = System.nanoTime() - start;
            cpuNanos[i] = cpuSupported ? threadBean.getCurrentThreadCpuTime() - cpuBefore : -1;
        }

        long[] sorted = Stats.sorted(latencyNanos);
        double p50 = Stats.percentile(sorted, 50) / 1_000_000.0;
        double p95 = Stats.percentile(sorted, 95) / 1_000_000.0;
        double p99 = Stats.percentile(sorted, 99) / 1_000_000.0;
        double mean = Stats.mean(latencyNanos) / 1_000_000.0;
        double min = sorted[0] / 1_000_000.0;
        double max = sorted[sorted.length - 1] / 1_000_000.0;

        System.out.printf("[%s] latency ms: p50=%.3f p95=%.3f p99=%.3f mean=%.3f min=%.3f max=%.3f%n",
                label, p50, p95, p99, mean, min, max);
        if (cpuSupported) {
            System.out.printf("[%s] client CPU ms/handshake (mean): %.3f%n", label, Stats.mean(cpuNanos) / 1_000_000.0);
        } else {
            System.out.println("[" + label + "] thread CPU time not supported on this JVM");
        }

        if (csvPath != null) {
            try (PrintWriter w = new PrintWriter(new FileWriter(csvPath))) {
                w.println("iteration,latency_ms,cpu_ms");
                for (int i = 0; i < iterations; i++) {
                    double lat = latencyNanos[i] / 1_000_000.0;
                    double cpu = cpuNanos[i] < 0 ? Double.NaN : cpuNanos[i] / 1_000_000.0;
                    w.printf("%d,%.4f,%.4f%n", i, lat, cpu);
                }
            }
            System.out.println("[" + label + "] wrote " + csvPath);
        }
    }

    // ---- throughput ----

    private static void runThroughput(SSLSocketFactory factory, boolean pqc, String label, String host, int port,
            int warmup, int iterations, int concurrency) throws Exception {
        for (int i = 0; i < warmup; i++) {
            oneHandshake(factory, pqc, host, port);
        }

        AtomicInteger completed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            tasks.add(() -> {
                try {
                    oneHandshake(factory, pqc, host, port);
                    completed.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
                return null;
            });
        }

        long start = System.nanoTime();
        for (Future<Void> f : pool.invokeAll(tasks)) {
            f.get();
        }
        long elapsedNanos = System.nanoTime() - start;
        pool.shutdown();

        double seconds = elapsedNanos / 1_000_000_000.0;
        double throughput = completed.get() / seconds;
        System.out.printf("[%s] throughput: %d ok, %d failed, %.2fs, %.1f handshakes/sec (concurrency=%d)%n",
                label, completed.get(), failed.get(), seconds, throughput, concurrency);
    }

    // ---- bytes-on-wire ----

    private static void runBytes(SSLSocketFactory factory, boolean pqc, String label, String host, int port,
            int iterations, String csvPath) throws Exception {
        long[] totalBytes = new long[iterations];
        long[] clientToServer = new long[iterations];
        long[] serverToClient = new long[iterations];

        for (int i = 0; i < iterations; i++) {
            try (ByteCountingRelay relay = new ByteCountingRelay(host, port)) {
                relay.start();
                try (SSLSocket socket = (SSLSocket) factory.createSocket("localhost", relay.getLocalPort())) {
                    applyPqcGroups(socket, pqc);
                    socket.startHandshake();
                }
                relay.awaitDone(5000);
                clientToServer[i] = relay.bytesClientToServer.get();
                serverToClient[i] = relay.bytesServerToClient.get();
                totalBytes[i] = relay.totalBytes();
            }
        }

        System.out.printf("[%s] bytes-on-wire (mean of %d handshakes): total=%.0f client->server=%.0f server->client=%.0f%n",
                label, iterations, Stats.mean(totalBytes), Stats.mean(clientToServer), Stats.mean(serverToClient));

        if (csvPath != null) {
            try (PrintWriter w = new PrintWriter(new FileWriter(csvPath))) {
                w.println("iteration,total_bytes,client_to_server,server_to_client");
                for (int i = 0; i < iterations; i++) {
                    w.printf("%d,%d,%d,%d%n", i, totalBytes[i], clientToServer[i], serverToClient[i]);
                }
            }
            System.out.println("[" + label + "] wrote " + csvPath);
        }
    }

    // ---- session resumption (Component B2, tuning lever 1) ----

    /**
     * Two back-to-back connections per iteration, reusing the SAME
     * SSLSocketFactory/SSLContext so the client-side session cache applies:
     * the first is a full handshake, the second attempts resumption against
     * it.
     *
     * Two real bugs were found and fixed getting this method right, both
     * worth understanding before touching it again:
     *
     * 1. TLS 1.3's NewSessionTicket (what resumption actually PSKs off of)
     * is a POST-handshake message - JSSE only processes buffered incoming
     * records, tickets included, when the application performs I/O, not
     * proactively in the background. The original version of this method
     * called Thread.sleep() after the handshake and then closed - sleeping
     * doesn't read anything, so the ticket sat unprocessed and every
     * "resumption attempt" silently fell back to a full handshake
     * ("Existing session has no PSK", confirmed via
     * -Djavax.net.debug=ssl,handshake,session against the classical/SunJSSE
     * path). Fixed by pumpPendingRecords() below: a short bounded read
     * attempt that forces the record-processing pipeline to run.
     *
     * 2. There was no warmup phase, unlike "latency" mode. Without it, the
     * very first "full" handshake in the whole run pays JIT cold-start cost
     * that the "resumed" side (executed microseconds later, warmer) doesn't
     * - producing a large, genuine-LOOKING latency drop that was actually
     * mostly JIT warmup, not resumption (a >90% reduction even while the
     * ticket wasn't being processed at all, per bug 1). Fixed with an
     * explicit discarded warmup loop before measurement starts.
     *
     * Resumption per-pair is flagged via SSLSession ID comparison as a
     * best-effort, supplementary signal only - it is not a documented
     * public API contract, and TLS 1.3 implementations aren't required to
     * reuse the same ID on a resumed connection. The measured latency delta
     * between the (now properly warmed-up, ticket-pumped) full and resumed
     * series is the primary, defensible result.
     */
    private static void runResumption(SSLSocketFactory factory, boolean pqc, String label, String host, int port,
            int iterations, String csvPath) throws Exception {
        int warmupPairs = Math.max(5, iterations / 10);
        System.out.println("[" + label + "] resumption: " + warmupPairs + " warmup pairs + " + iterations
                + " measured pairs");
        for (int i = 0; i < warmupPairs; i++) {
            resumptionPair(factory, pqc, host, port);
        }

        long[] fullLatencyNanos = new long[iterations];
        long[] resumedLatencyNanos = new long[iterations];
        boolean[] resumed = new boolean[iterations];

        for (int i = 0; i < iterations; i++) {
            long[] latencies = new long[2];
            boolean[] resumedFlag = new boolean[1];
            resumptionPairTimed(factory, pqc, host, port, latencies, resumedFlag);
            fullLatencyNanos[i] = latencies[0];
            resumedLatencyNanos[i] = latencies[1];
            resumed[i] = resumedFlag[0];
        }

        int resumedCount = 0;
        for (boolean r : resumed) {
            if (r) {
                resumedCount++;
            }
        }

        double fullMean = Stats.mean(fullLatencyNanos) / 1_000_000.0;
        double resumedMean = Stats.mean(resumedLatencyNanos) / 1_000_000.0;
        double resumedRate = 100.0 * resumedCount / iterations;
        double delta = 100.0 * (fullMean - resumedMean) / fullMean;

        System.out.printf(
                "[%s] resumption: full=%.3fms resumed_attempt=%.3fms (%.1f%% confirmed resumed, %.1f%% latency reduction)%n",
                label, fullMean, resumedMean, resumedRate, delta);
        if (resumedRate < 50.0) {
            System.out.println("[" + label + "] WARNING: most connections did NOT resume - "
                    + "resumed_attempt latency is not a reliable resumption-benefit number here");
        }

        if (csvPath != null) {
            try (PrintWriter w = new PrintWriter(new FileWriter(csvPath))) {
                w.println("iteration,full_latency_ms,resumed_attempt_latency_ms,resumed");
                for (int i = 0; i < iterations; i++) {
                    w.printf("%d,%.4f,%.4f,%d%n", i, fullLatencyNanos[i] / 1_000_000.0,
                            resumedLatencyNanos[i] / 1_000_000.0, resumed[i] ? 1 : 0);
                }
            }
            System.out.println("[" + label + "] wrote " + csvPath);
        }
    }

    /** Warmup variant: runs a full+resumed pair, discards timing/detection results. */
    private static void resumptionPair(SSLSocketFactory factory, boolean pqc, String host, int port)
            throws Exception {
        resumptionPairTimed(factory, pqc, host, port, new long[2], new boolean[1]);
    }

    /** outLatencies[0]=full ns, outLatencies[1]=resumed-attempt ns, outResumed[0]=best-effort resumption flag. */
    private static void resumptionPairTimed(SSLSocketFactory factory, boolean pqc, String host, int port,
            long[] outLatencies, boolean[] outResumed) throws Exception {
        byte[] firstSessionId;
        long fullStart = System.nanoTime();
        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port)) {
            applyPqcGroups(socket, pqc);
            socket.startHandshake();
            firstSessionId = socket.getSession().getId();
            pumpPendingRecords(socket);
        }
        outLatencies[0] = System.nanoTime() - fullStart;

        byte[] secondSessionId;
        long resumedStart = System.nanoTime();
        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port)) {
            applyPqcGroups(socket, pqc);
            socket.startHandshake();
            secondSessionId = socket.getSession().getId();
        }
        outLatencies[1] = System.nanoTime() - resumedStart;

        outResumed[0] = firstSessionId.length > 0 && Arrays.equals(firstSessionId, secondSessionId);
    }

    /**
     * Forces JSSE's read-driven record-processing pipeline to run at least
     * once after the handshake, so a buffered post-handshake message (like
     * TLS 1.3's NewSessionTicket) actually gets parsed and cached instead of
     * sitting unread on the socket - see runResumption's Javadoc, bug 1.
     * A timeout (no data ever arrives from BenchmarkServer, which sends no
     * application data) or EOF (server already closed) are both expected
     * outcomes here, not errors - the point is triggering the read, not
     * what it returns.
     */
    private static void pumpPendingRecords(SSLSocket socket) {
        try {
            socket.setSoTimeout(200);
            socket.getInputStream().read();
        } catch (Exception expected) {
            // timeout or EOF - fine, the read attempt already did its job
        }
    }
}
