#!/usr/bin/env bash
# B1 characterization harness (arm-hackathon-plan.md §3 Component B1): runs
# latency/throughput/bytes-on-wire passes against the classical ("before"),
# hybrid PQC ("after"), or hybrid PQC with ML-KEM-768 routed through
# mlkem-native's NEON FFM path ("after-native", see
# src/main/java/com/latticejack/pqc/nativekem/) config, writing CSVs under
# benchmarks/results/ for later before-vs-after (and, in Component B2,
# naive-vs-tuned) comparison.
#
# Each pass restarts BenchmarkServer fresh rather than sharing one long-lived
# server across passes, matching this repo's existing run-before.sh/
# run-after.sh style, and keeping each pass's connection count exact.
#
# Usage: ./run-benchmark.sh {before|after|after-native} [iterations] [warmup] [concurrency]
set -euo pipefail
cd "$(dirname "$0")"

source ./scripts/require-jdk21.sh
./scripts/gen-classical-keys.sh

CONFIG="${1:-}"
if [ "$CONFIG" != "before" ] && [ "$CONFIG" != "after" ] && [ "$CONFIG" != "after-native" ]; then
  echo "usage: ./run-benchmark.sh {before|after|after-native} [iterations] [warmup] [concurrency]" >&2
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
  # Deliberately NOT enabling scripts/bc-logging.properties here, unlike
  # run-after.sh: that FINEST-level logging is essential for verifying
  # negotiation there, but printing thousands of debug lines per handshake
  # would itself perturb the very latency/CPU numbers this script exists to
  # measure cleanly. Found by testing, not by inspection: an earlier version
  # of this script had it on unconditionally for every "after" pass,
  # contaminating timed measurements with logging overhead - see
  # docs/bouncycastle-pqc-notes.md.
  PQC_OPTS=(-Dlatticejack.tls.pqc=true)
elif [ "$CONFIG" = "after-native" ]; then
  # Same as "after" plus routing ML-KEM-768 through mlkem-native's NEON FFM
  # path (see run-nativekem.sh / NativeMlkemProvider) - trace deliberately
  # OFF here (unlike run-nativekem.sh, which turns it on to positively
  # verify the native path fired): per-handshake tracing would perturb the
  # very timing numbers this script exists to measure, same reasoning as
  # the "after" branch's logging note above. Only run-nativekem.sh, whose
  # whole purpose is verification rather than clean measurement, needs
  # trace on.
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
  PQC_OPTS=(
    -Dlatticejack.tls.pqc=true
    -Dlatticejack.tls.nativekem=true
    -Dlatticejack.nativekem.lib="$NATIVEKEM_LIB"
  )
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

  # --enable-preview: not used by the before/after/after-native benchmark
  # code paths themselves (only src/main/java/com/latticejack/pqc/nativekem/
  # uses java.lang.foreign), but required at runtime for EVERY class in the
  # module once pom.xml's compiler plugin turns it on module-wide - see
  # pom.xml's maven-compiler-plugin comment.
  java --enable-preview "${COMMON_OPTS[@]}" \
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

  java --enable-preview "${COMMON_OPTS[@]}" \
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

# BenchmarkClient's resumption mode uses its own warmup calc
# (max(5, iterations/10) pairs) independent of $WARMUP above - mirror it
# here so the server accepts exactly enough connections (2 per pair: full +
# resumed-attempt) and neither hangs waiting for more nor gets extras
# refused after the client's done.
RESUMPTION_WARMUP_PAIRS=$(( ITERATIONS / 10 > 5 ? ITERATIONS / 10 : 5 ))
RESUMPTION_CONNECTIONS=$(( (RESUMPTION_WARMUP_PAIRS + ITERATIONS) * 2 ))
run_pass resumption "$RESUMPTION_CONNECTIONS" "$RESULTS_DIR/${CONFIG}-resumption-${TIMESTAMP}.csv"

echo ""
echo "=== [$CONFIG] done - results in $RESULTS_DIR ==="
