package com.latticejack.pqc.aiproxy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

import com.latticejack.pqc.ProviderBootstrap;
import com.latticejack.pqc.TlsConfig;

/**
 * Component D prototype (arm-hackathon-plan.md's "AI solution on Arm"
 * gate — see WRITEUP.md): a one-shot TLS-fronted inference gateway. Same
 * hybrid X25519MLKEM768 handshake setup as {@link com.latticejack.pqc.EchoTlsServer}
 * (same {@link ProviderBootstrap}, same certs, same {@link TlsConfig}
 * plumbing — deliberately reused, not forked, per that class's Javadoc),
 * but instead of echoing a line back, it proxies the received line as a
 * prompt to a local llama.cpp {@code llama-server} instance (see
 * {@link LlamaServerClient}) over plain loopback HTTP, and returns the
 * completion — content plus llama-server's own timing breakdown — back to
 * the client over the SAME encrypted channel, as one JSON line.
 *
 * Deliberately additive and isolated: this package does not modify
 * EchoTlsServer, EchoTlsClient, or ProviderBootstrap's actual TLS
 * negotiation logic (only widened two members' visibility — see
 * ProviderBootstrap's Javadoc note) — this class is new code alongside the
 * already-verified before/after paths, not a change to them, the same
 * shape as how {@code com.latticejack.pqc.nativekem} was added.
 *
 * One-shot, single connection, like EchoTlsServer — a full inference
 * request/response is a much larger, slower unit of work than the echo's
 * one line, so there's no benefit here to EchoTlsServer's simplicity
 * being extended with concurrency for this prototype.
 *
 * Requires llama-server already running and reachable at
 * -Dlatticejack.ai.llamaServerUrl (default http://127.0.0.1:8090) — see
 * run-ai.sh, which starts it and checks /health before this class runs.
 */
public final class PqcAiTlsServer {

    public static void main(String[] args) throws Exception {
        ProviderBootstrap.install();
        SSLContext context = ProviderBootstrap.buildContext();

        TlsConfig cfg = TlsConfig.fromSystemProperties();
        AiConfig aiCfg = AiConfig.fromSystemProperties();
        System.out.println("[" + cfg.label() + "] ai-server: starting on port " + cfg.port()
                + ", llama-server=" + aiCfg.llamaServerUrl());

        LlamaServerClient llama = new LlamaServerClient(aiCfg.llamaServerUrl());
        // Fail fast and loud if llama-server isn't up, rather than accepting
        // a TLS connection successfully and only then discovering the
        // inference backend is unreachable mid-request - the whole point of
        // this class is proving the TLS leg AND the inference leg both
        // actually worked, not just the TLS leg.
        llama.checkHealth(aiCfg.llamaServerUrl());
        System.out.println("[" + cfg.label() + "] ai-server: llama-server health check OK");

        SSLServerSocketFactory factory = context.getServerSocketFactory();
        try (SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(cfg.port())) {
            serverSocket.setNeedClientAuth(cfg.requireClientAuth());
            if (cfg.protocol() != null) {
                serverSocket.setEnabledProtocols(new String[] {cfg.protocol()});
            }
            SSLParameters params = serverSocket.getSSLParameters();
            params.setNamedGroups(ProviderBootstrap.namedGroups());
            serverSocket.setSSLParameters(params);
            System.out.println("[" + cfg.label() + "] ai-server: listening, enabledProtocols="
                    + String.join(",", serverSocket.getEnabledProtocols())
                    + " needClientAuth=" + serverSocket.getNeedClientAuth());

            try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
                socket.startHandshake();
                SSLSession session = socket.getSession();
                System.out.println("[" + cfg.label() + "] ai-server: handshake complete protocol="
                        + session.getProtocol() + " cipherSuite=" + session.getCipherSuite());
                // Same caveat as EchoTlsServer: standard SSLSession exposes
                // no negotiated-named-group accessor - the HRR check in
                // run-ai.sh (reused verbatim from run-after.sh) is what
                // actually rules out a silent classical fallback here, not
                // this log line.

                try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                    String prompt = in.readLine();
                    if (prompt == null) {
                        // Client completed the handshake then closed without
                        // sending a line - EOF, not an error worth a stack
                        // trace. Found by an independent audit: this used to
                        // fall straight into llama.complete(null, ...) and
                        // NPE inside MiniJson.escape's s.length() call.
                        System.out.println("[" + cfg.label() + "] ai-server: client closed connection before sending a prompt - nothing to do");
                        return;
                    }
                    System.out.println("[" + cfg.label() + "] ai-server: received prompt \"" + prompt + "\"");

                    long inferStartNanos = System.nanoTime();
                    LlamaServerClient.CompletionResult result = llama.complete(prompt, aiCfg.maxTokens());
                    long inferEndNanos = System.nanoTime();
                    double inferWallMs = (inferEndNanos - inferStartNanos) / 1_000_000.0;

                    System.out.println("[" + cfg.label() + "] ai-server: llama-server responded in "
                            + String.format("%.1f", inferWallMs) + "ms wall (server-reported prompt="
                            + result.promptMs() + "ms predicted=" + result.predictedMs() + "ms, "
                            + result.promptTokens() + " prompt tok / " + result.predictedTokens() + " gen tok)");

                    // Single JSON line back over the encrypted channel - see
                    // MiniJson's Javadoc for why a hand-rolled reader/writer
                    // instead of a dependency, and why JSON (not raw text)
                    // for the reply: model output can itself contain
                    // newlines, which would break EchoTlsServer's
                    // one-line-per-message framing if sent raw.
                    // MiniJson.number(), not raw concatenation, for every
                    // double: llama-server's /completion response omits its
                    // "timings" object under some conditions, leaving these
                    // as Double.NaN - Java's String.valueOf(NaN) is "NaN",
                    // not a valid JSON token, which used to make the
                    // client's MiniJson.parse() reject this entire line.
                    // Found by an independent audit.
                    String replyJson = "{"
                            + "\"content\":\"" + MiniJson.escape(result.content()) + "\","
                            + "\"server_infer_wall_ms\":" + MiniJson.number(inferWallMs) + ","
                            + "\"llama_prompt_ms\":" + MiniJson.number(result.promptMs()) + ","
                            + "\"llama_predicted_ms\":" + MiniJson.number(result.predictedMs()) + ","
                            + "\"llama_prompt_tokens\":" + result.promptTokens() + ","
                            + "\"llama_predicted_tokens\":" + result.predictedTokens()
                            + "}";
                    out.println(replyJson);
                }
            }
        }
        System.out.println("[" + cfg.label() + "] ai-server: done");
    }
}
