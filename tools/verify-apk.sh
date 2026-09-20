#!/usr/bin/env bash
# Verify the built APK's signature, identity, SDK bounds, permissions and debuggable state.
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: tools/verify-apk.sh APK PACKAGE VERSION_CODE VERSION_NAME MIN_SDK TARGET_SDK DEBUGGABLE

DEBUGGABLE must be true or false. Set ANDROID_BUILD_TOOLS to an exact build-tools directory when
ANDROID_HOME does not contain build-tools/36.0.0. Set REPORT_FILE to save the verification record.
EOF
}

[[ $# == 7 ]] || { usage >&2; exit 2; }
APK="$1"
EXPECTED_PACKAGE="$2"
EXPECTED_VERSION_CODE="$3"
EXPECTED_VERSION_NAME="$4"
EXPECTED_MIN_SDK="$5"
EXPECTED_TARGET_SDK="$6"
EXPECTED_DEBUGGABLE="$7"
[[ "$EXPECTED_DEBUGGABLE" == "true" || "$EXPECTED_DEBUGGABLE" == "false" ]] || {
    echo "DEBUGGABLE must be true or false." >&2; exit 2;
}
[[ -s "$APK" ]] || { echo "APK is missing or empty: $APK" >&2; exit 1; }

BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-${ANDROID_HOME:-}/build-tools/36.0.0}"
AAPT="$BUILD_TOOLS/aapt"
APKSIGNER="$BUILD_TOOLS/apksigner"
[[ -x "$AAPT" ]] || { echo "aapt not found: $AAPT" >&2; exit 1; }
[[ -x "$APKSIGNER" ]] || { echo "apksigner not found: $APKSIGNER" >&2; exit 1; }

REPORT_FILE="${REPORT_FILE:-}"
if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    : >"$REPORT_FILE"
fi
report() {
    printf '%s\n' "$*"
    [[ -z "$REPORT_FILE" ]] || printf '%s\n' "$*" >>"$REPORT_FILE"
}
fail() { echo "APK verification failed: $*" >&2; exit 1; }

BADGING="$("$AAPT" dump badging "$APK")"
PERMISSIONS="$("$AAPT" dump permissions "$APK")"
MANIFEST_TREE="$("$AAPT" dump xmltree "$APK" AndroidManifest.xml)"
PACKAGE="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$BADGING")"
VERSION_CODE="$(sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" <<<"$BADGING")"
VERSION_NAME="$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING")"
MIN_SDK="$(sed -n "s/^sdkVersion:'\([^']*\)'.*/\1/p" <<<"$BADGING")"
TARGET_SDK="$(sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" <<<"$BADGING")"
if grep -q '^application-debuggable' <<<"$BADGING"; then DEBUGGABLE=true; else DEBUGGABLE=false; fi

[[ "$PACKAGE" == "$EXPECTED_PACKAGE" ]] || fail "package=$PACKAGE, expected $EXPECTED_PACKAGE"
[[ "$VERSION_CODE" == "$EXPECTED_VERSION_CODE" ]] || fail "versionCode=$VERSION_CODE, expected $EXPECTED_VERSION_CODE"
[[ "$VERSION_NAME" == "$EXPECTED_VERSION_NAME" ]] || fail "versionName=$VERSION_NAME, expected $EXPECTED_VERSION_NAME"
[[ "$MIN_SDK" == "$EXPECTED_MIN_SDK" ]] || fail "minSdk=$MIN_SDK, expected $EXPECTED_MIN_SDK"
[[ "$TARGET_SDK" == "$EXPECTED_TARGET_SDK" ]] || fail "targetSdk=$TARGET_SDK, expected $EXPECTED_TARGET_SDK"
[[ "$DEBUGGABLE" == "$EXPECTED_DEBUGGABLE" ]] || fail "debuggable=$DEBUGGABLE, expected $EXPECTED_DEBUGGABLE"

for permission in android.permission.BLUETOOTH_CONNECT android.permission.READ_PHONE_NUMBERS android.permission.MANAGE_ONGOING_CALLS; do
    grep -Fq "uses-permission: name='$permission'" <<<"$PERMISSIONS" || fail "missing $permission"
done
for manifest_value in org.carcallrouter.companion.telecom.RouterInCallService android.permission.BIND_INCALL_SERVICE android.telecom.InCallService; do
    grep -Fq "$manifest_value" <<<"$MANIFEST_TREE" || fail "manifest is missing $manifest_value"
done

SIGNATURE="$("$APKSIGNER" verify --verbose --print-certs "$APK")" || fail "signature rejected by apksigner"
CERT_SHA256="$(awk '/^Signer #1 certificate SHA-256 digest: / {sub(/^Signer #1 certificate SHA-256 digest: /, ""); print; exit}' <<<"$SIGNATURE")"
[[ -n "$CERT_SHA256" ]] || fail "certificate SHA-256 digest was not reported"
EXPECTED_CERT_SHA256="${EXPECTED_CERT_SHA256:-}"
if [[ -n "$EXPECTED_CERT_SHA256" ]]; then
    NORMALIZED_EXPECTED_CERT="$(tr -d '[:space:]:' <<<"$EXPECTED_CERT_SHA256" | tr '[:upper:]' '[:lower:]')"
    NORMALIZED_ACTUAL_CERT="$(tr -d '[:space:]:' <<<"$CERT_SHA256" | tr '[:upper:]' '[:lower:]')"
    [[ "$NORMALIZED_EXPECTED_CERT" =~ ^[0-9a-f]{64}$ ]] \
        || fail "EXPECTED_CERT_SHA256 must contain exactly 64 hexadecimal characters"
    [[ "$NORMALIZED_ACTUAL_CERT" == "$NORMALIZED_EXPECTED_CERT" ]] \
        || fail "signing certificate does not match the pinned SHA-256 digest"
fi
if command -v sha256sum >/dev/null; then
    APK_SHA256="$(sha256sum "$APK" | awk '{print $1}')"
else
    APK_SHA256="$(shasum -a 256 "$APK" | awk '{print $1}')"
fi

report "verified=true"
report "package=$PACKAGE"
report "version_code=$VERSION_CODE"
report "version_name=$VERSION_NAME"
report "min_sdk=$MIN_SDK"
report "target_sdk=$TARGET_SDK"
report "debuggable=$DEBUGGABLE"
report "certificate_sha256=$CERT_SHA256"
report "apk_sha256=$APK_SHA256"
