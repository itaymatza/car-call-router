#!/usr/bin/env bash
# Transparent source-only launcher: official Gradle binary ZIP, pinned SHA-256.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/.tools"
VERSION=8.13
EXPECTED=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78
if [[ -f "$CACHE/gradle-$VERSION/bin/gradle" ]]; then exit 0; fi
mkdir -p "$CACHE"
ZIP="$CACHE/gradle-$VERSION-bin.zip"
STAGING="$(mktemp -d "$CACHE/unpack.XXXXXX")"
trap 'rm -rf "$STAGING"' EXIT
curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
    "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ZIP"
if command -v sha256sum >/dev/null; then ACTUAL="$(sha256sum "$ZIP" | cut -d' ' -f1)"
else ACTUAL="$(shasum -a 256 "$ZIP" | cut -d' ' -f1)"; fi
[[ "$ACTUAL" == "$EXPECTED" ]] || { rm -f "$ZIP"; echo 'Gradle SHA-256 mismatch. Refusing to execute.' >&2; exit 1; }
unzip -q "$ZIP" -d "$STAGING"
mv "$STAGING/gradle-$VERSION" "$CACHE/gradle-$VERSION"
rm -f "$ZIP"
echo "Installed checksum-verified Gradle $VERSION under .tools/" >&2
