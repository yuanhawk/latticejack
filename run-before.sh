#!/usr/bin/env bash
# Component A "before": classical mTLS handshake (ECDSA P-256 certs, JSSE
# default TLS 1.3 negotiation -> X25519 key exchange on current JDKs).
# Set LATTICEJACK_DEBUG=1 to dump the full handshake (useful for verifying
# exactly which group/signature scheme was negotiated).
set -euo pipefail
cd "$(dirname "$0")"

source ./scripts/require-jdk21.sh
./scripts/gen-classical-keys.sh

KEYS_DIR="keys/classical"
PASS="changeit"
PORT="${LATTICEJACK_PORT:-8443}"

echo "=== building ==="
mvn -q -DskipTests package

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

# --enable-preview: not used by this classical path itself, but required at
# runtime for EVERY class in the module once pom.xml's compiler plugin turns
# it on module-wide for src/main/java/com/latticejack/pqc/nativekem/'s
# java.lang.foreign usage - see pom.xml's maven-compiler-plugin comment.
java --enable-preview "${COMMON_OPTS[@]}" \
  -Djavax.net.ssl.keyStore="$KEYS_DIR/server.jks" \
  -Djavax.net.ssl.keyStorePassword="$PASS" \
  -cp target/classes com.latticejack.pqc.EchoTlsServer &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT
sleep 1

java --enable-preview "${COMMON_OPTS[@]}" \
  -Djavax.net.ssl.keyStore="$KEYS_DIR/client.jks" \
  -Djavax.net.ssl.keyStorePassword="$PASS" \
  -cp target/classes com.latticejack.pqc.EchoTlsClient

wait "$SERVER_PID"
