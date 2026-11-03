#!/usr/bin/env bash
#
# JVM regression harness for the weightlog app (pure-Java protocol / crypto / state machine /
# formula checks; see AGENTS.md "测试与验证").
#
# Usage:
#   bash test/run.sh        # from anywhere
#
# Requirements: a local JDK 17 `javac`/`java` on PATH. No Android SDK, no Gradle, no network,
# no third-party jars. Everything is compiled into a throwaway directory under /tmp and removed
# on exit.
#
# Test-only sources compiled here:
#   - test/org/json/{JSONObject,JSONArray}.java : minimal org.json shim (desktop JDK lacks it)
#   - test/com/weightlog/app/data/WeightDb.java : stub providing the pure isoTime() helper
# Production sources compiled here (no android.* usage):
#   - src/com/weightlog/app/data/Measurement.java
#   - src/com/weightlog/app/scale/{MiBeaconDecoder,WeighSession,BodyComposition,RescuePolicy}.java

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WL_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

FILES=(
  # test-only shim first: Measurement/WeighSession import org.json
  "$WL_DIR/test/org/json/JSONObject.java"
  "$WL_DIR/test/org/json/JSONArray.java"
  "$WL_DIR/test/com/weightlog/app/data/WeightDb.java"
  # production sources under test (pure Java only)
  "$WL_DIR/src/com/weightlog/app/data/Measurement.java"
  "$WL_DIR/src/com/weightlog/app/scale/MiBeaconDecoder.java"
  "$WL_DIR/src/com/weightlog/app/scale/WeighSession.java"
  "$WL_DIR/src/com/weightlog/app/scale/BodyComposition.java"
  "$WL_DIR/src/com/weightlog/app/scale/RescuePolicy.java"
  # the harness
  "$WL_DIR/test/ReviewTest.java"
)

for f in "${FILES[@]}"; do
  if [[ ! -f "$f" ]]; then
    echo "error: missing source file: $f" >&2
    exit 1
  fi
done

for tool in javac java; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "error: '$tool' not found on PATH (JDK 17 required)" >&2
    exit 1
  fi
done

TMP_DIR="$(mktemp -d /tmp/weightlog-review.XXXXXX)"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

echo "weightlog JVM harness"
echo "app: $WL_DIR"
echo "tmp:  $TMP_DIR"
echo

# -source/-target 8 matches build.sh; -Xlint:-options silences the JDK 17 "source 8 is obsolete"
# notice. No -bootclasspath / android.jar is used: only pure-Java sources are compiled.
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d "$TMP_DIR" "${FILES[@]}"

java -cp "$TMP_DIR" ReviewTest
