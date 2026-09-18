# Car Call Router

**0.2.1-device-test source candidate. No 0.2.1 APK has been built or installed.**

Android 14+ companion app intended to route a newly active cellular call to an
explicitly selected native BMW Bluetooth device while Android Auto remains
connected. Samsung Phone remains the default dialer. The recovered application
uses Kotlin with a framework/XML UI, not Jetpack Compose.

## Current status (2026-09-18)

The corrected production source was recovered from the previous project archive.
It contains the callback-latching fix documented in `docs/REVERIFICATION.md`.
The application source itself is unchanged from that corrected candidate; this
revision changes version metadata, fixes the standalone service-test launcher,
adds `tools/test-all.sh`, and includes freshly executed test evidence.

- 62/62 pure Kotlin policy cases passed.
- 37/37 production-service scenarios with Android framework doubles passed.
- 5,000 seeded policy traces / 250,000 evaluated transitions passed.
- Android build, lint, installation, actual Telecom admission and physical
  Samsung/BMW/Android Auto behavior are **not verified** for this candidate.

The old 0.2.0 APK is deliberately not included: it does not contain the later
callback-latching correction. Historical documents under `docs/` describe earlier
operations, not a build or hardware validation of 0.2.1.

## GitHub publication

Two actual upload attempts to `itaymatza/car-call-router` failed with HTTP 403,
`Resource not accessible by integration`, on 2026-09-18: the contents API and Git
Trees API. The repository was not changed and no GitHub Actions job was started.
The presence of account-level push/admin metadata did not establish working
write access through that connection. See `verification/current/upload-attempts.json`.

## Reproduce the checks

With JDK 17+ and `kotlinc` installed, from the repository root:

```sh
bash tools/test-all.sh
```

The service harness uses deterministic Android doubles. It is not an emulator,
APK installation or Bluetooth test. The script fails if any suite fails and
writes fresh raw logs to `verification/current/`.

## Android build

Open this root directory in Android Studio, or use the retained checksum-pinned
Gradle launcher with an Android SDK installed:

```sh
bash gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The included `.github/workflows/build.yml` has not been executed for this source.
A successful build is not proof of phone installation or hardware routing.

## Activation is not install-only

This architecture needs runtime permission approval, explicit selection of the
BMW Bluetooth device, and protected Telecom authorization in addition to APK
installation. It cannot grant itself the latter on an unmodified phone.

From an already authorized ADB shell, the existing provision command is:

```sh
cmd appops set --uid com.itaymatza.carcallrouter MANAGE_ONGOING_CALLS allow
```

That command is not a claim that Samsung will admit the service on every build.
Only actual device evidence can establish admission. Configure and test while
parked, not while driving. No root, accessibility service, replacement dialer,
Bluetooth disconnection, A2DP modification, call recording or internet upload is
performed by this app.

## Routing implementation and limits

The recovered implementation selects an address-identified supported device via
Telecom's deprecated `requestBluetoothAudio(BluetoothDevice)` API and observes
both audio-state and endpoint callbacks. It is **not** a completed implementation
of the previously discussed endpoint-request-only design. It makes a bounded
startup attempt, with at most three requests in a four-second window, and stops
on safety-relevant callbacks and possible manual overrides. It fails closed for
emergency, unknown, non-SIM and multi-call cases.

Preserve these distinctions when describing or extending this code. Consult
`docs/REVERIFICATION.md` and `docs/ORIGINAL-README-0.2.0.md` for prior analysis.
Never commit signing keys, credentials or personal call logs to the public repo.
