# Engineering decisions and source references

Research checked 2026-09-17. These sources establish Android/AOSP mechanisms, not the behavior
of the user's particular Samsung firmware. The implementation is intentionally instrumented
and fails closed rather than guessing when prerequisites cannot be observed.

## 1. Non-UI service and AppOps

The service requires BIND_INCALL_SERVICE **on its service declaration**. That permission is
for the binding system process; the app does not try to acquire it. The APK declares
MANAGE_ONGOING_CALLS and the owner explicitly authorizes the UID's corresponding AppOp.
The activity calls TelecomManager.hasManageOngoingCallsPermission rather than mistaking
checkSelfPermission's signature-permission result for the AppOps-based admission result.

AOSP service admission and the dedicated compatibility test are the relevant evidence:

- https://android.googlesource.com/platform/packages/services/Telecomm/+/refs/heads/main/src/com/android/server/telecom/InCallController.java
- https://android.googlesource.com/platform/cts/+/refs/heads/main/tests/tests/telecom/src/android/telecom/cts/ThirdPartyInCallServiceAppOpsPermissionTest.java
- https://developer.android.com/reference/android/telecom/TelecomManager#hasManageOngoingCallsPermission()

## 2. Exact physical-device targeting

The actuator uses the still-public requestBluetoothAudio(BluetoothDevice), deliberately
isolated in AddressedTelecomRouter. Android documents that it requests a particular Bluetooth
device but the stack can choose a different one, so the returned void method is NOT success.
The selected device is obtained from Telecom's supported list and matched to the configured
bonded address. BluetoothHeadset observation separately checks HFP connection and SCO audio.

A modern CallEndpoint's UUID/name is not treated as a durable Bluetooth address. This v0.1
uses endpoint callbacks for observation but does not attempt a speculative mapping. Migrating
the actuator to requestCallEndpointChange needs an identity correlation proven on the phone.

- https://developer.android.com/reference/android/telecom/InCallService#requestBluetoothAudio(android.bluetooth.BluetoothDevice)
- https://developer.android.com/reference/android/telecom/CallAudioState
- https://developer.android.com/reference/android/telecom/CallEndpoint
- https://developer.android.com/reference/android/bluetooth/BluetoothHeadset

No raw SCO command or AudioManager mode change is involved. The app does not disconnect
profiles, change A2DP selection, auto-answer, originate or end calls.

## 3. Projection signal

ProjectionMonitor uses the public AndroidX CarConnection LiveData signal and removes its
observer when the screen/service closes. Its provider package visibility is declared.
No Bluetooth ACL, Wi-Fi SSID, notification, or running-process guess stands in for projection.
A false/unknown signal blocks auto mode; the explicit one-shot test can isolate the actuator.

- https://developer.android.com/reference/androidx/car/app/connection/CarConnection
- https://developer.android.com/jetpack/androidx/releases/car-app

## 4. Cellular/emergency classification

TelecomManager.getPhoneAccount requires READ_PHONE_NUMBERS for target API 31+, hence the
phone-account permission. Only CAPABILITY_SIM_SUBSCRIPTION is accepted. Numbers are used
transiently for TelephonyManager.isEmergencyNumber and are never logged/persisted. Network
emergency properties, callback mode, missing numbers, unavailable classification, conferences
and nonlocal/self-managed calls block routing. This conservatively skips anonymous calls.

- https://developer.android.com/reference/android/telecom/TelecomManager#getPhoneAccount(android.telecom.PhoneAccountHandle)
- https://developer.android.com/reference/android/telephony/TelephonyManager#isEmergencyNumber(java.lang.String)
- https://developer.android.com/reference/android/telecom/Call.Details

## 5. User intent and timing

The app cannot prove human intent from a device-change callback. It permits retries only
against an explicitly selected competing head unit, only in a fixed startup window. All
other changes suspend it. API 37's endpoint-request callback supplies additional observations,
not an infallible human/automatic discriminator; this compile-SDK-36 baseline does not use it.

The timer is a verification deadline, not an answer-detection delay. The policy is pure Kotlin
and receives monotonic timestamps. Android callbacks and one scheduled deadline drive it.
After release/suspension/failure it cannot issue more requests until a new session or explicit
manual test. An already-active call encountered after process/service recovery is passive.

- https://developer.android.com/reference/android/telecom/InCallService#onCallEndpointRequested(android.telecom.CallEndpoint)
- https://developer.android.com/reference/android/telecom/Call.Callback

## 6. Toolchain

Pinned: AGP 8.13.2, Gradle 8.13, Kotlin 2.2.21, AndroidX Car App 1.7.0, compile/target SDK 36,
minimum SDK 34. The published Gradle ZIP checksum is embedded in both bootstrap scripts and
the wrapper properties. First-party toolchain/library binaries are not bundled in this ZIP.

- https://developer.android.com/build/releases/agp-8-13-0-release-notes
- https://gradle.org/release-checksums/

Future escalation: should the service fail admission or the request fail on this firmware,
the next step is inspecting the captured state, not silently enabling a different privilege
mechanism. There is no root/Shizuku/privileged APK/LSPosed code disguised as a proven fallback.
