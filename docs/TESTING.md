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

The Gradle workflow also runs `:verification:service-tests:run`, so callback-order and lifecycle regressions cannot be skipped merely because a machine lacks a standalone `kotlinc` command.

## Current device evidence

On 2026-09-19, the project owner confirmed the proof of concept on the intended Samsung phone, aftermarket Android Auto unit, and native 2018 BMW X1 Bluetooth system. The app successfully moved active call speaker and microphone routing to the BMW endpoint while Android Auto remained active. Intermittent behavior was also reported, so this is evidence for technical feasibility—not yet a production stability claim.

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

## Production stability matrix

Do not promote a beta to a production release until one unchanged APK passes all of the following on the intended phone/car setup:

| Scenario | Minimum evidence | Expected result |
|---|---:|---|
| Incoming and outgoing calls | 20 of each | BMW speaker and microphone selected; Android Auto navigation/media remains available. |
| Cold start, warm start, screen off, and post-reboot | 5 calls per state | Same routing result without opening the app. |
| Android Auto first vs BMW Bluetooth first | 10 calls per connection order | Callback ordering does not change the result. |
| Consecutive calls and calls after idle | 10 calls | No stale session, request budget, or endpoint identity leaks into the next call. |
| Temporary projection/HFP unknown callbacks | Instrumented logs for each occurrence | Requests freeze and recover inside the bounded window; no false user-override classification. |
| Confirmed BMW or projection disconnect | 5 controlled trials each | Guard stops and does not resume automatically in that call. |
| Speaker, handset, wired, or other Bluetooth override | 3 trials per route | User choice is respected immediately and is not fought. |
| Hold/resume, second call, and conference | 3 trials each | Automatic reassertion stops for the session. |

Acceptance requires no unexplained routing failure, no route fight after a user override, no request outside the eligibility policy, and no loss of Android Auto media/navigation. Export the redacted app log after every failure and record the exact scenario; never publish raw `dumpsys` or Bluetooth data without reviewing it for personal information.
