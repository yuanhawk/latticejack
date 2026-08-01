#!/usr/bin/env bash
# Component D prototype: same hybrid X25519MLKEM768 TLS 1.3 key exchange as
# run-after.sh (same certs, same ProviderBootstrap, same HRR verification
# logic reused verbatim below - see run-after.sh's comment for why HRR is
# the right signal for "the hybrid group was actually negotiated, not a
# silent classical fallback"), fronting a real local LLM inference request
# instead of an echo - see src/main/java/com/latticejack/pqc/aiproxy/ for
# the additive PqcAiTlsServer/PqcAiTlsClient/LlamaServerClient classes this
# script drives.
#
# LOCAL PROTOTYPE ONLY: this runs against llama-server on THIS machine (a
# Mac laptop), not the real Azure Cobalt 100 (Neoverse-N2) target - the
# timing numbers this script prints at the end are explicitly NOT the
# project's headline benchmark numbers (those live in benchmarks/ and are
# all real-hardware). This script exists to prove the INTEGRATION MECHANICS
# work end to end (KleidiAI-accelerated inference reachable over a real,
# verified hybrid-PQC TLS handshake) cheaply, before a later phase repeats
# this exact mechanism on the real target.
#
# Prerequisites (not automated by this script - see component-ai/README.md):
#   1. llama.cpp built from source with GGML_CPU_KLEIDIAI=ON.
#   2. llama-server running locally with a Q4_0 GGUF model loaded, e.g.:
#        component-ai/llama.cpp/build/bin/llama-server \
#          -m component-ai/models/Llama-3.2-1B-Instruct-Q4_0.gguf \
#          --host 127.0.0.1 --port 8090
#
# Set SKIP_BUILD=1 to skip this script's own `mvn package` + build-classpath
# step (for a caller, e.g. demo/run-demo.sh, that already built once up
# front and wants to run all four demo scripts back-to-back without
# redundant rebuilds - target/classpath.txt must already exist in that
# case). When unset (the default), behavior is unchanged from before this
# option existed.
set -euo pipefail
cd "$(dirname "$0")"

source ./scripts/require-jdk21.sh
./scripts/gen-classical-keys.sh

KEYS_DIR="keys/classical"
PASS="changeit"
PORT="${LATTICEJACK_AI_PORT:-8446}"
LLAMA_URL="${LATTICEJACK_LLAMA_URL:-http://127.0.0.1:8090}"

echo "=== checking llama-server at $LLAMA_URL ==="
if ! curl -sf --max-time 5 "$LLAMA_URL/health" > /dev/null; then
  echo "ERROR: llama-server not reachable at $LLAMA_URL/health." >&2
  echo "  Start it first, e.g.:" >&2
  echo "    component-ai/llama.cpp/build/bin/llama-server \\" >&2
  echo "      -m component-ai/models/Llama-3.2-1B-Instruct-Q4_0.gguf \\" >&2
  echo "      --host 127.0.0.1 --port 8090" >&2
  echo "  See component-ai/README.md for how it was built (GGML_CPU_KLEIDIAI=ON)." >&2
  exit 1
fi
echo "llama-server OK."

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "=== building ==="
  mvn -q -DskipTests package
  mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath \
    -Dmdep.outputFile=target/classpath.txt
fi

CP="target/classes:$(cat target/classpath.txt)"

COMMON_OPTS=(
  -Djava.util.logging.config.file=scripts/bc-logging.properties
  -Djavax.net.ssl.trustStore="$KEYS_DIR/truststore.jks"
  -Djavax.net.ssl.trustStorePassword="$PASS"
  -Dlatticejack.tls.label=ai-pqc-kex
  -Dlatticejack.tls.port="$PORT"
  -Dlatticejack.ai.llamaServerUrl="$LLAMA_URL"
)

echo "=== [ai] hybrid X25519MLKEM768 TLS fronting local LLM inference, port $PORT ==="

SERVER_LOG="$(mktemp)"
CLIENT_LOG="$(mktemp)"
cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
  rm -f "$SERVER_LOG" "$CLIENT_LOG"
}
trap cleanup EXIT

# --enable-preview: required module-wide by nativekem's java.lang.foreign
# usage (see pom.xml's maven-compiler-plugin comment) - inert for this
# package specifically, same as run-after.sh.
java --enable-preview "${COMMON_OPTS[@]}" \
  -Djavax.net.ssl.keyStore="$KEYS_DIR/server.jks" \
  -Djavax.net.ssl.keyStorePassword="$PASS" \
  -cp "$CP" com.latticejack.pqc.aiproxy.PqcAiTlsServer > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!
sleep 1

java --enable-preview "${COMMON_OPTS[@]}" \
  -Djavax.net.ssl.keyStore="$KEYS_DIR/client.jks" \
  -Djavax.net.ssl.keyStorePassword="$PASS" \
  -cp "$CP" com.latticejack.pqc.aiproxy.PqcAiTlsClient 2>&1 | tee "$CLIENT_LOG"

wait "$SERVER_PID"
cat "$SERVER_LOG" >&2

# --- Correctness: the client must have actually received a real model
# reply through the encrypted channel, not an empty/error response.
if ! grep -q 'ai-client: model replied' "$CLIENT_LOG"; then
  echo "" >&2
  echo "VERIFICATION FAILED: client did not receive a model reply through the" >&2
  echo "encrypted channel - see $CLIENT_LOG contents above." >&2
  exit 1
fi

# --- Same HRR check run-after.sh/run-nativekem.sh do: rules out a silent
# CLASSICAL (secp256r1) fallback for this new path too, not just the
# original echo path - a fronting change like this is exactly the kind of
# regression skills/pqc-authoring/ is meant to catch, so check it directly
# here rather than assuming it still holds.
HELLO_COUNT="$(grep -c "ClientHello extensions" "$CLIENT_LOG" || true)"
if [ "$HELLO_COUNT" -lt 2 ]; then
  echo "" >&2
  echo "VERIFICATION FAILED: handshake completed but no HelloRetryRequest was" >&2
  echo "observed (only $HELLO_COUNT ClientHello, expected 2) - see run-after.sh for" >&2
  echo "why this means a silent classical secp256r1 fallback, not X25519MLKEM768." >&2
  exit 1
fi

echo ""
echo "VERIFIED: client received a real model reply through the encrypted channel."
echo ""
echo "VERIFIED: HelloRetryRequest observed ($HELLO_COUNT ClientHellos) - consistent"
echo "with X25519MLKEM768 (the first-preference group) being negotiated, not a"
echo "silent fallback to secp256r1."
echo ""
echo "NOTE: the TIMING block printed above is from THIS machine (see the"
echo "os.name/os.arch label on that line) - cross-check WRITEUP.md and"
echo "benchmarks/ai-inference-pqc/README.md for which run is the quoted"
echo "real-hardware headline number, see PqcAiTlsClient's Javadoc for why."
