# Production readiness

## Current classification

`0.3.0-beta.1` is a production-hardening beta. The real-device proof of concept validates the core Telecom endpoint approach, but intermittent behavior means the project must not yet be described as production-ready.

## Completed engineering gates

- Supported API 34+ `CallEndpoint` routing; no deprecated Bluetooth-audio request path.
- Samsung Phone remains the default dialer; protected access is verified through `TelecomManager`.
- Exact target resolution fails closed on ambiguity.
- Bounded, callback-driven requests with endpoint-change verification.
- User alternatives, emergency/unclassified calls, multiple calls, hold, conferences, authorization loss, confirmed observer loss, and lifecycle teardown stop automation.
- Temporary unknown projection/HFP evidence and transient endpoint-list gaps pause safely and can recover without a false disconnect or false user override.
- Deterministic policy, randomized transition, and production-service callback suites run locally; the service suite is also wired into Gradle CI.
- Diagnostics redact phone numbers, device names, and raw Bluetooth addresses.

## Release blockers

- Complete the real-car stability matrix in [TESTING.md](TESTING.md) using one unchanged APK.
- Classify every observed failure from the redacted event sequence and add a deterministic regression before changing routing behavior.
- Pass the complete Gradle build, JVM tests, service tests, Android lint, APK package/manifest inspection, and signature verification for the release commit.
- Verify upgrade and fresh-install flows, including AppOps authorization detection, revocation, reboot, process death, and settings preservation.
- Produce a signed release APK with a protected release key and publish its SHA-256 digest. Debug artifacts are not production releases.
- Confirm the privacy and safety documentation matches the final behavior and that no real device identifiers or private logs are committed.

## Stability rule

Do not add retries merely to improve a success percentage. A retry is permitted only for the explicitly configured competing endpoint, inside the bounded startup window, after fresh evidence confirms eligibility. Unknown evidence freezes routing; confirmed safety loss stops it; an alternative route that may be a user choice is never fought.
