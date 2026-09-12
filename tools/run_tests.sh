#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# VIDX local test runner — zero-network, dependency-free JVM unit tests.
#
# Requires (see docs/DEVELOPMENT.md for how the sandbox obtains these):
#   KOTLINC_BIN   path to the Kotlin 2.4.x compiler (kotlinc)
#   ANDROID_JAR   path to an android.jar (any API level ≥ 26)
#   AAPT2_BIN     path to an aapt2 binary (any recent version)
#   JAVA_HOME     any JDK/JRE 11+
#
# This ALSO compiles the app resources (aapt2) so the whole main source tree,
# including the R references, is validated on every run.
#
# Usage: tools/run_tests.sh
# ---------------------------------------------------------------------------
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLINC_BIN="${KOTLINC_BIN:-$REPO_ROOT/../android-toolchain/kc/package/bin/kotlinc}"
KOTLIN_LIB="$(dirname "$KOTLINC_BIN")/../lib"
ANDROID_JAR="${ANDROID_JAR:-$REPO_ROOT/../android-toolchain/sable/android-platforms-master/android-35/android.jar}"
AAPT2_BIN="${AAPT2_BIN:-$REPO_ROOT/../android-toolchain/aapt3/package/bin/x64/linux/aapt2}"
OUT="$REPO_ROOT/tools/out/test-classes"

[ -x "$KOTLINC_BIN" ] || { echo "kotlinc not found at $KOTLINC_BIN (set KOTLINC_BIN)"; exit 2; }
[ -f "$ANDROID_JAR" ] || { echo "android.jar not found at $ANDROID_JAR (set ANDROID_JAR)"; exit 2; }
[ -x "$AAPT2_BIN" ] || { echo "aapt2 not found at $AAPT2_BIN (set AAPT2_BIN)"; exit 2; }

rm -rf "$OUT"
mkdir -p "$OUT/gen"

MAIN_SRC="$REPO_ROOT/app/src/main/java"
TEST_SRC="$REPO_ROOT/app/src/test/java"
RES_DIR="$REPO_ROOT/app/src/main/res"
MANIFEST="$REPO_ROOT/app/src/main/AndroidManifest.xml"

echo "== Compiling resources (aapt2) =="
"$AAPT2_BIN" compile --dir "$RES_DIR" -o "$OUT/res.zip" || exit 3
"$AAPT2_BIN" link \
  -o "$OUT/resources.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --auto-add-overlay || exit 3

echo "== Compiling main + test sources =="
"$KOTLINC_BIN" \
  -jvm-target 1.8 \
  -Xlambdas=class \
  -no-reflect \
  -classpath "$KOTLIN_LIB/kotlin-stdlib.jar:$ANDROID_JAR" \
  -d "$OUT/vidx-tests.jar" \
  $(find "$MAIN_SRC" -name "*.kt" | tr '\n' ' ') \
  $(find "$TEST_SRC" -name "*.kt" ! -name "JUnitBridge.kt" | tr '\n' ' ') \
  $(find "$OUT/gen" -name "R.java" | tr '\n' ' ') || exit 3

echo "== Running suite =="
java -cp "$OUT/vidx-tests.jar:$KOTLIN_LIB/kotlin-stdlib.jar:$ANDROID_JAR" \
  com.vidx.testlib.TestMain
