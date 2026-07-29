#!/usr/bin/env bash
# Standalone BC-vs-JDK25-built-in ML-KEM microbenchmark. Deliberately NOT
# part of the JDK-21-pinned main project/Maven build - requires JDK 25+ for
# the javax.crypto.KEM "ML-KEM-768" SunJCE provider (JEP 496). Needs BC on
# the classpath for the "BC" side; reuses the main project's already-resolved
# ~/.m2 jar rather than duplicating a dependency declaration.
#
# Usage: ./run.sh [path-to-jdk25-home]
set -euo pipefail
cd "$(dirname "$0")"

JDK25_HOME="${1:-}"
if [ -z "$JDK25_HOME" ]; then
  for candidate in /opt/homebrew/opt/openjdk@25 /usr/lib/jvm/java-25-amazon-corretto \
                   /usr/lib/jvm/temurin-25-jdk-arm64 /usr/lib/jvm/temurin-25-jdk-amd64 \
                   /usr/lib/jvm/java-25-openjdk-arm64 /usr/lib/jvm/java-25-openjdk-amd64; do
    if [ -x "$candidate/bin/java" ]; then
      JDK25_HOME="$candidate"
      break
    fi
  done
fi
if [ -z "$JDK25_HOME" ] || [ ! -x "$JDK25_HOME/bin/java" ]; then
  echo "ERROR: JDK 25 not found. Pass its home dir as an argument, or install:" >&2
  echo "  macOS (Homebrew):        brew install openjdk@25" >&2
  echo "  Amazon Linux (Graviton): sudo dnf install -y java-25-amazon-corretto-devel" >&2
  echo "  Ubuntu (Ampere/Azure):   sudo apt install -y openjdk-25-jdk" >&2
  exit 1
fi
echo "[mlkem-microbench] using $("$JDK25_HOME/bin/java" -version 2>&1 | head -1)"

BC_JAR="$(find ~/.m2/repository/org/bouncycastle/bcprov-jdk18on -name 'bcprov-jdk18on-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort -V | tail -1)"
if [ -z "$BC_JAR" ]; then
  echo "ERROR: bcprov-jdk18on jar not found in ~/.m2 - run the main project's build first (mvn package) to resolve it." >&2
  exit 1
fi
echo "[mlkem-microbench] using $BC_JAR"

"$JDK25_HOME/bin/javac" -cp "$BC_JAR" -d target MLKemMicrobench.java
"$JDK25_HOME/bin/java" -cp "target:$BC_JAR" MLKemMicrobench
