#!/usr/bin/env bash
# Sourced (not executed) by run-before.sh / run-after.sh to pin JDK 21.
#
# Without this, `java`/`mvn` resolve to whatever's first on PATH, which on a
# dev machine can be a totally different major version per-tool (observed on
# this project: bare `java` -> 11, bare `mvn` -> 26, neither the JDK 21 LTS
# the plan targets and arm64/Graviton production images will actually run).
# A silent version mismatch here is also a live suspect for JSSE/BCJSSE
# behavior differences (see docs/bouncycastle-pqc-notes.md - bcgit/bc-java#2252
# is a JDK 25+-specific BCJSSE regression), so pin it explicitly rather than
# hope the caller's shell happens to have the right one first.
set -euo pipefail

is_jdk21() {
  [ -x "$1/bin/java" ] || return 1
  "$1/bin/java" -version 2>&1 | head -1 | grep -q '"21\.'
}

if [ -n "${JAVA_HOME:-}" ] && is_jdk21 "$JAVA_HOME"; then
  : # caller already has a correct JAVA_HOME, respect it
else
  CANDIDATES=(
    "/opt/homebrew/opt/openjdk@21"
    "/usr/local/opt/openjdk@21"
    "/usr/lib/jvm/java-21-amazon-corretto"
    "/usr/lib/jvm/temurin-21-jdk-amd64"
    "/usr/lib/jvm/temurin-21-jdk-arm64"
    # Plain `apt install openjdk-21-jdk` (Ubuntu/Debian) - what's actually
    # installed on the Azure Arm64 (Cobalt 100) runner, found by testing on
    # real hardware rather than assumed: the earlier candidate list only
    # covered Homebrew/Corretto/Temurin paths.
    "/usr/lib/jvm/java-21-openjdk-arm64"
    "/usr/lib/jvm/java-21-openjdk-amd64"
    "/usr/lib/jvm/java-21-openjdk"
  )
  # Homebrew installs JDKs unlinked under Cellar; opt/openjdk@21 may not
  # exist as a symlink even when the keg does, so check Cellar directly too.
  if [ -d /opt/homebrew/Cellar/openjdk@21 ]; then
    CANDIDATES+=("/opt/homebrew/Cellar/openjdk@21"/*/libexec/openjdk.jdk/Contents/Home)
  fi
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    MAC_21="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    [ -n "$MAC_21" ] && CANDIDATES+=("$MAC_21")
  fi

  FOUND=""
  for c in "${CANDIDATES[@]}"; do
    if is_jdk21 "$c" 2>/dev/null; then
      FOUND="$c"
      break
    fi
  done

  if [ -z "$FOUND" ]; then
    echo "ERROR: JDK 21 not found. This project requires JDK 21 LTS." >&2
    echo "  macOS (Homebrew):        brew install openjdk@21" >&2
    echo "  Amazon Linux (Graviton): sudo dnf install -y java-21-amazon-corretto-devel" >&2
    echo "  Ubuntu (Ampere):         sudo apt install -y openjdk-21-jdk" >&2
    echo "  Or set JAVA_HOME yourself to a JDK 21 install." >&2
    exit 1
  fi
  export JAVA_HOME="$FOUND"
fi

export PATH="$JAVA_HOME/bin:$PATH"
echo "[require-jdk21] using $(java -version 2>&1 | head -1)"
