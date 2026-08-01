package com.latticejack.pqc.aiproxy;

/**
 * Runtime configuration for {@link PqcAiTlsServer}/{@link PqcAiTlsClient},
 * read from system properties — same {@code fromSystemProperties()}
 * convention as {@link com.latticejack.pqc.TlsConfig}, kept as a separate
 * record rather than adding fields to that one so this additive package
 * doesn't touch a file the existing before/after paths depend on.
 */
record AiConfig(String llamaServerUrl, int maxTokens, String prompt) {

    static AiConfig fromSystemProperties() {
        return new AiConfig(
                System.getProperty("latticejack.ai.llamaServerUrl", "http://127.0.0.1:8090"),
                Integer.parseInt(System.getProperty("latticejack.ai.maxTokens", "64")),
                System.getProperty("latticejack.ai.prompt",
                        "In one short sentence, what is a lattice in cryptography?"));
    }
}
