package com.latticejack.pqc.aiproxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Plain-HTTP client for a local llama.cpp {@code llama-server} instance's
 * OpenAI-incompatible legacy {@code /completion} endpoint (chosen over
 * {@code /v1/chat/completions} for this prototype specifically because its
 * response is flat — {@code content}/{@code timings} at the top level, no
 * {@code choices[0].message.content} nesting — which keeps {@link MiniJson}
 * trivial). This call is loopback-only (same host as the JVM process, see
 * {@code run-ai.sh}): the PQC-TLS hop this whole package exists to
 * demonstrate is the client→{@link PqcAiTlsServer} leg, not this leg — the
 * server→llama-server leg is plain HTTP by design, the same trust boundary
 * a real deployment's sidecar-to-localhost-model-server hop would have.
 */
final class LlamaServerClient {

    /** One llama-server completion: generated text plus llama-server's own self-reported timings. */
    record CompletionResult(
            String content,
            double promptMs,
            double predictedMs,
            int promptTokens,
            int predictedTokens) {}

    private final HttpClient http;
    private final URI completionUri;

    LlamaServerClient(String baseUrl) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.completionUri = URI.create(baseUrl.replaceAll("/+$", "") + "/completion");
    }

    /** Throws if llama-server isn't reachable/healthy — fail fast with a clear cause, not a hang. */
    void checkHealth(String baseUrl) throws IOException, InterruptedException {
        URI healthUri = URI.create(baseUrl.replaceAll("/+$", "") + "/health");
        HttpRequest req = HttpRequest.newBuilder(healthUri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("llama-server health check at " + healthUri
                    + " returned HTTP " + resp.statusCode() + ": " + resp.body());
        }
    }

    CompletionResult complete(String prompt, int maxTokens) throws IOException, InterruptedException {
        String body = "{"
                + "\"prompt\":\"" + MiniJson.escape(prompt) + "\","
                + "\"n_predict\":" + maxTokens + ","
                + "\"temperature\":0,"
                + "\"cache_prompt\":false"
                + "}";

        HttpRequest req = HttpRequest.newBuilder(completionUri)
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("llama-server /completion returned HTTP " + resp.statusCode()
                    + ": " + resp.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) MiniJson.parse(resp.body());
        String content = MiniJson.asString(json.get("content"));
        Object timings = json.get("timings");
        double promptMs = timings != null ? MiniJson.asDouble(MiniJson.at(timings, "prompt_ms")) : Double.NaN;
        double predictedMs = timings != null ? MiniJson.asDouble(MiniJson.at(timings, "predicted_ms")) : Double.NaN;
        int promptTokens = timings != null ? MiniJson.asInt(MiniJson.at(timings, "prompt_n")) : -1;
        int predictedTokens = timings != null ? MiniJson.asInt(MiniJson.at(timings, "predicted_n")) : -1;

        return new CompletionResult(content, promptMs, predictedMs, promptTokens, predictedTokens);
    }
}
