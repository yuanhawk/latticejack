package com.latticejack.pqc;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
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
 * Three modes (-Dlatticejack.bench.mode): "latency" (default), "throughput",
 * "bytes" - run as separate passes (see run-benchmark.sh), not combined, so
 * each metric is measured without another metric's instrumentation skewing
 * it. In particular, "bytes" mode routes through a local ByteCountingRelay,
 * which adds a loopback hop that would bias latency numbers if measured in
 * the same pass.
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
            default -> throw new IllegalArgumentException(
                    "unknown -Dlatticejack.bench.mode: " + mode + " (expected latency, throughput, or bytes)");
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
}
