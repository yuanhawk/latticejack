#!/usr/bin/env bash
# B2 lever 4: compares full cold-start latency (server process launch to a
# successful hybrid-PQC handshake) between the GraalVM native-image binaries
# (scripts/build-native-image.sh) and the regular pinned-JDK21 JVM running
# the same jar. Restarts the server fresh for every iteration - EchoTlsServer
# accepts exactly one connection then exits, by design (see EchoTlsServer.java),
# so this is a fair single-shot-service comparison, not an amortized-startup one.
#
# Readiness is polled via the server's own "listening" log line, not a TCP
# probe: an `nc -z`-style probe connection gets accepted as THE ONE connection
# EchoTlsServer will ever accept, starving the real client (found the hard way
# while building this - see native-image/README.md).
set -euo pipefail
cd "$(dirname "$0")/.."

RUNS="${1:-10}"
NATIVE_DIR="target/native-image"
if [ ! -x "$NATIVE_DIR/echo-server-native" ] || [ ! -x "$NATIVE_DIR/echo-client-native" ]; then
  echo "ERROR: $NATIVE_DIR/echo-{server,client}-native not found - run scripts/build-native-image.sh first." >&2
  exit 1
fi

source ./scripts/require-jdk21.sh
mvn -q -DskipTests package
mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath \
  -Dmdep.outputFile=target/classpath.txt
CP="target/classes:$(cat target/classpath.txt)"
JAVA21="$JAVA_HOME/bin/java"

KEYS_DIR="keys/classical"
PASS="changeit"
./scripts/gen-classical-keys.sh >/dev/null

CLIENT_OPTS=(
  -Djavax.net.ssl.trustStore="$KEYS_DIR/truststore.jks" -Djavax.net.ssl.trustStorePassword="$PASS"
  -Dlatticejack.tls.label=coldstart -Dlatticejack.tls.pqc=true
  -Djavax.net.ssl.keyStore="$KEYS_DIR/client.jks" -Djavax.net.ssl.keyStorePassword="$PASS"
)
SERVER_OPTS=(
  -Djavax.net.ssl.trustStore="$KEYS_DIR/truststore.jks" -Djavax.net.ssl.trustStorePassword="$PASS"
  -Dlatticejack.tls.label=coldstart -Dlatticejack.tls.pqc=true
  -Djavax.net.ssl.keyStore="$KEYS_DIR/server.jks" -Djavax.net.ssl.keyStorePassword="$PASS"
)

run_pair() {
  local mode="$1" port="$2"
  local slog; slog="$(mktemp)"
  local sstart; sstart="$(date +%s%N)"
  if [ "$mode" = "native" ]; then
    "$NATIVE_DIR/echo-server-native" -Dlatticejack.tls.port="$port" "${SERVER_OPTS[@]}" >"$slog" 2>&1 &
  else
    "$JAVA21" -cp "$CP" -Dlatticejack.tls.port="$port" "${SERVER_OPTS[@]}" com.latticejack.pqc.EchoTlsServer >"$slog" 2>&1 &
  fi
  local spid=$!
  for _ in $(seq 1 200); do grep -q "listening" "$slog" 2>/dev/null && break; sleep 0.02; done
  local out cend
  if [ "$mode" = "native" ]; then
    out="$("$NATIVE_DIR/echo-client-native" -Dlatticejack.tls.port="$port" "${CLIENT_OPTS[@]}" 2>&1)"
  else
    out="$("$JAVA21" -cp "$CP" -Dlatticejack.tls.port="$port" "${CLIENT_OPTS[@]}" com.latticejack.pqc.EchoTlsClient 2>&1)"
  fi
  cend="$(date +%s%N)"
  wait "$spid" 2>/dev/null || true
  rm -f "$slog"
  if echo "$out" | grep -q "server replied"; then
    echo "OK $(( (cend - sstart) / 1000000 ))"
  else
    echo "FAIL $(( (cend - sstart) / 1000000 ))"
    echo "$out" >&2
  fi
}

PORT_BASE=$((20000 + RANDOM % 20000))

echo "### native-image: server-launch-to-client-done wall time, N=$RUNS ###"
for i in $(seq 1 "$RUNS"); do
  r="$(run_pair native $((PORT_BASE + i)))"
  echo "native run $i: $r"
done

echo "### regular JVM (pinned JDK21): server-launch-to-client-done wall time, N=$RUNS ###"
for i in $(seq 1 "$RUNS"); do
  r="$(run_pair jvm $((PORT_BASE + 1000 + i)))"
  echo "jvm run $i: $r"
done
