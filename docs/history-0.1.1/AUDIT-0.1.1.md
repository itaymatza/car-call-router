# Car Call Router 0.1.1 — source audit, not an APK release

## Result

No APK was produced. Android application compilation, Android lint, APK signing,
installation, on-device Telecom binding and physical BMW/Android Auto testing have
NOT completed. This must not be described as production-ready or fully verified.

The local APK build attempt failed in Gradle bootstrap with curl exit code 6:
`Could not resolve host: services.gradle.org`. The Android SDK and build tools are
also absent; Google SDK downloads failed at name resolution. The compiler never
processed the Android application. An SDK-based compiler error has therefore not
been ruled out. The GitHub connection was inspected; no matching `car-call-router`
repository was returned by the search. No repository was modified and no remote
workflow was started.

## Executed checks

- Original v0.1 pure-Kotlin suite: 47/47 passed when rerun.
- Four added before-patch regression cases: 4/4 FAILED as expected. A switch to
  speaker, handset, another Bluetooth headset, or wired audio while awaiting
  projection could still be overridden when projection became ready.
- Patched pure-Kotlin suite: 62/62 passed, including the above cases and additional
  pending-device, authorization, callback-coalescing and streaming-route checks.
- Seeded policy exercise: 5,000 traces, 250,000 evaluated state transitions,
  3,786 permitted requests; all asserted invariants passed (seed 20260917).
- XML: all 6 manifest/resource XML files are well-formed. This is XML parsing,
  NOT Android resource compilation or Android lint.

The policy checks do not mock or exercise Samsung Telecom, Android Auto, HFP,
Bluetooth microphones, Android service lifecycles or permission enforcement.
The seeded exercise is not 250,000 independent end-to-end tests.

## Source corrections

1. A changed, known alternative route is respected while projection/BMW availability
   is pending, not only after the first request.
2. Telecom authorization is now a separate mandatory gate. Manual one-shot mode
   bypasses only the master toggle and projection requirement, not authorization.
   This fixes a missing local guard; it does not imply Android itself allowed an
   unauthorized application to route a call.
3. Alternative route events are recorded synchronously before queued evaluations
   can coalesce them away.
4. STREAMING/UNKNOWN endpoint types are no longer allowed to fall through to stale
   Bluetooth route classification in the endpoint switch.
5. The optional battery-settings button changes nothing automatically, requests no
   direct exemption permission, and makes no Samsung deep-sleep guarantee.
6. The prepared CI workflow uploads an APK only after successful build, unit tests,
   lint, signature verification and zip-alignment verification. This workflow has
   NOT been executed. Its debug signing key is runner-local; future CI runs may
   require uninstall/reinstall unless a stable private signing key is configured.

## Gemini proposals that are not accepted as facts

- `CallEndpoint.identifier` is a ParcelUuid, not a Bluetooth MAC. The public type
  has no MAC-address getter. Comparing it to a stored MAC is not a working mapping.
- Available endpoints arrive through `onAvailableCallEndpointsChanged`; code must
  retain that list rather than assume an undocumented getter.
- A 100–300 ms Android Auto takeover is not established by the user's manual switch.
  Timing and which component initiates it require actual device logs.
- A non-target Bluetooth device is not necessarily the head unit. Startup retries
  remain limited to a separately user-selected competing car device.
- Battery-optimization exemption is partial; it is not a guarantee of Telecom
  service binding or protection from all Samsung background policies.
- A Compose rewrite changes the UI framework, not routing feasibility. This audited
  source retains its smaller native Android UI rather than introducing an untested
  UI rewrite while the routing actuator remains unproved.

## Remaining technical risks

The source deliberately retains public-but-deprecated
`InCallService.requestBluetoothAudio(BluetoothDevice)` for exact address targeting.
There is no invented UUID-to-MAC mapping and no promise that the deprecated adapter
will keep working indefinitely. Newer endpoint callbacks and legacy addressed audio
callbacks can arrive at different times; their ordering and the current adapter's
verification behavior still require live device tests. No API success or matching
software state can alone prove that the remote caller hears the BMW microphone.

Unknown/private caller handles, uncertain emergency classification, conferences,
multiple calls, VoIP and already-active calls first seen on a recovered service are
intentionally skipped by automatic routing. Permission grants and AppOps admission
must be tested on the actual Samsung build. Reboot, overnight idle, user overrides,
Bluetooth toggling and projection loss have not been tested on hardware.

## Current primary sources used in this audit

- https://developer.android.com/reference/android/telecom/CallEndpoint
- https://developer.android.com/reference/android/telecom/InCallService
- https://android.googlesource.com/platform/packages/services/Telecomm/+/master/src/com/android/server/telecom/InCallController.java
- https://developer.android.com/reference/android/bluetooth/BluetoothHeadset
- https://developer.android.com/training/monitoring-device-state/doze-standby
- https://developer.android.com/build/releases/agp-8-13-0-release-notes

Reviewed September 17, 2026. AOSP source is not evidence that the same path has been
exercised on this user's Samsung firmware.
