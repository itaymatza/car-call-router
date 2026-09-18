# Car Call Router 0.2.0 — independent re-verification and release decision

**Decision: NOT READY FOR VERIFIED DEPLOYMENT.**

The delivered APK was not installed or launched on an Android runtime in this session or in the previous build session. No physical Samsung/BMW test has occurred. The newly corrected service source is NOT present in the previously delivered APK. No replacement APK was built in this audit.

## Artifact identification

- Package: `com.itaymatza.carcallrouter`
- APK: `CarCallRouter-0.2.0.apk`
- Bytes: 2,110,064
- SHA-256: `55994f6616f1a16d21638a88d84b1d2c119fb5598162a6b286b4c6c4c8258edb`
- The byte-identical APK was audited, not modified.

## Executed checks

| Check | Delivered source/binary | Corrected source candidate | Actual scope |
|---|---|---|---|
| Source hash inventory | Passed | One production source file changed; patch supplied | Source identity only |
| ZIP CRC, DEX checksums, packaged entry alignment | Passed | Not rebuilt | File structure, not Android installation |
| Existing policy/safety cases | 62/62 passed | Policy source is unchanged | Pure Kotlin, no Android |
| Seeded policy traces | 5,000 traces / 250,000 evaluated steps passed | Policy source is unchanged | Pure Kotlin; many steps exercise terminal states |
| New service callback scenarios | **30/37 passed; seven failed** | **37/37 passed** | Production service/adapter/settings/policy code on JVM with test doubles |
| Android SDK/Gradle build of the source fix | Not applicable | Not executed | No patched Android binary exists |
| APK install, launch, UI, permission dialogs | Not executed | Not executed | No Android runtime available |
| Real Telecom service admission | Not executed | Not executed | Mock authorization is not OS permission testing |
| Real or virtual Bluetooth HFP/SCO integration | Not executed | Not executed | Endpoint/address inputs are fixtures |
| Samsung Phone + Android Auto + BMW audio/microphone | Not executed | Not executed | Requires the relevant actual system |
| Device reboot / Samsung long-idle behavior | Not executed | Not executed | Not inferred from service design |

The earlier report described signature verification performed during the original build. Signature verification was not newly repeated by this audit's Python file-structure check. DEX hash integrity is not Android bytecode execution or ART verification.

## Why the original 62 checks were insufficient

They tested the isolated policy's response to individual snapshots. The service debounces pending evaluations. When a negative event and its recovery occur before that evaluation, the negative state can disappear from the snapshot even though the service received it.

The new tests compile and execute these original production classes together:

- `RouterInCallService`
- `AddressedTelecomRouter`
- `RoutingPolicy`
- `RouterSettings`
- `SessionBridge`

Android, Bluetooth, authorization, projection queries, emergency/SIM classification, and persistence infrastructure are supplied as explicit test doubles. These are integration tests of application components, **not Android emulator tests**. A status string saying that SIM checks passed in a test is generated from mocked classification; it is not evidence of a real SIM, phone call, permission grant, or microphone path.

## Reproduced counterexamples

The final expanded suite produced seven failing scenarios against the original service:

1. HOLDING and then ACTIVE before queued evaluation.
2. Another call added and then removed before queued evaluation.
3. Projection disconnect and reconnect before queued evaluation.
4. HFP disconnect and reconnect before queued evaluation.
5. BMW disappears from Telecom's supported devices and returns before queued evaluation.
6. Conference children appear and disappear before queued evaluation.
7. A HOLDING callback is delivered while the Call object already exposes newer ACTIVE details.

Each counterexample starts from an established target route and then supplies a competitor event at 350 ms. The expected count is one initial routing request, because the intervening cancellation condition should end the startup guard. The original service instead issues a second request. These are controlled software counterexamples, not an assertion that all seven orderings have been observed on Samsung.

Example original result:

```
Expected 1 requests, observed [(0, 00:11:22:33:44:55), (350, 00:11:22:33:44:55)]
Controller: VERIFYING; attempts=2
```

All Bluetooth addresses are synthetic test fixtures.

## Source correction and validation

The candidate retains cancellation conditions immediately in the callback instead of relying only on a later coalesced snapshot. It uses the call-state value supplied by the callback, cancels the pending timer, and prevents a subsequent recovery event from resuming that call session's guard. Fresh independent call sessions remain eligible. Manual one-shot projection bypass remains intentional and is tested.

The candidate passed all 37 service scenarios. Positive controls include incoming/outgoing lifecycle sequences, late binding, no permission, no BMW, no projection, duplicate device addresses, manual pause, quick speaker/Buds overrides, bounded competitor retries, exception handling, fresh sessions, rebind, callback cleanup, and startup prerequisites becoming available. This result does not replace Android compilation or runtime testing of the patch.

The candidate is provided as source and a unified diff only. The original APK has not been silently replaced or relabeled.

## Install-only authorization finding

The selected architecture requires `MANAGE_ONGOING_CALLS`. In current AOSP this permission is `signature|appop`, not a dangerous runtime permission obtainable through an ordinary permission dialog. An ordinary newly installed application cannot grant its own AppOp through supported APIs. AppOps mode setters require separately protected authority (`MANAGE_APP_OPS_MODES`). Placing an ADB command inside the application does not give that application shell identity.

The documented companion route is for wearable companion access. Current AOSP role definitions grant ongoing-call AppOp access to watch and glasses companion roles; a generic association with the BMW is not established as an equivalent authorization route. The pairing flow also includes system-owned user consent.

Therefore the current application does not satisfy install-only setup on an otherwise unmodified, unprovisioned phone while retaining Samsung Phone. A pre-authorized privileged helper/root setup would change the starting conditions; it has not been established on this phone. An embedded local ADB implementation could eliminate a separate terminal application and copied commands, but Android wireless-debugging enablement and pairing consent would still be necessary. That alternative has not been implemented or verified here.

## Android runtime testing attempts and limits

A local inventory found no Android SDK, emulator, ADB, system image or existing attached Android test target. The official emulator download attempt failed because the build container could not resolve `dl.google.com`. The available file downloader did not supply the emulator either. Connected cloud-device testing integrations and usable public artifacts were investigated, but no executable Android test environment was obtained. No emulator boot, package install or GSM simulation succeeded or is represented as executed.

Lack of `/dev/kvm` was not treated as conclusive: the Android emulator documents software emulation. The unresolved issue is obtaining a runnable emulator plus an appropriate image and then successfully executing the tests.

A real Android emulator can cover package installation, app launch, permission handling and simulated cellular call lifecycle. Virtual Bluetooth peers can extend software integration coverage. Neither an idealized AOSP image nor a mocked routing success establishes the physical Samsung/BMW microphone path. An end-to-end release claim must include observed call output, microphone input, Android Auto coexistence, and post-call media behavior on the actual relevant setup.

## Unpassed release gates

No release-ready claim is made until these are executed and pass:

- Official Android build/lint of the corrected source, then signature and package verification.
- Fresh installation of the exact signed artifact, startup, permission denial/grant flows, settings persistence and user controls on representative current Android runtimes.
- Actual Telecom admission before/after supported authorization, simulated incoming/outgoing calls, precise ACTIVE triggering, late binding, disconnect, hold, multi-call and process restart behavior.
- Bluetooth integration with two independently identified HFP devices, route confirmation, forced competitor changes, manual override, missing device and loss/reconnection handling.
- Actual Samsung + native BMW + aftermarket Android Auto call output AND microphone confirmation, plus idle/reboot and end-of-call coexistence.

## Evidence and rerunning the component tests

`logs/` contains raw outputs. `service-tests/` includes the deterministic harness and explicitly named framework doubles. `baseline/CarCallRouter/` is the original source archive; `candidate/CarCallRouter/` contains the source patch.

With JDK 17+ and Kotlin available, component tests can be independently rerun from the evidence folder:

```bash
bash service-tests/run.sh ../baseline/CarCallRouter baseline
# Expected: exit 1, seven reproduced counterexamples.
bash service-tests/run.sh ../candidate/CarCallRouter candidate
# Expected: exit 0, 37/37 scenarios pass.
```

These commands are for reproducibility, not a request that the phone user perform build or setup work. No personal phone, account, car or head-unit state was changed in this audit.

## Primary research references

- AOSP current permission definitions: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml
- AOSP Telecom admission and authorization: https://android.googlesource.com/platform/packages/services/Telecomm/+/master/src/com/android/server/telecom/InCallController.java
- AOSP role grants: https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml
- Android Telecom authorization API: https://developer.android.com/reference/android/telecom/TelecomManager#hasManageOngoingCallsPermission()
- Companion-device consent: https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing
- Emulator operation: https://developer.android.com/studio/run/emulator-commandline
- Simulated cellular events: https://developer.android.com/studio/run/emulator-console

Source inspection supports architecture and permission conclusions; it does not constitute device validation.
