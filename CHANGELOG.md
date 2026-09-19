# Changelog

## Unreleased

- Add a privacy-safe parked-device capture harness that records only new structured routing traces,
  minimal device/app metadata, and explicit speaker, microphone, and Android Auto observations.
- Add a dependency-free trace analyzer with JSON/CSV output, integrity checks, strict dual-
  confirmation success semantics, and deterministic CI tests.

## 0.3.0-beta.2

- Retain stable API 36 builds while enforcing the exact API 37
  `onCallEndpointRequested()` virtual signature in the service harness.
- Preserve self-request identity across either API 37 callback order so an app request is not
  mistaken for an external/user override after the endpoint changes.
- Add stable routing reason codes and versioned, redacted, session-sequenced trace events.
- Record Telecom endpoint confirmation separately from exact target HFP audio/SCO confirmation.
- Add API 37 ordering, external-request, and dual-confirmation regression coverage.

## 0.3.0-beta.1

- Preserve the verified API 34+ Telecom endpoint routing proof of concept.
- Distinguish temporary unknown projection/HFP evidence from confirmed disconnection.
- Treat transient Telecom endpoint-list gaps as a safe pause instead of a permanent false failure.
- Prevent stale Bluetooth endpoints from being misclassified as a user-selected alternative route.
- Add regression coverage for observer uncertainty, endpoint churn, and recovery.
- Run the production-service callback harness as part of Gradle CI.
- Record the owner-confirmed real-device POC and define explicit production-release gates.
