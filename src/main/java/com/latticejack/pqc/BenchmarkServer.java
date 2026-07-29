package com.latticejack.pqc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

/**
 * B1 characterization harness server (arm-hackathon-plan.md §3 Component B1):
 * accepts a fixed number of handshake-only connections (no application data -
 * see BenchmarkClient) and exits. Reuses the same -Dlatticejack.tls.pqc
 * gating and ProviderBootstrap wiring as EchoTlsServer, so it runs against
 * both before/after configs unmodified.
 *
 * accept() itself stays sequential (cheap - just queuing incoming SYNs), but
 * each accepted connection's handshake runs on its own pool thread, so
 * concurrent clients (BenchmarkClient's "throughput" mode) aren't serialized
 * behind a single-threaded handshake loop, which would make the server the
 * artificial bottleneck rather than measuring real concurrent capacity.
 */
public final class BenchmarkServer {

    public static void main(String[] args) throws Exception {
        boolean pqc = Boolean.getBoolean("latticejack.tls.pqc");
        SSLContext context;
        if (pqc) {
            ProviderBootstrap.install();
            context = ProviderBootstrap.buildContext();
        } else {
            context = SSLContext.getDefault();
        }

        int port = Integer.parseInt(System.getProperty("latticejack.bench.port", "8500"));
        int connections = Integer.parseInt(System.getProperty("latticejack.bench.connections", "500"));
        boolean requireClientAuth =
                Boolean.parseBoolean(System.getProperty("latticejack.tls.requireClientAuth", "true"));

        SSLServerSocketFactory factory = context.getServerSocketFactory();
        try (SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(port)) {
            serverSocket.setNeedClientAuth(requireClientAuth);
            if (pqc) {
                SSLParameters params = serverSocket.getSSLParameters();
                params.setNamedGroups(ProviderBootstrap.NAMED_GROUPS);
                serverSocket.setSSLParameters(params);
            }
            System.out.println("[bench-server] accepting " + connections + " connections on port " + port);

            ExecutorService pool = Executors.newCachedThreadPool();
            AtomicInteger handled = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(connections);

            for (int i = 0; i < connections; i++) {
                SSLSocket socket = (SSLSocket) serverSocket.accept();
                pool.submit(() -> {
                    try (socket) {
                        socket.startHandshake();
                        handled.incrementAndGet();
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            pool.shutdown();
            System.out.println("[bench-server] handled " + handled.get() + "/" + connections
                    + " (" + failed.get() + " failed)");
        }
    }
}
