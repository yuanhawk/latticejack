#!/usr/bin/env bash
# Component A "before": classical mTLS handshake (ECDSA P-256 certs, JSSE
# default TLS 1.3 negotiation -> X25519 key exchange on current JDKs).
# Set LATTICEJACK_DEBUG=1 to dump the full handshake (useful for verifying
# exactly which group/signature scheme was negotiated).
# Set SKIP_BUILD=1 to skip this script's own `mvn package` step (for a
# caller, e.g. demo/run-demo.sh, that already built once up front and wants
# to run all four demo scripts back-to-back without redundant rebuilds).
# When unset (the default), behavior is unchanged from before this option
# existed.
set -euo pipefail
cd "$(dirname "$0")"

source ./scripts/require-jdk21.sh
./scripts/gen-classical-keys.sh

KEYS_DIR="keys/classical"
PASS="changeit"
PORT="${LATTICEJACK_PORT:-8443}"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "=== building ==="
  mvn -q -DskipTests package
fi

DEBUG_OPTS=()
if [ "${LATTICEJACK_DEBUG:-0}" = "1" ]; then
  DEBUG_OPTS=(-Djavax.net.debug=ssl:handshake)
fi

COMMON_OPTS=(
  -Djavax.net.ssl.trustStore="$KEYS_DIR/truststore.jks"
  -Djavax.net.ssl.trustStorePassword="$PASS"
  -Dlatticejack.tls.label=before-classical
  -Dlatticejack.tls.port="$PORT"
  "${DEBUG_OPTS[@]+"${DEBUG_OPTS[@]}"}"
)

echo "=== [before] classical TLS/mTLS on port $PORT ==="

CLIENT_LOG="$(mktemp)"

# --enable-preview: not used by this classical path itself, but required at
# runtime for EVERY class in the module once pom.xml's compiler plugin turns
# it on module-wide for src/main/java/com/latticejack/pqc/nativekem/'s
# java.lang.foreign usage - see pom.xml's maven-compiler-plugin comment.
java --enable-preview "${COMMON_OPTS[@]}" \
  -Djavax.net.ssl.keyStore="$KEYS_DIR/server.jks" \
  -Djavax.net.ssl.keyStorePassword="$PASS" \
  -cp target/classes com.latticejack.pqc.EchoTlsServer &
SERVER_PID=$!
cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
  rm -f "$CLIENT_LOG"
}
trap cleanup EXIT
sleep 1

java --enable-preview "${COMMON_OPTS[@]}" \
  -Djavax.net.ssl.keyStore="$KEYS_DIR/client.jks" \
  -Djavax.net.ssl.keyStorePassword="$PASS" \
  -cp target/classes com.latticejack.pqc.EchoTlsClient 2>&1 | tee "$CLIENT_LOG"

wait "$SERVER_PID"

# --- Correctness, not just "it ran": unlike run-after.sh/run-nativekem.sh/
# run-ai.sh, this script previously asserted nothing beyond a nonzero exit
# code. Mirror run-nativekem.sh's own pattern here: grep the client log for
# the server's echoed reply line, positively confirming the round trip
# actually completed over the TLS channel rather than just trusting
# `set -euo pipefail` + `wait "$SERVER_PID"` not exploding.
if ! grep -q 'server replied "echo: hello from client"' "$CLIENT_LOG"; then
  echo "" >&2
  echo "VERIFICATION FAILED: client did not receive the expected echoed reply" >&2
  echo "through the TLS channel - see $CLIENT_LOG contents above." >&2
  exit 1
fi

echo ""
echo "VERIFIED: client received the echoed application data back through the"
echo "TLS channel - handshake round-trip actually completed end to end."
