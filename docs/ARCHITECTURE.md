# Architecture

## Design objective

**Car Call Router** is an Android 14+ reference application that can make a deliberately bounded request to route an eligible call to a user-selected Bluetooth call endpoint. It is not a dialer, does not own the call lifecycle, and does not promise that a request will be honored. A successful `requestCallEndpointChange()` outcome means the request was accepted; only `onCallEndpointChanged()` verifies the final route. [1]

The design treats autonomous call-audio routing as a safety-sensitive feature. Its default posture is **fail closed**: when it cannot establish that a condition is safe, it makes no request or stops the current session.

## Components

The repository separates platform-independent behavior into the pure-JVM `:core` module. The
Android `:app` and framework-double `:verification:service-tests` modules both depend on that same
compiled implementation, preventing either harness from maintaining a private copy.

| Component | Responsibility | Safety boundary |
|---|---|---|
| `MainActivity` | Collects runtime permission, device selection, master-toggle, manual-test, pause, and diagnostic-export choices. | User actions are explicit. It cannot enable automation without a configured target and granted prerequisites. |
| `RouterSettings` | Persists the enabled state plus the locally chosen target and optional competing Bluetooth devices in app-private preferences. | No device identifiers are compiled into the source tree or sent over the network. |
| `Access` | Checks runtime permissions and the protected ongoing-call authorization. | Missing or revoked access stops routing eligibility. The displayed AppOps command derives from the installed application ID. |
| `ProjectionMonitor` | Reads the AndroidX host-provider state for optional projection gating. | Missing or unknown evidence freezes requests; confirmed loss stops an active guard. It does not infer state from names, Wi-Fi, or process activity. |
| `HfpMonitor` | Tracks the target device’s HFP connection and SCO state through the Bluetooth profile service. | It starts only for verified projection or an explicit manual test, stops on confirmed projection loss, and re-queries profile state rather than trusting broadcast extras. |
| `CellularClassifier` and `CallSafety` | Determine whether the call is a single, verifiable, non-emergency SIM-backed call. | Emergency, hidden/unclassifiable, external, self-managed, conference, and multi-call cases are rejected. |
| `RoutingPolicy` (`:core`) | Pure Kotlin state machine that decides whether a routing request is permitted, delayed, released, or stopped. | Uses separate evidence and action deadlines, permits one request in flight, enforces a 2.5-second request gap and a three-request maximum, and classifies platform outcomes. |
| `RoutingTrace` | Emits versioned, redacted, session-sequenced evidence with stable reason codes and elapsed timings. | Separates a Telecom endpoint observation from exact target HFP audio/SCO evidence; it does not claim physical microphone quality. |
| `EndpointIdentity` and `AddressedTelecomRouter` | Resolve the saved paired target against live API 34+ endpoints and issue the request. | Uses only callback-supplied endpoint objects. Unique label or one-to-one HFP topology is required; ambiguity fails closed. |
| `RouterInCallService` | Coordinates Telecom, route, endpoint, projection, HFP, and call callbacks on the main thread. | Safety-relevant events are latched before deferred evaluation so that later callbacks cannot erase them. |

## Decision flow

1. Telecom binds `RouterInCallService` and the service starts its lightweight projection observer. HFP observation starts only after projection is verified or a parked manual test is requested. The service normally waits for a fresh pre-active-to-active call transition; a new call first observed as active can use the stricter late-bind evidence path.
2. The service gathers a current snapshot: authorization, runtime permissions, call safety, number of live calls, projection state, target availability, and observed route.
3. `RoutingPolicy` rejects the snapshot unless all automatic-mode prerequisites hold. A manual one-shot bypasses only the master-toggle and projection checks.
4. When eligible, the policy allows at most one outstanding request. `AddressedTelecomRouter` submits the exact current callback object to `requestCallEndpointChange`.
5. The service waits for `onCallEndpointChanged`. An accepted outcome is not route verification.
6. A matching endpoint produces `TELECOM_ENDPOINT_CONFIRMED`. Only a matching endpoint together
   with the exact configured Bluetooth address in HFP audio/SCO state produces
   `TARGET_HFP_AUDIO_CONFIRMED`.
7. Temporary unknown observer evidence freezes requests until a fresh callback arrives. Confirmed loss or a user alternative stops the session. The policy releases control after the bounded startup guard.

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

The application compiles and targets stable API 36 and requires API 34 or later. It declares API
37's exact `onCallEndpointRequested(CallEndpoint)` virtual signature, which is enforced by the
service harness while the API 37 platform package is unavailable to hosted SDK Manager builds.
Android 17 can dispatch the method; API 34–36 safely ignore it. Telecom may report
an initiating request before or after the resulting endpoint change, so self-request markers are
consumed only by the request callback and are cleared at session teardown. A non-self request stops
the bounded guard as a possible user override. Endpoint-change callbacks and the existing
route-observation rules provide the available protection on earlier versions.

`CallEndpoint.identifier` is unique on the device but the public contract does not promise a persistent Bluetooth-address identity, and AOSP keeps the Bluetooth address mapping inside Telecom. The app therefore never persists the identifier. It maps the saved paired device to each live callback set using a unique endpoint label, with a one-endpoint/one-HFP-device fallback. This is intentionally conservative and OEM-dependent.

## Non-goals

This implementation does not replace the default dialer, manage media profiles, disconnect Bluetooth, alter A2DP, record calls, read contacts or call logs, or upload telemetry. It is not an emergency-routing system, a driving-safety product, or evidence that an Android build, dialer, headset, vehicle, or projection host will accept the request.

## References

[1]: https://developer.android.com/reference/android/telecom/InCallService "Android Developers: InCallService reference"
