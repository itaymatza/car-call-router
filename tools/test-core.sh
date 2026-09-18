#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
CORE="$ROOT/app/src/main/java/org/carcallrouter/companion/core"
TEST="$ROOT/app/src/test/java/org/carcallrouter/companion/core/PolicyCases.kt"
kotlinc "$CORE"/*.kt "$TEST" "$ROOT/tools/CoreTestsMain.kt" -include-runtime -d "$BUILD/core-tests.jar"
java -jar "$BUILD/core-tests.jar"
