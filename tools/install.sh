#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE=com.itaymatza.carcallrouter
if [[ -z "${ANDROID_HOME:-}" ]]; then
    for dir in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [[ -d "$dir" ]]; then export ANDROID_HOME="$dir"; break; fi
    done
fi
ADB="${ADB:-${ANDROID_HOME:+$ANDROID_HOME/platform-tools/adb}}"
ADB="${ADB:-adb}"
SERIAL="${1:-}"
[[ "${SKIP_BUILD:-0}" == 1 ]] || bash "$ROOT/gradlew" :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || { echo "Missing APK: $APK" >&2; exit 1; }
if [[ -z "$SERIAL" ]]; then
    DEVICES="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
    COUNT="$(printf '%s\n' "$DEVICES" | awk 'NF {n++} END {print n+0}')"
    [[ "$COUNT" == 1 ]] || { echo 'Connect one authorized phone or pass its serial number.' >&2; exit 1; }
    SERIAL="$DEVICES"
fi
run_adb() { "$ADB" -s "$SERIAL" "$@"; }
USER_ID="$(run_adb shell am get-current-user | tr -d '\r\n')"
[[ "$USER_ID" =~ ^[0-9]+$ ]] || { echo 'Cannot determine Android user' >&2; exit 1; }
run_adb install --user "$USER_ID" -r "$APK"
run_adb shell cmd appops set --user "$USER_ID" --uid "$PACKAGE" MANAGE_ONGOING_CALLS allow
run_adb shell cmd appops get --user "$USER_ID" "$PACKAGE" MANAGE_ONGOING_CALLS
run_adb shell am start --user "$USER_ID" -n "$PACKAGE/.ui.MainActivity"
echo 'Grant runtime permissions, select BMW, test Route now while parked, then enable automation.'
