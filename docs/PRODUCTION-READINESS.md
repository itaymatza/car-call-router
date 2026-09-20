# Production readiness

## Current classification

`0.3.0-beta.8` is a production-hardening beta. The real-device proof of concept validates the core Telecom endpoint approach, but intermittent behavior means the project must not yet be described as production-ready.

## Completed engineering gates

- Supported API 34+ `CallEndpoint` routing; no deprecated Bluetooth-audio request path.
- Samsung Phone remains the default dialer; protected access is verified through `TelecomManager`.
- Exact target resolution fails closed on ambiguity.
- Bounded, callback-driven requests with endpoint-change verification.
- User alternatives, emergency/unclassified calls, multiple calls, hold, conferences, authorization loss, confirmed observer loss, and lifecycle teardown stop automation.
- Temporary unknown projection/HFP evidence and transient endpoint-list gaps pause safely and can recover without a false disconnect or false user override.
- Deterministic policy, randomized transition, and production-service callback suites run locally; the service suite is also wired into Gradle CI.
- Diagnostics redact phone numbers, device names, and raw Bluetooth addresses.
- The API 37 endpoint-request signature and both callback orders are regression-tested; final
  dispatch validation remains a real Android 17 device gate until hosted API 37 SDK builds exist.
- Structured evidence separates Telecom endpoint confirmation from exact target HFP audio/SCO confirmation.
- A privacy-bounded ADB harness isolates each new device session, validates trace integrity, records
  physical speaker/microphone and Android Auto observations, and emits a conservative combined
  pass/fail verdict. Its parser has deterministic CI coverage.
- Qualification aggregation verifies one unchanged installed APK and phone build, counts only
  passing evidence toward every matrix cell, identifies missing trials, and reports the `3/n`
  bound only for failure-free batches.
- CI fails on APK signature rejection, package/version or SDK drift, missing required permissions,
  and unexpected debuggability; its artifact includes the exact APK and certificate SHA-256 values.
- Service regressions cover authorization and runtime-permission revocation during a pending route,
  plus process recreation without taking over an already-active call.
- Requests are single-flight and generation-tokened. AOSP's two-second timeout is respected, new
  requests are separated by 2.5 seconds, timeout/stale-endpoint errors have bounded typed recovery,
  and an endpoint-gone retry requires a newer endpoint snapshot.
- API 37 request markers are call-generation-bound and expire; late result callbacks are ignored,
  Samsung startup-request replays are distinguished from genuine external requests, and the
  diagnostic analyzer rejects route oscillation or external interference as an unstable run.
- Evidence collection and routing action use separate deadlines, transient alternative routes are
  debounced only during the immediate post-request settling period, and the UI persists the last
  completed session result.
- A protected, manually triggered/tag-triggered workflow builds a non-debuggable signed APK,
  verifies its identity, publishes its SHA-256 digest, and creates a GitHub artifact attestation.

## Release blockers

- Complete the real-car stability matrix in [TESTING.md](TESTING.md) using one unchanged APK and
  retain one capture-harness record per trial.
- Classify every observed failure from the redacted event sequence and add a deterministic regression before changing routing behavior.
- Pass the complete Gradle build, JVM tests, service tests, Android lint, and APK verification for
  the release commit; repeat the APK gate against the separately signed, non-debuggable release.
- Verify upgrade and fresh-install flows, including AppOps authorization detection, revocation, reboot, process death, and settings preservation.
- Configure and protect the `beta-signing` environment secrets and pinned certificate variable,
  run the signed workflow, and prove that an update signed with the same key retains application
  data and the AppOps authorization.
- Confirm the privacy and safety documentation matches the final behavior and that no real device identifiers or private logs are committed.

## Stability rule

Do not add retries merely to improve a success percentage. A retry is permitted only for the explicitly configured competing endpoint, inside the bounded startup window, after fresh evidence confirms eligibility. Unknown evidence freezes routing; confirmed safety loss stops it; an alternative route that may be a user choice is never fought.
