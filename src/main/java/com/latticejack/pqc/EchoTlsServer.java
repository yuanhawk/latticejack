package com.latticejack.pqc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * One-shot mTLS echo server. Accepts a single connection, completes the
 * handshake, echoes one line, then exits — enough to prove and benchmark a
 * handshake without a full application framework in the way.
 *
 * Key/trust material and enabled protocol come from javax.net.ssl.* and
 * latticejack.tls.* system properties (see run-before.sh / run-after.sh),
 * so this class is identical across the classical and PQC configurations.
 */
public final class EchoTlsServer {

    public static void main(String[] args) throws Exception {
        boolean pqc = Boolean.getBoolean("latticejack.tls.pqc");
        SSLContext context;
        if (pqc) {
            ProviderBootstrap.install();
            context = ProviderBootstrap.buildContext();
        } else {
            context = SSLContext.getDefault();
        }
        TlsConfig cfg = TlsConfig.fromSystemProperties();
        System.out.println("[" + cfg.label() + "] server: starting on port " + cfg.port());

        SSLServerSocketFactory factory = context.getServerSocketFactory();
        try (SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(cfg.port())) {
            serverSocket.setNeedClientAuth(cfg.requireClientAuth());
            if (cfg.protocol() != null) {
                serverSocket.setEnabledProtocols(new String[] {cfg.protocol()});
            }
            if (pqc) {
                SSLParameters params = serverSocket.getSSLParameters();
                params.setNamedGroups(ProviderBootstrap.namedGroups());
                serverSocket.setSSLParameters(params);
            }
            System.out.println("[" + cfg.label() + "] server: listening, enabledProtocols="
                    + String.join(",", serverSocket.getEnabledProtocols())
                    + " needClientAuth=" + serverSocket.getNeedClientAuth());

            try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
                socket.startHandshake();
                SSLSession session = socket.getSession();
                System.out.println("[" + cfg.label() + "] server: handshake complete protocol="
                        + session.getProtocol() + " cipherSuite=" + session.getCipherSuite());
                // NOTE: standard javax.net.ssl.SSLSession does not expose the
                // negotiated key-exchange group or signature scheme directly,
                // and BCJSSE has no BC-specific accessor for it either (checked
                // BCExtendedSSLSession/BCSSLConnection). Do not assume a hybrid
                // PQC group negotiated just because the handshake succeeded -
                // a silent classical fallback is possible and must be ruled
                // out explicitly (arm-hackathon-plan.md §8). run-after.sh does
                // this via BC's java.util.logging output (NOT -Djavax.net.debug,
                // which BCJSSE doesn't honor) - see MIGRATION.md "Gotchas".

                try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                    String line = in.readLine();
                    System.out.println("[" + cfg.label() + "] server: received \"" + line + "\"");
                    out.println("echo: " + line);
                }
            }
        }
        System.out.println("[" + cfg.label() + "] server: done");
    }
}
