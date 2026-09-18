# Architecture

## Design objective

**Call Route Companion** is an Android 14+ reference application that can make a deliberately bounded request to route an eligible call to a user-selected paired Bluetooth device. It is not a dialer, does not own the call lifecycle, and does not promise that a routing request will be honored. Android documents that `InCallService.requestBluetoothAudio(BluetoothDevice)` can route to a device other than the requested device when the Bluetooth stack cannot satisfy the request. The app therefore waits for callback evidence instead of treating a successful method return as proof of a route change. [1]

The design treats autonomous call-audio routing as a safety-sensitive feature. Its default posture is **fail closed**: when it cannot establish that a condition is safe, it makes no request or stops the current session.

## Components

| Component | Responsibility | Safety boundary |
|---|---|---|
| `MainActivity` | Collects runtime permission, device selection, master-toggle, manual-test, pause, and diagnostic-export choices. | User actions are explicit. It cannot enable automation without a configured target and granted prerequisites. |
| `RouterSettings` | Persists the enabled state plus the locally chosen target and optional competing Bluetooth devices in app-private preferences. | No device identifiers are compiled into the source tree or sent over the network. |
| `Access` | Checks runtime permissions and the protected ongoing-call authorization. | Missing or revoked access stops routing eligibility. The displayed AppOps command derives from the installed application ID. |
| `ProjectionMonitor` | Reads the AndroidX host-provider state for optional projection gating. | Missing, unknown, or lost state blocks new automatic requests. It does not infer state from names, Wi-Fi, or process activity. |
| `HfpMonitor` | Tracks the target device’s HFP connection and SCO state through the Bluetooth profile service. | Broadcast extras are not trusted; state is re-queried from the profile service. |
| `CellularClassifier` and `CallSafety` | Determine whether the call is a single, verifiable, non-emergency SIM-backed call. | Emergency, hidden/unclassifiable, external, self-managed, conference, and multi-call cases are rejected. |
| `RoutingPolicy` | Pure Kotlin state machine that decides whether a routing request is permitted, delayed, released, or stopped. | Enforces a four-second window, a 300 ms request gap, and a three-request maximum. |
| `AddressedTelecomRouter` | Matches the selected Bluetooth address against Telecom-supported devices and issues the request. | A target must be a unique supported device; the app does not guess from a name or UUID. |
| `RouterInCallService` | Coordinates Telecom, route, endpoint, projection, HFP, and call callbacks on the main thread. | Safety-relevant events are latched before deferred evaluation so that later callbacks cannot erase them. |

## Decision flow

1. Telecom binds `RouterInCallService` and the service starts its projection and HFP observers. The service remains passive until it sees a fresh pre-active-to-active call transition.
2. The service gathers a current snapshot: authorization, runtime permissions, call safety, number of live calls, projection state, target availability, and observed route.
3. `RoutingPolicy` rejects the snapshot unless all automatic-mode prerequisites hold. A manual one-shot bypasses only the master-toggle and projection checks.
4. When eligible, the policy allows at most one outstanding request. `AddressedTelecomRouter` resolves the configured address from Telecom’s supported Bluetooth-device list and calls `requestBluetoothAudio`.
5. The service waits for audio or endpoint callbacks. A target route is recorded as verification; a void API return is not verification.
6. The policy releases control after a verified route or stops permanently for the session after a safety-relevant cancellation condition.

## State machine

`RoutingPolicy` uses the following phases.

| Phase | Meaning |
|---|---|
| `IDLE` | No call session is active. |
| `WAITING` | A fresh active call was observed; prerequisites are being checked. |
| `VERIFYING` | A bounded routing request was submitted and callback evidence is awaited. |
| `STABILIZING` | The target route was observed. Only the explicitly configured competing route can lead to a bounded reassertion. |
| `RELEASED` | The startup guard is complete; user and system routing own the remaining call. |
| `SUSPENDED` | A user choice or safety-relevant event stopped further requests for this call session. |
| `FAILED` | The bounded window or request budget expired, or Telecom threw an exception. |

## Automatic versus manual mode

The master toggle is off by default. Automatic mode requires the projection-host gate and a fresh active transition. The manual one-shot exists for a parked, controlled test while the service is already bound. It retains Telecom authorization, runtime permission, SIM/non-emergency classification, single-call, target-availability, and target-identity checks. It performs no more than one request in its verification window.

## Platform compatibility

The application targets Android API 35 and requires Android API 34 or later. It retains `requestBluetoothAudio(BluetoothDevice)` because it needs address-specific matching and the current public `CallEndpoint` interface does not expose a Bluetooth hardware address. Android marks that method deprecated from API 34 and recommends `requestCallEndpointChange` for endpoint-driven routing. Any migration must preserve deterministic target identity, callback-based verification, and the existing fail-closed policy. [1]

## Non-goals

This implementation does not replace the default dialer, manage media profiles, disconnect Bluetooth, alter A2DP, record calls, read contacts or call logs, or upload telemetry. It is not an emergency-routing system, a driving-safety product, or evidence that an Android build, dialer, headset, vehicle, or projection host will accept the request.

## References

[1]: https://developer.android.com/reference/android/telecom/InCallService "Android Developers: InCallService reference"
