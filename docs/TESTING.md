# Testing guidance

## Deterministic host-JVM suites

Install a JDK 17 or newer and a Kotlin compiler, then run:

```sh
bash tools/test-all.sh
```

The script runs ktlint for every Kotlin module, the pure-JVM `:core` JUnit suite and its enforced
90% branch-coverage gate,
production-service scenarios against local Android-framework doubles, and the Python
trace/APK-tooling tests. The core suite includes named policy rules, 5,000 seeded traces (250,000
transitions), connection-order and timing scenarios, and JSONL regression-trace replay. It writes
fresh local logs under `verification/current/`, which is intentionally ignored by Git. The
standalone Kotlin compiler is needed only by the lightweight framework-double service runner.

These checks exercise decision rules and lifecycle behavior. They do not install an APK, emulate Android Telecom, validate protected-permission admission, or test a real Bluetooth stack, microphone, projection host, headset, or vehicle.

## Android build and static checks

With a suitable Android SDK:

```sh
bash gradlew :core:check :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --stacktrace --console=plain
```

The repository workflow runs that command on pushes and pull requests with read-only workflow permissions and uploads only the generated debug APK as an expiring artifact.

Kotlin formatting is enforced with ktlint's official style and a 140-character maximum. Run
`bash gradlew :app:ktlintFormat :core:ktlintFormat :verification:service-tests:ktlintFormat` before
committing broad mechanical changes. Kotlin compiler warnings and Android lint warnings are treated
as errors. Plugin and library versions are centralized in `gradle/libs.versions.toml` and updated
through Dependabot.

The Gradle workflow and both installation scripts also run `:verification:service-tests:run`, so
callback-order and lifecycle regressions are exercised against the same compiled `:core` module.
CI also runs the standard-library-only device trace analyzer and
APK-verifier tests. After building, CI verifies the generated APK's signature,
package/version identity, SDK bounds, required permissions, and debuggable state, then publishes
the verification record beside the APK.

## Verify device authorization

When more than one ADB target is listed, address the physical phone explicitly:

```sh
adb devices
adb -s PHONE_SERIAL shell cmd appops get --uid org.carcallrouter.companion MANAGE_ONGOING_CALLS
```

The required result includes `MANAGE_ONGOING_CALLS: allow`. Refresh the app's Diagnostics and confirm the current status reports runtime permissions and Telecom authorization as true. Historical `AUTH_MISSING` events may remain after a successful grant and do not override the current status.

`Last Telecom binding: Never observed` is normal before the first eligible call. It becomes a useful failure signal only if it remains unchanged during an authorized normal test call.

## Capture one parked device run

From macOS or Linux with `adb` and Python 3 available, connect the authorized phone and run:

```sh
bash tools/capture_device_run.sh --serial PHONE_SERIAL --scenario outgoing
```

Supported scenarios are `incoming`, `outgoing`, `override`, `hold-resume`, `reconnect`, and
`other`. Omit `--serial` only when exactly one authorized ADB device is connected. If the build
uses a custom application ID, also pass `--package YOUR_APPLICATION_ID`.

The script verifies that the package is installed and `MANAGE_ONGOING_CALLS` is allowed. It takes
a baseline, prompts for one parked non-emergency call, then captures only new, structured
`ROUTING_TRACE` records. It never starts a call, clears logcat, runs broad `dumpsys` collection, or
stores the ADB serial, phone number, Bluetooth name, or raw Bluetooth address.

Each local, Git-ignored directory under `verification/device-runs/` contains:

| File | Contents |
|---|---|
| `device.txt` | Scenario, UTC capture time, phone model, Android API/build fingerprint, app version, and authorization state. |
| `trace.log` | Only new schema-1 structured routing records; prior sessions are removed. |
| `report.txt` / `report.json` | Per-session status, confirmations, latencies, finish reason, and anomalies. |
| `observations.txt` | Parked human checks for native HFP speaker/microphone and preserved Android Auto behavior. |
| `verdict.txt` | `PASS` only when every new session has complete trace evidence and every required observation is `yes`. |

Review all artifacts before sharing them. They are designed to be privacy-safe, but device model
and timing can still be identifying context. To analyze a previously exported app log without ADB:

```sh
python3 tools/analyze_device_trace.py exported-log.txt
python3 tools/analyze_device_trace.py exported-log.txt --format json
```

The analyzer reports malformed records, unsupported schemas, sequence gaps, time regressions,
missing starts, and duplicate finishes as `INVALID`. A session that confirms only the Telecom
endpoint is `INCOMPLETE`; a session cannot be `PASS` without a finish and exact target HFP audio
confirmation.

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
10. End the call and complete the capture prompts. If working manually, export the redacted diagnostic log after a failure and inspect it before sharing. Only after the one-shot test succeeds should automatic mode be enabled.

Repeat separately for outgoing and incoming calls. Then test call hold/resume and a second incoming call; the expected safe behavior is to stop automatic reassertion. Conferences and emergency calls must never be used as positive routing tests.

For a successful run, the exported trace should contain both
`TELECOM_ENDPOINT_CONFIRMED` and `TARGET_HFP_AUDIO_CONFIRMED` for the same `session`. The events
include a schema version, sequence, and elapsed milliseconds. Endpoint confirmation without HFP
audio confirmation is incomplete evidence and must not be counted as a successful microphone and
speaker result.

## Production stability matrix

Do not promote a beta to a production release until one unchanged APK passes all of the following on the intended phone/car setup:

| Scenario | Minimum evidence | Expected result |
|---|---:|---|
| Incoming and outgoing calls | 20 of each | BMW speaker and microphone selected; Android Auto navigation/media remains available. |
| Cold start, warm start, screen off, and post-reboot | 5 calls per state | Same routing result without opening the app. |
| Samsung unrestricted, optimized, and restricted battery states | 5 calls per state | Binding behavior is measured explicitly; unsupported restricted behavior is documented rather than guessed. |
| Android Auto first vs BMW Bluetooth first | 10 calls per connection order | Callback ordering does not change the result. |
| Consecutive calls and calls after idle | 10 calls | No stale session, request budget, or endpoint identity leaks into the next call. |
| Temporary projection/HFP unknown callbacks | Instrumented logs for each occurrence | Requests freeze and recover inside the bounded window; no false user-override classification. |
| Confirmed BMW or projection disconnect | 5 controlled trials each | Guard stops and does not resume automatically in that call. |
| Speaker, handset, wired, or other Bluetooth override | 3 trials per route | User choice is respected immediately and is not fought. |
| Hold/resume, second call, and conference | 3 trials each | Automatic reassertion stops for the session. |

Acceptance requires no unexplained routing failure, no route fight after a user override, no request outside the eligibility policy, and no loss of Android Auto media/navigation. Use a fresh capture directory for every row, retain failed runs, and keep the APK version unchanged throughout a qualification batch. Never publish raw `dumpsys` or Bluetooth data without reviewing it for personal information.

Record the full Android build fingerprint for every qualification batch and repeat the baseline
after an OS or major One UI update. Freeze features during a batch; every failure must first become
a deterministic regression fixture. With zero failures in `n` independent trials, the approximate
one-sided 95% upper bound on the failure rate is `3/n`: 60 clean trials support a bound below 5%,
and 100 clean trials support a bound below 3%.
