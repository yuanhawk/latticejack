package com.latticejack.pqc;

/**
 * Runtime configuration for the reference TLS/mTLS server and client, read from
 * system properties so the same jar serves both the "before" (classical) and
 * "after" (PQC) configurations via different launch flags — no code fork.
 */
public record TlsConfig(
        String label,
        String host,
        int port,
        String protocol,
        boolean requireClientAuth,
        String message) {

    public static TlsConfig fromSystemProperties() {
        return new TlsConfig(
                System.getProperty("latticejack.tls.label", "unlabeled"),
                System.getProperty("latticejack.tls.host", "localhost"),
                Integer.parseInt(System.getProperty("latticejack.tls.port", "8443")),
                System.getProperty("latticejack.tls.protocol"),
                Boolean.parseBoolean(System.getProperty("latticejack.tls.requireClientAuth", "true")),
                System.getProperty("latticejack.tls.message", "hello from client"));
    }
}
