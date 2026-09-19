#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT="$ROOT/SOURCE-SHA256SUMS.txt"
TEMP="$(mktemp)"
trap 'rm -f "$TEMP"' EXIT

(
    cd "$ROOT"
    LC_ALL=C find . -type f \
        -not -path './.git/*' \
        -not -path './.gradle/*' \
        -not -path './.tools/*' \
        -not -path '*/build/*' \
        -not -path './verification/current/*' \
        -not -path './SOURCE-SHA256SUMS.txt' \
        -print0 | LC_ALL=C sort -z | xargs -0 sha256sum
) > "$TEMP"

if [[ "${1:-}" == "--check" ]]; then
    cmp -s "$TEMP" "$OUTPUT" || {
        echo 'SOURCE-SHA256SUMS.txt is stale. Run: bash tools/update-checksums.sh' >&2
        diff -u "$OUTPUT" "$TEMP" || true
        exit 1
    }
else
    mv "$TEMP" "$OUTPUT"
    trap - EXIT
fi
