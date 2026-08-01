package com.latticejack.pqc.aiproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * This project's first automated tests, added after an independent audit
 * found a real bug here (see {@code number()}'s test below): the shell
 * -script end-to-end checks elsewhere in this project (./run before,
 * ./run after, ./run-nativekem.sh) are the right tool for verifying real
 * TLS negotiation, since the negotiated named group isn't observable any
 * other way — but MiniJson is pure, deterministic, dependency-free logic
 * with no such excuse for being unverified. Scope matches MiniJson's own
 * Javadoc: the subset of JSON llama-server's /completion and /health
 * responses actually use, not spec-complete JSON.
 */
class MiniJsonTest {

    @Test
    void escapeThenParseRoundTripsSpecialCharacters() {
        String original = "quote\" backslash\\ newline\n tab\t cr\r control end";
        String json = "\"" + MiniJson.escape(original) + "\"";
        assertEquals(original, MiniJson.parse(json));
    }

    @Test
    void parsesARealisticCompletionResponseShape() {
        String json = """
                {"content":"hello there","timings":{"prompt_ms":12.5,"predicted_ms":340.2},\
                "tokens_predicted":18,"tokens_evaluated":6}""";
        @SuppressWarnings("unchecked")
        Map<String, Object> reply = (Map<String, Object>) MiniJson.parse(json);

        assertEquals("hello there", MiniJson.asString(reply.get("content")));
        assertEquals(12.5, MiniJson.asDouble(MiniJson.at(reply, "timings.prompt_ms")));
        assertEquals(340.2, MiniJson.asDouble(MiniJson.at(reply, "timings.predicted_ms")));
        assertEquals(18, MiniJson.asInt(reply.get("tokens_predicted")));
    }

    @Test
    void atDescendsArrayIndicesInADottedPath() {
        Object parsed = MiniJson.parse("{\"choices\":[{\"text\":\"a\"},{\"text\":\"b\"}]}");
        assertEquals("b", MiniJson.asString(MiniJson.at(parsed, "choices.1.text")));
    }

    @Test
    void rejectsTrailingContentAfterTheValue() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\":1} garbage"));
    }

    // --- regression test for the bug this audit found ---

    @Test
    void numberEmitsJsonNullForNanAndInfiniteRatherThanInvalidTokens() {
        // Before the fix: String.valueOf(Double.NaN) is the Java literal
        // "NaN", which is not a valid JSON number token - embedding it
        // un-guarded (as the old code did for llama-server's timing fields
        // when its response omitted the "timings" object) produced a
        // document MiniJson.parse() itself would reject.
        assertEquals("null", MiniJson.number(Double.NaN));
        assertEquals("null", MiniJson.number(Double.POSITIVE_INFINITY));
        assertEquals("null", MiniJson.number(Double.NEGATIVE_INFINITY));
        assertEquals("1.5", MiniJson.number(1.5));

        String json = "{\"llama_prompt_ms\":" + MiniJson.number(Double.NaN) + "}";
        Object parsed = MiniJson.parse(json); // must not throw
        @SuppressWarnings("unchecked")
        Map<String, Object> reply = (Map<String, Object>) parsed;
        assertNull(reply.get("llama_prompt_ms"));
    }

    @Test
    void asDoubleOfJsonNullIsNanNotAnNpe() {
        // Companion to the above: the reader side must round-trip a JSON
        // null back to NaN rather than NullPointerException-ing on
        // ((Number) null).doubleValue() - otherwise the writer-side fix
        // alone would just move the crash from the server to the client.
        assertTrue(Double.isNaN(MiniJson.asDouble(null)));
    }

    @Test
    void parsesArraysBooleansAndNull() {
        List<?> list = (List<?>) MiniJson.parse("[true, false, null, 1, \"x\"]");
        assertEquals(5, list.size());
        assertEquals(Boolean.TRUE, list.get(0));
        assertEquals(Boolean.FALSE, list.get(1));
        assertNull(list.get(2));
    }
}
