# Changelog

## Unreleased

- Improve repository discoverability with problem-oriented Android Auto, Bluetooth HFP, call-audio,
  and microphone-routing language plus a shareable social-preview asset.
- Add problem-first onboarding, compatibility and FAQ documentation, structured community-health
  files, duplicate-run cancellation, dependency review, and an automated version-consistency gate.
- Pin signed releases to an expected certificate SHA-256, fail closed on signing-key drift, and
  leave only the intentionally deferred target-SDK warning in the Android lint baseline.
- Record the exact installed APK hash and controlled qualification tags for parked device runs, and
  add an offline batch report that enforces build consistency and production-matrix counts.
- Resolve nine Android lint findings: avoid retaining the projection monitor through an async
  query, use plural resources for setup progress, remove redundant drawing, and delete stale
  compatibility strings.
- Centralize plugin and library versions in a Gradle version catalog.
- Enforce ktlint across all Kotlin modules, Kotlin compiler warnings-as-errors, and Android lint
  warnings-as-errors with a checked-in baseline; reformat the existing Kotlin source tree.
- Move the framework-double service harness to a conventional Gradle source layout.
- Extract Android-independent routing, safety, endpoint identity, and trace behavior into a
  dedicated pure-JVM `:core` module shared by the app and service harness.
- Replace the ad-hoc core `kotlinc` launchers with Gradle/JUnit tests and an enforced 90% branch-
  coverage gate.
- Add explicit slow-SCO, head-unit-reclaim, intermediate-handset, projection-unknown, connection-
  order scenarios, plus JSONL regression-trace replay.

## 0.3.0-beta.3

- Add a privacy-safe parked-device capture harness that records only new structured routing traces,
  minimal device/app metadata, and explicit speaker, microphone, and Android Auto observations.
- Add a dependency-free trace analyzer with JSON/CSV output, integrity checks, strict dual-
  confirmation success semantics, and deterministic CI tests.
- Add CI and installer enforcement for APK signature, package/version identity, SDK bounds,
  required permissions, debuggability, and SHA-256 evidence.
- Add service regressions for mid-call authorization/runtime-permission revocation and safe process
  recreation during an already-active call.
- Enforce a single in-flight Telecom request with generation-tokened callbacks and a 2.5-second
  retry gap that respects AOSP's two-second request timeout.
- Add error-specific bounded recovery: timeout may retry, endpoint disappearance requires a fresh
  endpoint snapshot, and external cancellation or unknown failure stops the session.
- Separate the evidence-gathering and routing-action deadlines, and debounce transient alternative
  routes only during the first second after the app's own request.
- Persist a privacy-safe last-call result in the UI and suppress duplicate HFP/projection logs.
- Add a protected signed-beta workflow with APK identity verification, SHA-256 publication, and
  GitHub artifact attestation; add Dependabot, Gradle caching, and CodeQL workflows.

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
