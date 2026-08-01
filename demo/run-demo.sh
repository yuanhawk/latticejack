#!/usr/bin/env bash
# VM-side wrapper for the Latticejack live-demo feature: builds once, runs
# the four self-verifying demo scripts (run-before.sh, run-after.sh,
# run-nativekem.sh, run-ai.sh) back-to-back, and streams progress to a
# Cloudflare Worker (the other, independently-built half of this feature -
# see demo/worker/) via HTTPS POSTs to $INGEST_URL, so a judge-facing web
# page can poll/render it live.
#
# Usage:
#   run-demo.sh SESSION_ID SESSION_TOKEN
#
# SESSION_ID/SESSION_TOKEN are minted by the Worker's /api/start and passed
# as argv by its Run Command dispatch (see demo/worker/src/azure.ts
# composeRunCommandScript) - this script does not mint or validate them
# itself, it just echoes SESSION_ID back in every ingest event and presents
# SESSION_TOKEN as a bearer credential.
#
# ---------------------------------------------------------------------------
# Configuration (environment variables - nothing below is hardcoded; this
# script expects these to already be set in the VM's environment, e.g. by
# whatever provisioned the VM image, since the Worker's dispatch script
# invokes this script with only the two positional args above):
#
#   INGEST_URL            Required. Where to POST progress, e.g.
#                          https://demo.itinerario.io/api/ingest
#   LATTICEJACK_LLAMA_URL  Optional, default http://127.0.0.1:8090. Where
#                          llama-server listens - this script starts
#                          llama-server bound to this URL's host:port, AND
#                          exports it unchanged for run-ai.sh, which reads
#                          this exact env var and forwards it as
#                          -Dlatticejack.ai.llamaServerUrl (see run-ai.sh /
#                          AiConfig.java - this is that project's existing,
#                          pre-established convention, not a new one).
#   LLAMA_SERVER_BIN      Path to the llama-server binary (e.g.
#                          component-ai/llama.cpp/build/bin/llama-server).
#                          If unset/not executable, llama-server is not
#                          started and the "ai" stage is expected to fail
#                          its own health check honestly (a real, tested
#                          code path - see component-ai/README.md).
#   LLAMA_MODEL_PATH      Path to the GGUF model file (e.g.
#                          component-ai/models/Llama-3.2-1B-Instruct-Q4_0.gguf).
#                          Same fallback behavior as LLAMA_SERVER_BIN above
#                          if unset/missing.
#   LLAMA_CONTEXT_SIZE    Optional, default 4096 - matches the documented
#                          OOM fix in benchmarks/ai-inference-pqc/README.md
#                          ("Real hardware-resource problem" caveat): the
#                          Azure target's 3.8GB RAM is tight for LLM
#                          inference + JVM + build tooling running
#                          concurrently, and llama-server's default context
#                          size (inherited from the model, ~79872 tokens)
#                          alone consumed ~3.35GB RSS and got OOM-killed.
#                          `-c 4096` cut that to ~1.68GB.
#
# ---------------------------------------------------------------------------
# Ingest POST shape: this script sends ONE JSON object per HTTP POST to
# $INGEST_URL, as:
#
#   curl -sS -X POST \
#     -H "Authorization: Bearer $SESSION_TOKEN" \
#     -H "Content-Type: application/json" \
#     --data-binary @- "$INGEST_URL"
#
# The exact event shapes below are NOT invented by this script - they are
# copied from demo/worker/src/types.ts ("IngestEvent" and friends) and
# cross-checked against demo/worker/src/orchestrator.ts's handleIngest(),
# the actual server-side implementation already present in this repo (the
# Worker half was built in parallel and got there first; its types.ts
# explicitly says "if [run-demo.sh] now sends something different,
# reconcile the two rather than silently diverging" - this script conforms
# to that existing contract rather than inventing a divergent one):
#
#   Every event: {"session_id": "<SESSION_ID>", "seq": <int, 0-based,
#                 monotonically increasing per session>, "type": "..."}
#   stage_start:  + "stage": "before"|"after"|"nativekem"|"ai"
#   stage_end:    + "stage": "...", "status": "done"|"failed",
#                   "exit_code": <int, optional>
#   log_chunk:    + "stage": "..." (optional - omitted for build-phase
#                   output, which precedes all four stages),
#                   "text": "<raw bytes appended since the last chunk>"
#   verification: + "stage": "..." (optional), "text": "<the literal
#                   VERIFIED:/VERIFICATION FAILED: block this script or one
#                   of the four demo scripts printed, verbatim - the
#                   frontend's verdict panel only ever quotes these, never
#                   synthesizes one, per types.ts's own comment>
#   done:         + "summary": {"handshake_ms":.., "request_to_response_ms":..,
#                   "ratio":..} (optional, all fields optional - populated
#                   from the "ai" stage's PqcAiTlsClient timing line when
#                   available, omitted entirely otherwise). Sent once, at
#                   the end, REGARDLESS of whether individual stages
#                   failed - per this project's design decision that the
#                   four stages are independent claims, so "the wrapper
#                   completed" (done) is orthogonal to "did every stage
#                   pass" (tracked per-stage via stage_end.status).
#   failed:       + "reason": "<string>", "stage": "..." (optional). Sent
#                   ONLY for a catastrophic, nothing-downstream-can-work
#                   failure (the up-front `mvn package` itself failing, or
#                   this script crashing unexpectedly) - NOT for an
#                   individual stage failing (that's a stage_end with
#                   status:"failed", per the "independent claims" design
#                   above).
#
# No session_token is ever included in a POST body - only in the
# Authorization header, matching orchestrator.ts's handleIngest().
#
# ---------------------------------------------------------------------------
# Spawn-reliability note: this script is expected to be launched via
# systemd-run/setsid (see demo/worker/src/azure.ts composeRunCommandScript)
# rather than `nohup ... & disown` from a shell whose session may be torn
# down - this script does not assume anything about its parent process
# still being alive, and the `trap ... EXIT` below fires regardless of how
# the process ends (normal completion, a `timeout 300` killing a stage, or
# a signal) so llama-server always gets cleaned up and a final done/failed
# marker always gets sent (best-effort; a dead ingest endpoint does not
# hang or crash this script - see post_event()'s curl timeout below).
#
# Dependencies: bash, curl, python3 (used only for correct JSON
# construction/parsing of arbitrary log text - the same "simple, dependable
# tool" rationale this project already applies to python3 in
# component-c/cbom/*.py).
set -uo pipefail
# Deliberately NOT `set -e`: per this project's design, a stage failing (or
# even the whole `timeout 300` on a stage firing) must NOT abort this
# script - the four stages are independent claims and one failing doesn't
# invalidate the others. Every risky step below checks its own exit status
# explicitly instead of relying on set -e.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

SESSION_ID="${1:-}"
SESSION_TOKEN="${2:-}"
if [ -z "$SESSION_ID" ] || [ -z "$SESSION_TOKEN" ]; then
  echo "usage: $0 SESSION_ID SESSION_TOKEN" >&2
  exit 2
fi

if [ -z "${INGEST_URL:-}" ]; then
  echo "ERROR: INGEST_URL must be set (e.g. https://demo.itinerario.io/api/ingest)." >&2
  exit 2
fi

LATTICEJACK_LLAMA_URL="${LATTICEJACK_LLAMA_URL:-http://127.0.0.1:8090}"
export LATTICEJACK_LLAMA_URL
LLAMA_CONTEXT_SIZE="${LLAMA_CONTEXT_SIZE:-4096}"

LOG_DIR="$(mktemp -d "${TMPDIR:-/tmp}/latticejack-demo.XXXXXX")"
echo "[run-demo] session=$SESSION_ID log_dir=$LOG_DIR"

# GNU coreutils `timeout` (the real Azure/Ubuntu target has it); fall back
# to `gtimeout` (Homebrew coreutils) or, if neither exists (e.g. a bare
# macOS dev box used only for local testing of this script), a manual
# background watchdog - see run_stage() below.
TIMEOUT_BIN=""
if command -v timeout >/dev/null 2>&1; then
  TIMEOUT_BIN="timeout"
elif command -v gtimeout >/dev/null 2>&1; then
  TIMEOUT_BIN="gtimeout"
fi

# ---------------------------------------------------------------------------
# Ingest POSTing. SEQ is persisted to a file, not a plain shell variable,
# because several call sites below invoke functions that post events from
# inside a `$( ... )` command substitution or `< <( ... )` process
# substitution (both run in a subshell) - a plain variable increment would
# be lost when that subshell exits. A file survives across subshells.
# ---------------------------------------------------------------------------

SEQ_FILE="$LOG_DIR/seq"
echo 0 > "$SEQ_FILE"
INGEST_CURL_LOG="$LOG_DIR/ingest-curl.log"

next_seq() {
  local s
  s="$(cat "$SEQ_FILE")"
  echo $((s + 1)) > "$SEQ_FILE"
  echo "$s"
}

# Low-level: build the documented JSON shape for one event and POST it.
# Never lets a slow/unreachable ingest endpoint hang this script (short
# curl timeout) and never aborts the script on failure (best-effort).
_raw_post() {
  local ev_type="$1" stage="$2" status="$3" exit_code="$4" text="$5" reason="$6" handshake_ms="$7" req_ms="$8" ratio="$9"
  local seq
  seq="$(next_seq)"
  python3 - "$SESSION_ID" "$seq" "$ev_type" "$stage" "$status" "$exit_code" "$text" "$reason" "$handshake_ms" "$req_ms" "$ratio" <<'PYEOF' | \
    curl -sS -m 10 -X POST \
      -H "Authorization: Bearer $SESSION_TOKEN" \
      -H "Content-Type: application/json" \
      --data-binary @- \
      "$INGEST_URL" >>"$INGEST_CURL_LOG" 2>&1
import sys, json

(session_id, seq, ev_type, stage, status, exit_code, text, reason,
 handshake_ms, req_ms, ratio) = sys.argv[1:12]

obj = {"session_id": session_id, "seq": int(seq), "type": ev_type}
if stage:
    obj["stage"] = stage
if ev_type == "stage_end":
    obj["status"] = status
    if exit_code != "":
        obj["exit_code"] = int(exit_code)
if ev_type in ("log_chunk", "verification"):
    obj["text"] = text
if ev_type == "failed":
    obj["reason"] = reason
if ev_type == "done":
    summary = {}
    if handshake_ms != "":
        summary["handshake_ms"] = float(handshake_ms)
    if req_ms != "":
        summary["request_to_response_ms"] = float(req_ms)
    if ratio != "":
        summary["ratio"] = float(ratio)
    if summary:
        obj["summary"] = summary

sys.stdout.write(json.dumps(obj))
PYEOF
  return 0
}

post_stage_start() { _raw_post "stage_start" "$1" "" "" "" "" "" "" ""; }
post_stage_end() {
  local stage="$1" exit_code="$2" status
  if [ "$exit_code" = "0" ]; then status="done"; else status="failed"; fi
  _raw_post "stage_end" "$stage" "$status" "$exit_code" "" "" "" "" ""
}
post_log_chunk() { _raw_post "log_chunk" "$1" "" "" "$2" "" "" "" ""; }
post_verification() { _raw_post "verification" "$1" "" "" "$2" "" "" "" ""; }
post_failed() { _raw_post "failed" "${2:-}" "" "" "" "$1" "" "" ""; }
post_done() { _raw_post "done" "" "" "" "" "" "${1:-}" "${2:-}" "${3:-}"; }

# ---------------------------------------------------------------------------
# Cleanup / final marker - fires exactly once no matter how this script
# ends (normal completion, a stage's `timeout 300` firing, or a signal).
# ---------------------------------------------------------------------------

LLAMA_PID=""
FINISHED=0

stop_llama_server() {
  if [ -n "$LLAMA_PID" ] && kill -0 "$LLAMA_PID" 2>/dev/null; then
    echo "[run-demo] stopping llama-server (pid=$LLAMA_PID)"
    kill "$LLAMA_PID" 2>/dev/null || true
    wait "$LLAMA_PID" 2>/dev/null || true
  fi
}

# Parses PqcAiTlsClient's own printed timing line (see run-ai.sh /
# component-ai/README.md §5) out of the "ai" stage's captured log, if it's
# there. Prints three tab-separated fields (handshake_ms, req_ms, ratio),
# any/all empty if not found - never fails the caller.
parse_ai_summary() {
  local log_file="$1"
  python3 - "$log_file" <<'PYEOF'
import re, sys
path = sys.argv[1]
try:
    with open(path, "r", errors="replace") as f:
        content = f.read()
except OSError:
    content = ""
h = re.search(r"handshake time\s*=\s*([\d.]+) ms", content)
r = re.search(r"request-to-response\s*=\s*([\d.]+) ms", content)
ratio = re.search(r"handshake / inference ratio = ([\d.]+)", content)
print("\t".join([
    h.group(1) if h else "",
    r.group(1) if r else "",
    ratio.group(1) if ratio else "",
]))
PYEOF
}

finish_done() {
  if [ "$FINISHED" = "1" ]; then return 0; fi
  FINISHED=1
  stop_llama_server
  local h="" r="" ratio="" ai_log="$LOG_DIR/ai.log"
  if [ -f "$ai_log" ]; then
    IFS=$'\t' read -r h r ratio < <(parse_ai_summary "$ai_log")
  fi
  echo "[run-demo] === done (handshake_ms=$h request_to_response_ms=$r ratio=$ratio) ==="
  post_done "$h" "$r" "$ratio"
}

finish_failed() {
  local reason="$1" stage="${2:-}"
  if [ "$FINISHED" = "1" ]; then return 0; fi
  FINISHED=1
  stop_llama_server
  echo "[run-demo] === failed: $reason ===" >&2
  post_failed "$reason" "$stage"
}

# Fallback for any exit this script didn't already handle explicitly
# (signal, unexpected error under `set -u`/`pipefail`, etc). No-ops if
# finish_done/finish_failed already ran (FINISHED guard).
trap 'finish_failed "run-demo.sh exited unexpectedly (trap fallback, exit code $?) - see wrapper output and '"$LOG_DIR"' on the VM."' EXIT

# ---------------------------------------------------------------------------
# Pin JDK 21 - replicates scripts/require-jdk21.sh's own resolution by
# sourcing it directly (single source of truth, not a fork) rather than
# re-deriving the candidate list here.
# ---------------------------------------------------------------------------

source "$REPO_ROOT/scripts/require-jdk21.sh"
# require-jdk21.sh does `set -euo pipefail` itself (correct for the four
# demo scripts it's designed to be sourced by, which already want errexit)
# - but sourcing it runs those `set` commands directly in THIS shell too,
# which would silently turn on `-e` here as a side effect. That would
# break this wrapper's whole design: a failed stage's `wait "$pid"`
# returning non-zero would immediately kill the ENTIRE wrapper under
# errexit, before it could even record the failure and move on to the
# next stage (found by direct testing - a stage's `wait` returning 1
# aborted the whole script with no stage_end/done event ever sent).
# Explicitly restore this wrapper's own intended option set right after:
# `set -uo pipefail` alone would NOT be enough - it only adds -u/pipefail,
# it does not touch -e's current state either way, so an -e turned on by
# the sourced script would silently stay on. `set +e` is the part that
# actually matters here.
set +e
set -uo pipefail

# ---------------------------------------------------------------------------
# Step 1: start llama-server (background, --verbose, context-size capped),
# wait for /health - do NOT let stage 4 (run-ai.sh) start before this is
# confirmed ready or has definitively timed out.
# ---------------------------------------------------------------------------

_hostport="${LATTICEJACK_LLAMA_URL#*://}"
_hostport="${_hostport%%/*}"
LLAMA_HOST="${_hostport%%:*}"
LLAMA_PORT="${_hostport##*:}"

LLAMA_LOG="$LOG_DIR/llama-server.log"
LLAMA_HEALTHY=0

echo "[run-demo] === starting llama-server ($LATTICEJACK_LLAMA_URL, ctx=$LLAMA_CONTEXT_SIZE) ==="
if [ -n "${LLAMA_SERVER_BIN:-}" ] && [ -x "$LLAMA_SERVER_BIN" ] && \
   [ -n "${LLAMA_MODEL_PATH:-}" ] && [ -f "$LLAMA_MODEL_PATH" ]; then
  "$LLAMA_SERVER_BIN" -m "$LLAMA_MODEL_PATH" --host "$LLAMA_HOST" --port "$LLAMA_PORT" \
    -c "$LLAMA_CONTEXT_SIZE" --verbose > "$LLAMA_LOG" 2>&1 &
  LLAMA_PID=$!
  echo "[run-demo] llama-server started, pid=$LLAMA_PID, log=$LLAMA_LOG"

  HEALTH_TIMEOUT_S=60
  WAITED=0
  while [ "$WAITED" -lt "$HEALTH_TIMEOUT_S" ]; do
    if curl -sf --max-time 2 "$LATTICEJACK_LLAMA_URL/health" >/dev/null 2>&1; then
      LLAMA_HEALTHY=1
      break
    fi
    if ! kill -0 "$LLAMA_PID" 2>/dev/null; then
      echo "[run-demo] llama-server process exited early - see $LLAMA_LOG" >&2
      break
    fi
    sleep 2
    WAITED=$((WAITED + 2))
  done
else
  echo "[run-demo] LLAMA_SERVER_BIN/LLAMA_MODEL_PATH not set or not found - not starting llama-server." >&2
fi

# ---------------------------------------------------------------------------
# Step 2: if healthy, grep the verbose log for the two positive KleidiAI
# engagement signals this project already established as correct
# verification (benchmarks/ai-inference-pqc/README.md "KleidiAI
# verification evidence"): "KLEIDIAI = 1" and a non-zero "CPU_KLEIDIAI
# model buffer size" line. POST as an extra verification beat.
# ---------------------------------------------------------------------------

if [ "$LLAMA_HEALTHY" = "1" ]; then
  echo "[run-demo] llama-server healthy after ${WAITED}s."
  KLEIDIAI_OK=1
  DETAIL=""
  if grep -q "KLEIDIAI = 1" "$LLAMA_LOG"; then
    DETAIL="KLEIDIAI = 1 (backend compiled in and detected). "
  else
    DETAIL="\"KLEIDIAI = 1\" NOT found in system_info. "
    KLEIDIAI_OK=0
  fi
  BUF_LINE="$(grep "CPU_KLEIDIAI model buffer size" "$LLAMA_LOG" | tail -1 || true)"
  if [ -n "$BUF_LINE" ] && ! printf '%s' "$BUF_LINE" | grep -q "0.00 MiB"; then
    DETAIL="$DETAIL$(printf '%s' "$BUF_LINE" | sed -e 's/^[^:]*: //')"
  else
    DETAIL="${DETAIL}\"CPU_KLEIDIAI model buffer size\" NOT found, or read 0.00 MiB (no tensor data actually placed in KleidiAI's buffer)."
    KLEIDIAI_OK=0
  fi
  if [ "$KLEIDIAI_OK" = "1" ]; then
    VLINE="VERIFIED: llama-server KleidiAI engagement confirmed - $DETAIL"
  else
    VLINE="VERIFICATION FAILED: llama-server KleidiAI engagement NOT confirmed - $DETAIL"
  fi
  echo "[run-demo] $VLINE"
  post_verification "ai" "$VLINE"
else
  echo "[run-demo] llama-server did NOT become healthy - stage 'ai' is expected to fail its own health check honestly." >&2
  post_verification "ai" "VERIFICATION FAILED: llama-server never reported healthy at $LATTICEJACK_LLAMA_URL/health - see component-ai/README.md for how it's started; stage 'ai' will report its own health-check failure."
fi

# ---------------------------------------------------------------------------
# Step 3: one shared `mvn package` + build-classpath up front, then run the
# four scripts in order under SKIP_BUILD=1. If THIS build fails, abort
# immediately (nothing downstream can possibly succeed) - this is the one
# case that gets a top-level `failed` event rather than a per-stage one.
# ---------------------------------------------------------------------------

echo "[run-demo] === building (shared across all four stages) ==="
BUILD_LOG="$LOG_DIR/build.log"
if ! mvn -q -DskipTests package > "$BUILD_LOG" 2>&1; then
  cat "$BUILD_LOG"
  post_log_chunk "" "$(cat "$BUILD_LOG")"
  finish_failed "mvn package failed - nothing downstream can possibly succeed; see build log."
  exit 1
fi
if ! mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath \
     -Dmdep.outputFile=target/classpath.txt >> "$BUILD_LOG" 2>&1; then
  cat "$BUILD_LOG"
  post_log_chunk "" "$(cat "$BUILD_LOG")"
  finish_failed "mvn build-classpath failed - nothing downstream can possibly succeed; see build log."
  exit 1
fi
post_log_chunk "" "$(cat "$BUILD_LOG")"
echo "[run-demo] build OK."
export SKIP_BUILD=1

# ---------------------------------------------------------------------------
# Extracts each VERIFIED:/VERIFICATION FAILED: block from a completed
# stage's log (paragraphs separated by a blank line, same style all four
# scripts already print in) and POSTs each as its own "verification" event
# - the frontend's verdict panel quotes these literal blocks verbatim.
# ---------------------------------------------------------------------------

extract_and_post_verifications() {
  local stage="$1" log_file="$2"
  while IFS= read -r -d '' block; do
    post_verification "$stage" "$block"
  done < <(python3 - "$log_file" <<'PYEOF'
import sys
path = sys.argv[1]
try:
    with open(path, "r", errors="replace") as f:
        content = f.read()
except OSError:
    sys.exit(0)
for p in content.split("\n\n"):
    p = p.strip("\n")
    if p.startswith("VERIFIED:") or p.startswith("VERIFICATION FAILED:"):
        sys.stdout.write(p)
        sys.stdout.write("\0")
PYEOF
  )
}

# Reads new bytes appended to $log_file since $offset, POSTs them as a
# log_chunk if non-empty, and prints the new offset (bytes) for the caller
# to pass back in on the next call.
stream_new_bytes() {
  local stage="$1" log_file="$2" offset="$3"
  local size
  size="$(wc -c < "$log_file" 2>/dev/null || echo 0)"
  size="${size//[[:space:]]/}"
  [ -z "$size" ] && size=0
  if [ "$size" -gt "$offset" ]; then
    local chunk
    chunk="$(tail -c +"$((offset + 1))" "$log_file" 2>/dev/null || true)"
    if [ -n "$chunk" ]; then
      post_log_chunk "$stage" "$chunk"
    fi
  fi
  echo "$size"
}

# Runs one stage script under a 300s timeout, streaming its combined
# stdout+stderr to $LOG_DIR/$stage.log and POSTing new bytes roughly every
# 2s while it runs, plus a stage_start/stage_end pair and a verification
# event per VERIFIED:/VERIFICATION FAILED: block found at the end. Records
# the exit code in $LOG_DIR/$stage.exit for the final summary. Never
# aborts the caller regardless of the stage's outcome (see the "one stage
# failing doesn't invalidate the others" design note up top).
run_stage() {
  local stage="$1" script_path="$2"
  local log_file="$LOG_DIR/${stage}.log"
  : > "$log_file"
  echo ""
  echo "[run-demo] === stage: $stage ==="
  post_stage_start "$stage"

  if [ -n "$TIMEOUT_BIN" ]; then
    "$TIMEOUT_BIN" 300 "$script_path" > "$log_file" 2>&1 &
  else
    "$script_path" > "$log_file" 2>&1 &
  fi
  local pid=$!

  local watchdog_pid=""
  if [ -z "$TIMEOUT_BIN" ]; then
    # Portable fallback for a dev box without GNU coreutils' `timeout`/
    # `gtimeout` (the real Azure/Ubuntu target always has real `timeout`).
    ( sleep 300; kill -0 "$pid" 2>/dev/null && kill "$pid" 2>/dev/null ) &
    watchdog_pid=$!
  fi

  local offset=0
  while kill -0 "$pid" 2>/dev/null; do
    sleep 2
    offset="$(stream_new_bytes "$stage" "$log_file" "$offset")"
  done
  wait "$pid"
  local exit_code=$?
  [ -n "$watchdog_pid" ] && kill "$watchdog_pid" 2>/dev/null
  offset="$(stream_new_bytes "$stage" "$log_file" "$offset")"

  cat "$log_file"
  extract_and_post_verifications "$stage" "$log_file"
  post_stage_end "$stage" "$exit_code"
  echo "$exit_code" > "$LOG_DIR/${stage}.exit"
  echo "[run-demo] === stage $stage: exit=$exit_code ==="
}

# ---------------------------------------------------------------------------
# Run the four stages in order, each independently.
# ---------------------------------------------------------------------------

STAGE_NAMES=(before after nativekem ai)
STAGE_SCRIPTS=(
  "$REPO_ROOT/run-before.sh"
  "$REPO_ROOT/run-after.sh"
  "$REPO_ROOT/run-nativekem.sh"
  "$REPO_ROOT/run-ai.sh"
)

for i in "${!STAGE_NAMES[@]}"; do
  run_stage "${STAGE_NAMES[$i]}" "${STAGE_SCRIPTS[$i]}"
done

echo ""
echo "[run-demo] === all stages complete ==="
for name in "${STAGE_NAMES[@]}"; do
  ec="$(cat "$LOG_DIR/${name}.exit" 2>/dev/null || echo "?")"
  echo "[run-demo]   $name: exit=$ec"
done

finish_done
exit 0
