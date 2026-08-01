package com.latticejack.pqc.aiproxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader/writer — same "a simple, honest
 * harness you control" ethos as {@link com.latticejack.pqc.Stats} and
 * {@link com.latticejack.pqc.BenchmarkClient} (arm-hackathon-plan.md §8):
 * pulling in a JSON library for two call sites (building the llama-server
 * request body, reading its response) isn't worth a new pom.xml dependency.
 *
 * Parses the subset of JSON llama-server's {@code /completion} and
 * {@code /health} responses actually use: objects, arrays, strings,
 * numbers, booleans, null. Not a general-purpose/spec-complete parser —
 * e.g. it does not validate surrogate-pair \\uXXXX sequences beyond basic
 * hex decoding, and it is not hardened against adversarial input, both fine
 * for a loopback call to a local trusted process, not fine for anything
 * parsing untrusted external JSON.
 */
final class MiniJson {
    private MiniJson() {}

    // --- writing (only what's needed to build a JSON request body) ---

    /** Escapes a string for embedding as a JSON string literal (without the surrounding quotes). */
    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * Formats a double for embedding as a JSON number literal - emits the
     * JSON literal {@code null} for NaN/Infinite instead of Java's
     * {@code String.valueOf(double)} rendering ("NaN", "Infinity"), which
     * are not valid JSON tokens and would make {@link #parse} reject the
     * whole document. Found by an independent audit: llama-server's
     * {@code /completion} response omits its {@code timings} object under
     * some conditions, which left the caller building the reply string with
     * a raw NaN, silently corrupting an otherwise-successful completion.
     */
    static String number(double d) {
        return (Double.isNaN(d) || Double.isInfinite(d)) ? "null" : String.valueOf(d);
    }

    // --- reading ---

    /** Parses a complete JSON document (object, array, string, number, boolean, or null). */
    static Object parse(String json) {
        Parser p = new Parser(json);
        Object result = p.parseValue();
        p.skipWhitespace();
        if (p.pos != json.length()) {
            throw new IllegalArgumentException("trailing content after JSON value at offset " + p.pos);
        }
        return result;
    }

    /** Reads a dotted path (e.g. "timings.predicted_ms") out of a parsed object/array tree. */
    @SuppressWarnings("unchecked")
    static Object at(Object root, String dottedPath) {
        Object cur = root;
        for (String part : dottedPath.split("\\.")) {
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(part);
            } else if (cur instanceof List<?> l) {
                cur = l.get(Integer.parseInt(part));
            } else {
                throw new IllegalStateException("cannot descend into '" + part + "' of " + cur);
            }
        }
        return cur;
    }

    static String asString(Object o) {
        return (String) o;
    }

    /** Null-safe: a JSON {@code null} (round-tripped from {@link #number}'s NaN/Infinite guard) reads back as NaN, not an NPE. */
    static double asDouble(Object o) {
        return o == null ? Double.NaN : ((Number) o).doubleValue();
    }

    static int asInt(Object o) {
        return ((Number) o).intValue();
    }

    private static final class Parser {
        private final String s;
        private int pos = 0;

        Parser(String s) {
            this.s = s;
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw new IllegalArgumentException("unexpected end of JSON input");
            }
            return s.charAt(pos);
        }

        void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException("expected '" + c + "' at offset " + pos + " in: " + s);
            }
            pos++;
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    break;
                } else {
                    throw new IllegalArgumentException("expected ',' or '}' at offset " + pos);
                }
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    break;
                } else {
                    throw new IllegalArgumentException("expected ',' or ']' at offset " + pos);
                }
            }
            return list;
        }

        String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char esc = peek();
                    pos++;
                    switch (esc) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'u' -> {
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            out.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("bad escape '\\" + esc + "'");
                    }
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        Boolean parseBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("expected boolean at offset " + pos);
        }

        Object parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("expected null at offset " + pos);
        }

        Double parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < s.length() && "0123456789.eE+-".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
