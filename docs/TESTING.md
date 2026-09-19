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

The repository workflow runs that command on pushes and pull requests with read-only workflow permissions and uploads only the generated debug APK as an expiring artifact.

## Verify device authorization

When more than one ADB target is listed, address the physical phone explicitly:

```sh
adb devices
adb -s PHONE_SERIAL shell cmd appops get --uid org.carcallrouter.companion MANAGE_ONGOING_CALLS
```

The required result includes `MANAGE_ONGOING_CALLS: allow`. Refresh the app's Diagnostics and confirm the current status reports runtime permissions and Telecom authorization as true. Historical `AUTH_MISSING` events may remain after a successful grant and do not override the current status.

`Last Telecom binding: Never observed` is normal before the first eligible call. It becomes a useful failure signal only if it remains unchanged during an authorized normal test call.

## Exact parked-car test

1. Park safely, switch off the engine if local conditions require it, and do not begin driving during the test.
2. Confirm the phone is connected simultaneously to the projection unit and the intended native Bluetooth hands-free device.
3. Confirm Android Auto navigation/media still works. Select the native hands-free device in the app and leave automatic mode off.
4. Confirm the app reports runtime permissions granted and protected Telecom authorization detected.
5. Place an ordinary non-emergency call to a consenting helper or voicemail. Never test with an emergency number.
6. After the call becomes active, tap **Route this call now** once.
7. Confirm the app reports the target endpoint as verified. Speak and listen through the intended device; ask the helper which microphone is heard.
8. Confirm navigation/media remains active on Android Auto.
9. Manually select speaker in the Phone UI. Confirm the app respects that override and does not switch back.
10. End the call, export the redacted diagnostic log if the route failed, and inspect it before sharing. Only after the one-shot test succeeds should automatic mode be enabled.

Repeat separately for outgoing and incoming calls. Then test call hold/resume and a second incoming call; the expected safe behavior is to stop automatic reassertion. Conferences and emergency calls must never be used as positive routing tests.
