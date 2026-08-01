package com.latticejack.pqc.aiproxy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.latticejack.pqc.ProviderBootstrap;
import com.latticejack.pqc.TlsConfig;

/**
 * Counterpart to {@link PqcAiTlsServer} — connects over hybrid X25519MLKEM768
 * TLS, sends one prompt line, reads back one JSON reply line, and reports a
 * rough timing split: handshake time vs. total request-to-response time
 * (which is dominated by llama-server's own prompt-eval + token-generation
 * time, not by anything TLS-related).
 *
 * This same class is used both for the cheap local prototype (originally
 * run on a Mac laptop, to work out the integration mechanics before
 * spending real Azure VM time) AND, unmodified, for the real-hardware
 * measurement on the actual Azure Cobalt 100 (Neoverse-N2) target that
 * produced the numbers quoted in WRITEUP.md and
 * benchmarks/ai-inference-pqc/README.md - it does not know which machine
 * it's running on, so its printed platform label is read at runtime from
 * {@code os.name}/{@code os.arch} (see below) rather than hardcoded to
 * either one; an earlier version of this class printed a hardcoded
 * "Mac laptop" label unconditionally, which was still printing on the real
 * Azure run - found by an independent audit, fixed here.
 */
public final class PqcAiTlsClient {

    public static void main(String[] args) throws Exception {
        ProviderBootstrap.install();
        SSLContext context = ProviderBootstrap.buildContext();

        TlsConfig cfg = TlsConfig.fromSystemProperties();
        AiConfig aiCfg = AiConfig.fromSystemProperties();
        System.out.println("[" + cfg.label() + "] ai-client: connecting to " + cfg.host() + ":" + cfg.port());

        SSLSocketFactory factory = context.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket(cfg.host(), cfg.port())) {
            if (cfg.protocol() != null) {
                socket.setEnabledProtocols(new String[] {cfg.protocol()});
            }
            SSLParameters params = socket.getSSLParameters();
            params.setNamedGroups(ProviderBootstrap.namedGroups());
            socket.setSSLParameters(params);

            long handshakeStartNanos = System.nanoTime();
            socket.startHandshake();
            long handshakeEndNanos = System.nanoTime();
            double handshakeMs = (handshakeEndNanos - handshakeStartNanos) / 1_000_000.0;

            SSLSession session = socket.getSession();
            System.out.println("[" + cfg.label() + "] ai-client: handshake complete protocol="
                    + session.getProtocol() + " cipherSuite=" + session.getCipherSuite());

            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                System.out.println("[" + cfg.label() + "] ai-client: sending prompt \"" + aiCfg.prompt() + "\"");

                long requestStartNanos = System.nanoTime();
                out.println(aiCfg.prompt());
                String replyLine = in.readLine();
                long requestEndNanos = System.nanoTime();
                double requestToResponseMs = (requestEndNanos - requestStartNanos) / 1_000_000.0;

                if (replyLine == null) {
                    throw new IllegalStateException("ai-server closed the connection with no reply");
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> reply = (Map<String, Object>) MiniJson.parse(replyLine);
                String content = MiniJson.asString(reply.get("content"));

                System.out.println("[" + cfg.label() + "] ai-client: model replied \"" + content + "\"");
                System.out.println("");
                String platform = System.getProperty("os.name", "unknown-os") + "/"
                        + System.getProperty("os.arch", "unknown-arch");
                System.out.println("[" + cfg.label() + "] ai-client: TIMING (this run: " + platform
                        + " - cross-check against WRITEUP.md/benchmarks/ai-inference-pqc/README.md "
                        + "for which run is the quoted headline number):");
                System.out.println("[" + cfg.label() + "]   handshake time        = "
                        + String.format("%.1f", handshakeMs) + " ms");
                System.out.println("[" + cfg.label() + "]   request-to-response   = "
                        + String.format("%.1f", requestToResponseMs) + " ms  (includes llama-server prompt eval "
                        + MiniJson.asDouble(reply.get("llama_prompt_ms")) + "ms + generation "
                        + MiniJson.asDouble(reply.get("llama_predicted_ms")) + "ms + loopback HTTP + JSON overhead)");
                System.out.println("[" + cfg.label() + "]   handshake / inference ratio = "
                        + String.format("%.3f", handshakeMs / requestToResponseMs)
                        + "  (i.e. handshake cost is " + String.format("%.1f", requestToResponseMs / handshakeMs)
                        + "x smaller than the AI workload it fronts)");
            }
        }
    }
}
