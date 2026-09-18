# Testing guidance

## Deterministic host-JVM suites

Install a JDK 17 or newer and a Kotlin compiler, then run:

```sh
bash tools/test-all.sh
```

The script compiles and runs three deterministic checks: explicit routing-policy cases, seeded policy-state exploration, and production-service scenarios against local Android-framework doubles. It writes fresh local logs under `verification/current/`, which is intentionally ignored by Git.

These checks exercise decision rules and lifecycle behavior. They do not install an APK, emulate Android Telecom, validate protected-permission admission, or test a real Bluetooth stack, microphone, projection host, headset, or vehicle.

## Android build and static checks

With a suitable Android SDK:

```sh
bash gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --stacktrace --console=plain
```

The repository workflow runs that command on pushes and pull requests with read-only workflow permissions. It intentionally retains no APK or build-report uploads.

## Device testing

Any real-device work must be planned independently, carried out only while parked, and documented without personal or hardware-identifying data. Confirm that the target device, user intent, system routing behavior, and call safety conditions are appropriate before initiating an ordinary non-emergency test call. Never use a real emergency call for testing.
