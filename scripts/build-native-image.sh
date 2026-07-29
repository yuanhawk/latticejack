#!/usr/bin/env bash
# Builds GraalVM native-image executables of the "after" (hybrid PQC) mTLS
# reference service - see native-image/README.md for what this is, why the
# flags are what they are, and the two GraalVM/BouncyCastle incompatibilities
# each one works around (JCE provider build-time verification, and BC's own
# class-initializer order once org.bouncycastle is forced to build-time init).
#
# Requires GRAALVM_HOME to point at a GraalVM JDK 21 install (native-image
# is architecture-specific - rebuild on each target OS/arch, don't copy
# binaries between them). Get one:
#   macOS (aarch64):  https://download.oracle.com/graalvm/21/latest/graalvm-jdk-21_macos-aarch64_bin.tar.gz
#   Linux (aarch64):  https://download.oracle.com/graalvm/21/latest/graalvm-jdk-21_linux-aarch64_bin.tar.gz
# extract anywhere user-writable, then:
#   export GRAALVM_HOME=/path/to/graalvm-jdk-21.../Contents/Home   # macOS
#   export GRAALVM_HOME=/path/to/graalvm-jdk-21...                 # Linux
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${GRAALVM_HOME:-}" ] || [ ! -x "$GRAALVM_HOME/bin/native-image" ]; then
  echo "ERROR: GRAALVM_HOME must point at a GraalVM JDK 21 install (bin/native-image not found)." >&2
  exit 1
fi

source ./scripts/require-jdk21.sh
echo "=== building project jar (JDK 21, for classpath capture) ==="
mvn -q -DskipTests package
mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath \
  -Dmdep.outputFile=target/classpath.txt
CP="$(pwd)/target/classes:$(cat target/classpath.txt)"

echo "=== compiling native-image/BouncyCastleFeature.java (GraalVM's own javac) ==="
FEATURE_CLASSES="target/native-image-feature-classes"
mkdir -p "$FEATURE_CLASSES"
"$GRAALVM_HOME/bin/javac" -cp "$CP" -d "$FEATURE_CLASSES" native-image/BouncyCastleFeature.java
CP="$CP:$(pwd)/$FEATURE_CLASSES"

BUILD_FLAGS=(
  -H:ConfigurationFileDirectories="$(pwd)/native-image/config"
  --features=BouncyCastleFeature
  --initialize-at-build-time=org.bouncycastle
  --initialize-at-run-time='org.bouncycastle.jcajce.provider.drbg.DRBG$Default,org.bouncycastle.jcajce.provider.drbg.DRBG$NonceAndIV'
  --no-fallback
)

OUT_DIR="target/native-image"
mkdir -p "$OUT_DIR"

echo "=== building echo-server-native ==="
"$GRAALVM_HOME/bin/native-image" -cp "$CP" "${BUILD_FLAGS[@]}" \
  -o "$OUT_DIR/echo-server-native" com.latticejack.pqc.EchoTlsServer

echo "=== building echo-client-native ==="
"$GRAALVM_HOME/bin/native-image" -cp "$CP" "${BUILD_FLAGS[@]}" \
  -o "$OUT_DIR/echo-client-native" com.latticejack.pqc.EchoTlsClient

echo ""
echo "Built: $OUT_DIR/echo-server-native, $OUT_DIR/echo-client-native"
