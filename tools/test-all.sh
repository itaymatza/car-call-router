#!/usr/bin/env bash
# Run the same deterministic JVM checks recorded in verification/current/.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
command -v java >/dev/null || { echo "A JDK is required" >&2; exit 1; }
command -v kotlinc >/dev/null || { echo "kotlinc is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
LOGS="$ROOT/verification/current"
mkdir -p "$LOGS"
{ java -version; kotlinc -version; } > "$LOGS/toolchain.log" 2>&1
bash "$ROOT/tools/test-core.sh" 2>&1 | tee "$LOGS/policy-tests.log"
bash "$ROOT/tools/test-properties.sh" 2>&1 | tee "$LOGS/property-tests.log"
bash "$ROOT/verification/service-tests/run.sh" 2>&1 | tee "$LOGS/service-tests.log"
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s "$ROOT/tools/tests" -p 'test_*.py' \
    2>&1 | tee "$LOGS/tool-tests.log"
