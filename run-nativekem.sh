#!/usr/bin/env bash
# Opt-in native ML-KEM-768 path: the same hybrid X25519MLKEM768 handshake as
# run-after.sh (same certs, same HRR verification logic, reused exactly -
# see that script for why HRR is the right signal for "hybrid group was
# actually negotiated, not a silent classical fallback"), but additionally
# routes ML-KEM-768 keygen/encapsulate/decapsulate through mlkem-native's
# NEON-optimized C implementation via a custom JCA Provider
# (src/main/java/com/latticejack/pqc/nativekem/) instead of BC's own
# pure-Java ML-KEM - see ProviderBootstrap.install()'s
# -Dlatticejack.tls.nativekem branch and NativeMlkemProvider's Javadoc for
# the exact mechanism.
#
# This does NOT just trust "handshake completed" or run-after.sh's HRR check
# alone: if NativeMlkemProvider's registration/priority were wrong, BC would
# silently handle ML-KEM instead and the handshake would still succeed AND
# still show an HRR (the group choice and the KEM implementation actually
# used are separate concerns) - testing nothing about the native path
# specifically. So this ALSO greps both server and client logs for the
# "[native-mlkem-provider] ... via mlkem-native FFM" trace marker
# (-Dlatticejack.nativekem.trace=true) to positively confirm keygen, encaps,
# AND decaps each actually executed through the native path - the whole
# point of this script.
set -euo pipefail
cd "$(dirname "$0")"

source ./scripts/require-jdk21.sh
./scripts/gen-classical-keys.sh

KEYS_DIR="keys/classical"
PASS="changeit"
PORT="${LATTICEJACK_PORT:-8445}"

case "$(uname -s)" in
  Darwin) LIB_NAME="libmlkem768ffm.dylib" ;;
  *)      LIB_NAME="libmlkem768ffm.so" ;;
esac
NATIVEKEM_LIB="$(pwd)/vendor/mlkem-native/$LIB_NAME"
if [ ! -f "$NATIVEKEM_LIB" ]; then
  echo "ERROR: $NATIVEKEM_LIB not found." >&2
  echo "  Build it per benchmarks/mlkem-ffm-bench/README.md 'How the shared" >&2
  echo "  library was built' (mlkem-native's own build only produces .a" >&2
  echo "  static archives - a shared library must be linked from that)." >&2
  exit 1
fi

echo "=== building ==="
mvn -q -DskipTests package
mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath \
  -Dmdep.outputFile=target/classpath.txt

CP="target/classes:$(cat target/classpath.txt)"

COMMON_OPTS=(
  -Djava.util.logging.config.file=scripts/bc-logging.properties
  -Djavax.net.ssl.trustStore="$KEYS_DIR/truststore.jks"
  -Djavax.net.ssl.trustStorePassword="$PASS"
  -Dlatticejack.tls.label=nativekem-pqc-kex
  -Dlatticejack.tls.port="$PORT"
  -Dlatticejack.tls.pqc=true
  -Dlatticejack.tls.nativekem=true
  -Dlatticejack.nativekem.lib="$NATIVEKEM_LIB"
  -Dlatticejack.nativekem.trace=true
)

echo "=== [native-kem] hybrid X25519MLKEM768 key exchange, ML-KEM-768 via mlkem-native FFM, port $PORT ==="

SERVER_LOG="$(mktemp)"
CLIENT_LOG="$(mktemp)"
cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
  rm -f "$SERVER_LOG" "$CLIENT_LOG"
}
trap cleanup EXIT

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

# --- Correctness, not just "it ran": the client must have actually received
# the echoed application data back through the encrypted channel (same
# end-to-end proof run-after.sh already relies on via `set -euo pipefail` +
# `wait "$SERVER_PID"` aborting on any failure - checked explicitly here too
# since this script's whole purpose is not to paper over a silent failure).
if ! grep -q 'server replied "echo: hello from client"' "$CLIENT_LOG"; then
  echo "" >&2
  echo "VERIFICATION FAILED: client did not receive the expected echoed reply" >&2
  echo "through the encrypted channel - see $CLIENT_LOG contents above." >&2
  exit 1
fi

# --- Same HRR check run-after.sh does: rules out a silent CLASSICAL
# (secp256r1) fallback.
HELLO_COUNT="$(grep -c "ClientHello extensions" "$CLIENT_LOG" || true)"
if [ "$HELLO_COUNT" -lt 2 ]; then
  echo "" >&2
  echo "VERIFICATION FAILED: handshake completed but no HelloRetryRequest was" >&2
  echo "observed (only $HELLO_COUNT ClientHello, expected 2) - see run-after.sh for" >&2
  echo "why this means a silent classical secp256r1 fallback, not X25519MLKEM768." >&2
  exit 1
fi

# --- The check specific to THIS script: the HRR check above only rules out
# a silent CLASSICAL fallback - it says nothing about whether ML-KEM-768
# itself ran through mlkem-native or through BC's own pure-Java
# implementation (both would show an identical HRR). Positively confirm the
# native path fired for all three operation kinds, across both peers:
# keygen happens on both sides (client's ephemeral keypair for its second
# ClientHello key_share, server's own keypair for its key_share), encaps
# happens once (client, against the server's public key), decaps happens
# once (server, against the client's ciphertext).
MARKER='\[native-mlkem-provider\]'
# grep -h -c against two files prints one count per file (no filename
# prefix); sum them directly rather than parsing a "file:count" format.
KEYGEN_COUNT="$(grep -h -c "$MARKER keypair via mlkem-native FFM" "$SERVER_LOG" "$CLIENT_LOG" | awk '{s+=$1} END {print s+0}')"
ENCAPS_COUNT="$(grep -h -c "$MARKER encaps via mlkem-native FFM" "$SERVER_LOG" "$CLIENT_LOG" | awk '{s+=$1} END {print s+0}')"
DECAPS_COUNT="$(grep -h -c "$MARKER decaps via mlkem-native FFM" "$SERVER_LOG" "$CLIENT_LOG" | awk '{s+=$1} END {print s+0}')"

if [ "$KEYGEN_COUNT" -lt 1 ] || [ "$ENCAPS_COUNT" -lt 1 ] || [ "$DECAPS_COUNT" -lt 1 ]; then
  echo "" >&2
  echo "VERIFICATION FAILED: handshake completed and HRR was observed, but the" >&2
  echo "[native-mlkem-provider] trace marker was not seen for all three operations" >&2
  echo "(keygen=$KEYGEN_COUNT encaps=$ENCAPS_COUNT decaps=$DECAPS_COUNT, want >=1 each)." >&2
  echo "This means BC's own pure-Java ML-KEM silently handled the crypto instead of" >&2
  echo "mlkem-native - check NativeMlkemProvider registration/priority in" >&2
  echo "ProviderBootstrap.install()." >&2
  exit 1
fi

echo ""
echo "VERIFIED: client received the echoed application data back through the"
echo "encrypted channel - handshake round-trip actually completed end to end."
echo ""
echo "VERIFIED: HelloRetryRequest observed ($HELLO_COUNT ClientHellos) - consistent"
echo "with X25519MLKEM768 (the first-preference group) being negotiated, not a"
echo "silent fallback to secp256r1."
echo ""
echo "VERIFIED: [native-mlkem-provider] trace marker observed for keygen"
echo "($KEYGEN_COUNT), encaps ($ENCAPS_COUNT), and decaps ($DECAPS_COUNT) - mlkem-native's"
echo "FFM path actually handled ML-KEM-768, not BC's pure-Java implementation."
