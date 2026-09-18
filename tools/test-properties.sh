#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
kotlinc "$ROOT"/app/src/main/java/com/itaymatza/carcallrouter/core/*.kt \
  "$ROOT/tools/PropertyChecks.kt" -include-runtime -d "$BUILD/properties.jar"
java -jar "$BUILD/properties.jar"
