# Direct-download debug beta

This pre-release makes the current Car Call Router beta installable as a direct APK download.

## Install

1. Download **car-call-router-0.3.0-beta.4-debug.apk** below.
2. Open the APK on an Android 14+ phone and allow the browser or file manager to install unknown
   apps when prompted.
3. Open Car Call Router and complete the guided permissions, target-device selection, and
   one-time ADB authorization.
4. Test the manual one-shot route while safely parked before enabling automatic routing.

## Important beta limitations

- This is a debug-signed testing build, not a production-ready release.
- Its signing certificate is recorded in **apk-verification.txt**, but is not the future protected
  release certificate. Moving to a differently signed build may require uninstalling this build,
  reconfiguring the app, and repeating the one-time authorization.
- Real-car stability qualification is still incomplete. Never use emergency calls for testing and
  do not interact with the app while driving.

The attached `.sha256` file verifies the APK bytes. The GitHub artifact attestation links the APK
to the workflow and source commit that produced it.

## What changed in beta.4

- The installed app is now consistently named **Car Call Router**.
- The launcher supports adaptive, round, and themed icons.
- An in-app About and support section links to versions, downloads, source, and issue reporting.
- Android manifest and reusable store-listing metadata now match the public release identity.
