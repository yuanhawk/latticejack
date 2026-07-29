package com.latticejack.pqc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A one-shot, single-connection byte-counting TCP relay: accepts exactly one
 * connection on an ephemeral local port, forwards it transparently to the
 * real target, and reports total bytes relayed in each direction once both
 * sides close.
 *
 * Used by BenchmarkClient's "bytes" mode to measure raw bytes-on-wire for a
 * single TLS handshake without parsing TLS framing or subclassing
 * java.net.Socket (which would need to override ~20 methods to delegate
 * correctly) - the relay only ever sees opaque bytes, so it works
 * identically for the classical and hybrid PQC configs.
 */
final class ByteCountingRelay implements AutoCloseable {
    private final ServerSocket listener;
    private final String targetHost;
    private final int targetPort;
    final AtomicLong bytesClientToServer = new AtomicLong();
    final AtomicLong bytesServerToClient = new AtomicLong();
    private final CountDownLatch done = new CountDownLatch(1);

    ByteCountingRelay(String targetHost, int targetPort) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }

    int getLocalPort() {
        return listener.getLocalPort();
    }

    /** Starts accepting the single connection in a background thread. */
    void start() {
        Thread t = new Thread(this::runOnce, "byte-counting-relay");
        t.setDaemon(true);
        t.start();
    }

    private void runOnce() {
        try (Socket client = listener.accept();
                Socket server = new Socket(targetHost, targetPort)) {
            Thread up = pump(client, server, bytesClientToServer);
            Thread down = pump(server, client, bytesServerToClient);
            up.join();
            down.join();
        } catch (Exception e) {
            // benchmark-only relay: a failed relay just yields a failed iteration upstream
        } finally {
            done.countDown();
        }
    }

    private Thread pump(Socket from, Socket to, AtomicLong counter) {
        Thread t = new Thread(() -> {
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    counter.addAndGet(n);
                }
            } catch (IOException ignored) {
                // peer closed
            } finally {
                try {
                    to.shutdownOutput();
                } catch (IOException ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Blocks until the relayed connection has fully closed (both directions EOF). */
    void awaitDone(long timeoutMillis) throws InterruptedException {
        done.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    long totalBytes() {
        return bytesClientToServer.get() + bytesServerToClient.get();
    }

    @Override
    public void close() throws IOException {
        listener.close();
    }
}
