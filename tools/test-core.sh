#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
kotlinc "$ROOT"/app/src/main/java/com/itaymatza/carcallrouter/core/*.kt \
 "$ROOT/app/src/test/java/com/itaymatza/carcallrouter/core/PolicyCases.kt" \
 "$ROOT/tools/CoreTestsMain.kt" -include-runtime -d "$BUILD/core-tests.jar"
java -jar "$BUILD/core-tests.jar"
