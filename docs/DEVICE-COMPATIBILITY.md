# Device compatibility

Compatibility depends on Android Telecom, the phone vendor's Bluetooth behavior, the projection
unit, and the target hands-free profile. A brand or model name alone is not enough to claim support.

## Required conditions

- Android 14 or newer (API 34+).
- The preferred device appears in Android's active-call audio selector as a Bluetooth endpoint.
- Manually selecting it routes both call speaker and microphone correctly.
- Android Auto remains connected for navigation/media after that manual selection.
- The target can be selected unambiguously in the app.
- Runtime permissions and the protected Telecom authorization are detected.

## Evidence levels

| Level | Meaning |
|---|---|
| Not evaluated | No parked physical test has been recorded. |
| Proof of concept | At least one call routed correctly, but repeatability is unproven. |
| Qualified beta | One unchanged APK passed the full matrix in `TESTING.md` on that setup. |
| Unsupported | A required condition is absent, or a classified platform limitation prevents safe routing. |

## Current evidence

| Phone / projection / target | Status | Evidence |
|---|---|---|
| Project owner's Samsung phone / aftermarket Android Auto / native 2018 BMW X1 Bluetooth | Proof of concept | Successful physical speaker and microphone routing was observed, but intermittent behavior was also reported. |

This table is deliberately conservative. A successful result on one phone build or head unit does
not imply compatibility with another. Re-run the baseline after an Android or major One UI update.

## Reporting a result

Use the repository's **Device compatibility report** issue form. Never include phone numbers, raw
Bluetooth addresses, ADB serials, unreviewed logs, or screenshots containing account details. A
useful report includes the Android version, phone model family, projection type, target type, app
version, scenario, result, and the app's redacted reason code.
