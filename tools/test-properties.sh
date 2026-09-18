#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
CORE="$ROOT/app/src/main/java/org/carcallrouter/companion/core"
kotlinc "$CORE"/*.kt "$ROOT/tools/PropertyChecks.kt" -include-runtime -d "$BUILD/properties.jar"
java -jar "$BUILD/properties.jar"
