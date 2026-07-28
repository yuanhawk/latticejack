package com.latticejack.pqc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Counterpart to {@link EchoTlsServer}: connects, handshakes, sends one line, prints the reply. */
public final class EchoTlsClient {

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
        System.out.println("[" + cfg.label() + "] client: connecting to " + cfg.host() + ":" + cfg.port());

        SSLSocketFactory factory = context.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket(cfg.host(), cfg.port())) {
            if (cfg.protocol() != null) {
                socket.setEnabledProtocols(new String[] {cfg.protocol()});
            }
            if (pqc) {
                SSLParameters params = socket.getSSLParameters();
                params.setNamedGroups(ProviderBootstrap.NAMED_GROUPS);
                socket.setSSLParameters(params);
            }
            socket.startHandshake();
            SSLSession session = socket.getSession();
            System.out.println("[" + cfg.label() + "] client: handshake complete protocol="
                    + session.getProtocol() + " cipherSuite=" + session.getCipherSuite());

            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                out.println(cfg.message());
                String response = in.readLine();
                System.out.println("[" + cfg.label() + "] client: server replied \"" + response + "\"");
            }
        }
    }
}
