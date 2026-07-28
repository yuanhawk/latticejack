#!/usr/bin/env bash
# Component A "after" (in progress): attempts a hybrid TLS 1.3 key exchange
# (X25519MLKEM768 only, no classical fallback - see ProviderBootstrap.NAMED_GROUPS)
# via BouncyCastle BCJSSE 1.85, layered on the SAME classical ECDSA certs as
# run-before.sh.
#
# CURRENT STATE: this correctly runs BC's provider stack (a real bug in
# SSLContext.getDefault()'s BC credential-bridging was found and fixed to get
# here - see docs/bouncycastle-pqc-notes.md) but the handshake still fails
# with handshake_failure: X25519MLKEM768 negotiation on its own does not yet
# succeed, even though a handshake using BC's full default group list does.
# This is a known, tracked gap (docs/bouncycastle-pqc-notes.md), not a script
# bug - it deliberately does NOT fall back to a classical group, because a
# handshake that silently falls back would misrepresent itself as PQC-migrated.
#
# ML-DSA certificate-based authentication is a separate, also-unresolved gap:
# implemented upstream in BC but not enabled by default (bcgit/bc-java#2102).
set -euo pipefail
cd "$(dirname "$0")"

./scripts/gen-classical-keys.sh

KEYS_DIR="keys/classical"
PASS="changeit"
PORT="${LATTICEJACK_PORT:-8444}"

echo "=== building ==="
mvn -q -DskipTests package
mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath \
  -Dmdep.outputFile=target/classpath.txt

CP="target/classes:$(cat target/classpath.txt)"

DEBUG_OPTS=()
if [ "${LATTICEJACK_DEBUG:-0}" = "1" ]; then
  DEBUG_OPTS=(-Djavax.net.debug=ssl:handshake)
fi

COMMON_OPTS=(
  -Djavax.net.ssl.trustStore="$KEYS_DIR/truststore.jks"
  -Djavax.net.ssl.trustStorePassword="$PASS"
  -Dlatticejack.tls.label=after-pqc-kex
  -Dlatticejack.tls.port="$PORT"
  -Dlatticejack.tls.pqc=true
  "${DEBUG_OPTS[@]+"${DEBUG_OPTS[@]}"}"
)

echo "=== [after] hybrid X25519MLKEM768 key exchange attempt (ECDSA certs) on port $PORT ==="
echo "    known gap: this currently fails with handshake_failure - see docs/bouncycastle-pqc-notes.md"

java "${COMMON_OPTS[@]}" \
  -Djavax.net.ssl.keyStore="$KEYS_DIR/server.jks" \
  -Djavax.net.ssl.keyStorePassword="$PASS" \
  -cp "$CP" com.latticejack.pqc.EchoTlsServer &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT
sleep 1

java "${COMMON_OPTS[@]}" \
  -Djavax.net.ssl.keyStore="$KEYS_DIR/client.jks" \
  -Djavax.net.ssl.keyStorePassword="$PASS" \
  -cp "$CP" com.latticejack.pqc.EchoTlsClient

wait "$SERVER_PID"
