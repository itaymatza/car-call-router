# Direct-download debug beta

This pre-release makes the current Car Call Router beta installable as a direct APK download.

## Install

1. Download the `.apk` attached to this release.
2. Open the APK on an Android 14+ phone and allow the browser or file manager to install unknown
   apps when prompted.
3. Open Car Call Router and complete the guided permissions, target-device selection, and
   one-time ADB authorization.
4. Test the manual one-shot route while safely parked before enabling automatic routing.

## Important beta limitations

- This is a debug-signed testing build, not a production-ready release.
- Its signing certificate is recorded in **apk-verification.txt**. Each GitHub runner generates its
  own debug signing key. Installing a newer debug APK over an older one can fail with **App not installed**;
  uninstall the old app first, then reinstall, reconfigure, and repeat the one-time authorization.
  The protected release certificate will also differ.
- Real-car stability qualification is still incomplete. Never use emergency calls for testing and
  do not interact with the app while driving.

The attached `.sha256` file verifies the APK bytes. The GitHub artifact attestation links the APK
to the workflow and source commit that produced it.

## What changed in beta.12

- Replaces callback-fighting behavior with one bounded BMW request verified by exact, stable
  HFP/SCO ownership rather than the endpoint displayed by the Phone UI.
- Restores Samsung's manual BMW selector once when Telecom displays BMW but the configured Android
  Auto endpoint still owns call audio, without retrying BMW or guessing an endpoint.
- Adds complete privacy-conscious routing evidence, selector-recovery diagnostics, and capture
  analysis so the next parked run can distinguish Telecom display from actual audio ownership.
- Stops selector recovery when speaker, handset, wired, or another Bluetooth route appears and
  adds deterministic, randomized, JDK 17/21, coverage, and merge-queue regressions.
- Includes the selector-recovery hardening merged on September 26, 2026. Remains a debug beta pending parked real-car qualification.
