#!/usr/bin/env bash
# Component A "after": hybrid TLS 1.3 key exchange (X25519MLKEM768, preferred
# over the secp256r1 fallback ProviderBootstrap.NAMED_GROUPS requires - see
# that file's Javadoc for why a fallback is a hard requirement, not a loophole)
# via BouncyCastle BCJSSE 1.85, on the SAME classical ECDSA certs as
# run-before.sh (ML-DSA cert auth is a separate, deferred gap - see
# docs/bouncycastle-pqc-notes.md and MIGRATION.md).
#
# This script does not just trust "handshake complete" - BCJSSE exposes no
# programmatic accessor for the negotiated named group (checked: neither
# BCExtendedSSLSession nor BCSSLConnection), and BCJSSE logs via
# java.util.logging, not -Djavax.net.debug, so it enables that logging and
# asserts a HelloRetryRequest occurred: since the hybrid group is listed
# first, an HRR means the client's cheap first guess (classical) wasn't what
# got selected, and a second ClientHello then carried the actual (larger,
# ML-KEM-inclusive) key_share - the same signal an independent audit used to
# confirm this. See arm-hackathon-plan.md §8: don't trust a PQC label without
# checking for a silent classical fallback.
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
PORT="${LATTICEJACK_PORT:-8444}"

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
  -Dlatticejack.tls.label=after-pqc-kex
  -Dlatticejack.tls.port="$PORT"
  -Dlatticejack.tls.pqc=true
)

echo "=== [after] hybrid X25519MLKEM768 key exchange on port $PORT ==="

SERVER_LOG="$(mktemp)"
CLIENT_LOG="$(mktemp)"
cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
  rm -f "$SERVER_LOG" "$CLIENT_LOG"
}
trap cleanup EXIT

# --enable-preview: not used by this hybrid-KEX-only path itself, but
# required at runtime for EVERY class in the module once pom.xml's compiler
# plugin turns it on module-wide for src/main/java/com/latticejack/pqc/
# nativekem/'s java.lang.foreign usage - see pom.xml's maven-compiler-plugin
# comment.
java --enable-preview "${COMMON_OPTS[@]}" \
  -Djavax.net.ssl.keyStore="$KEYS_DIR/server.jks" \
  -Djavax.net.ssl.keyStorePassword="$PASS" \
  -cp "$CP" com.latticejack.pqc.EchoTlsServer > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!
sleep 1

java --enable-preview "${COMMON_OPTS[@]}" \
  -Djavax.net.ssl.keyStore="$KEYS_DIR/client.jks" \
  -Djavax.net.ssl.keyStorePassword="$PASS" \
  -cp "$CP" com.latticejack.pqc.EchoTlsClient 2>&1 | tee "$CLIENT_LOG"

wait "$SERVER_PID"
cat "$SERVER_LOG" >&2

HELLO_COUNT="$(grep -c "ClientHello extensions" "$CLIENT_LOG" || true)"
if [ "$HELLO_COUNT" -lt 2 ]; then
  echo "" >&2
  echo "VERIFICATION FAILED: handshake completed but no HelloRetryRequest was" >&2
  echo "observed (only $HELLO_COUNT ClientHello, expected 2). Since X25519MLKEM768" >&2
  echo "is the first-preference group, a single-round handshake with no HRR is" >&2
  echo "evidence the negotiation silently used the classical secp256r1 fallback" >&2
  echo "instead - see ProviderBootstrap.NAMED_GROUPS and docs/bouncycastle-pqc-notes.md." >&2
  exit 1
fi

echo ""
echo "VERIFIED: HelloRetryRequest observed ($HELLO_COUNT ClientHellos) - consistent"
echo "with X25519MLKEM768 (the first-preference group) being negotiated, not a"
echo "silent fallback to secp256r1."
