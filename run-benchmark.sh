#!/usr/bin/env bash
# B1 characterization harness (arm-hackathon-plan.md §3 Component B1): runs
# latency/throughput/bytes-on-wire passes against either the classical
# ("before") or hybrid PQC ("after") config, writing CSVs under
# benchmarks/results/ for later before-vs-after (and, in Component B2,
# naive-vs-tuned) comparison.
#
# Each pass restarts BenchmarkServer fresh rather than sharing one long-lived
# server across passes, matching this repo's existing run-before.sh/
# run-after.sh style, and keeping each pass's connection count exact.
#
# Usage: ./run-benchmark.sh {before|after} [iterations] [warmup] [concurrency]
set -euo pipefail
cd "$(dirname "$0")"

source ./scripts/require-jdk21.sh
./scripts/gen-classical-keys.sh

CONFIG="${1:-}"
if [ "$CONFIG" != "before" ] && [ "$CONFIG" != "after" ]; then
  echo "usage: ./run-benchmark.sh {before|after} [iterations] [warmup] [concurrency]" >&2
  exit 1
fi

ITERATIONS="${2:-200}"
WARMUP="${3:-20}"
CONCURRENCY="${4:-8}"

KEYS_DIR="keys/classical"
PASS="changeit"
PORT="${LATTICEJACK_PORT:-8500}"
RESULTS_DIR="benchmarks/results"
mkdir -p "$RESULTS_DIR"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ 2>/dev/null || echo run)"

echo "=== building ==="
mvn -q -DskipTests package
mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath \
  -Dmdep.outputFile=target/classpath.txt
CP="target/classes:$(cat target/classpath.txt)"

PQC_OPTS=()
if [ "$CONFIG" = "after" ]; then
  PQC_OPTS=(-Djava.util.logging.config.file=scripts/bc-logging.properties -Dlatticejack.tls.pqc=true)
fi

COMMON_OPTS=(
  -Djavax.net.ssl.trustStore="$KEYS_DIR/truststore.jks"
  -Djavax.net.ssl.trustStorePassword="$PASS"
  -Dlatticejack.tls.label="$CONFIG"
  -Dlatticejack.bench.port="$PORT"
  "${PQC_OPTS[@]+"${PQC_OPTS[@]}"}"
)

SERVER_LOG="$(mktemp)"
trap 'rm -f "$SERVER_LOG"' EXIT

run_pass() {
  local mode="$1" connections="$2" csv="${3:-}"
  echo ""
  echo "=== [$CONFIG] $mode pass ($connections connections) ==="

  java "${COMMON_OPTS[@]}" \
    -Djavax.net.ssl.keyStore="$KEYS_DIR/server.jks" \
    -Djavax.net.ssl.keyStorePassword="$PASS" \
    -Dlatticejack.bench.connections="$connections" \
    -cp "$CP" com.latticejack.pqc.BenchmarkServer > "$SERVER_LOG" 2>&1 &
  local server_pid=$!
  sleep 1

  local csv_opt=()
  if [ -n "$csv" ]; then
    csv_opt=(-Dlatticejack.bench.csv="$csv")
  fi

  java "${COMMON_OPTS[@]}" \
    -Djavax.net.ssl.keyStore="$KEYS_DIR/client.jks" \
    -Djavax.net.ssl.keyStorePassword="$PASS" \
    -Dlatticejack.bench.mode="$mode" \
    -Dlatticejack.bench.iterations="$ITERATIONS" \
    -Dlatticejack.bench.warmup="$WARMUP" \
    -Dlatticejack.bench.concurrency="$CONCURRENCY" \
    "${csv_opt[@]+"${csv_opt[@]}"}" \
    -cp "$CP" com.latticejack.pqc.BenchmarkClient

  wait "$server_pid" || true
  tail -3 "$SERVER_LOG" | grep -E "^\[bench-server\]" || true
}

run_pass latency "$((ITERATIONS + WARMUP))" "$RESULTS_DIR/${CONFIG}-latency-${TIMESTAMP}.csv"
run_pass throughput "$((ITERATIONS + WARMUP))"
run_pass bytes "$ITERATIONS" "$RESULTS_DIR/${CONFIG}-bytes-${TIMESTAMP}.csv"

echo ""
echo "=== [$CONFIG] done - results in $RESULTS_DIR ==="
