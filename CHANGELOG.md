# Changelog

## Unreleased

## 0.3.0-beta.7

- Add privacy-safe process, UI, Telecom-service, and call lifecycle correlation so an exported log
  proves whether Samsung bound the service before or only after the app was opened.
- Record process age, foreground importance, screen-interactive state, battery-optimization
  exemption, app-standby bucket, and the prior process-exit reason at the relevant lifecycle edges.
- Preserve the existing fail-safe for already-active calls; this diagnostic release does not
  guess that a late bind is a fresh call or silently override a route chosen mid-call.

## 0.3.0-beta.6

- Correlate API 37 endpoint-request callbacks with generation-bound, expiring request tickets so
  delayed callbacks cannot leak across call sessions.
- Classify Samsung call-start callback replays separately from app requests and genuine external
  requests, including the pre-ACTIVE/replayed-after-submit ordering observed on the real device.
- Add request IDs, generations, callback latency, endpoint revision, route context, and a compact
  per-session diagnostic summary while retaining salted device aliases and no call identifiers.
- Detect endpoint oscillation and external-request interference as `UNSTABLE` qualification
  results instead of allowing a later target confirmation to produce a false pass.
- Suppress repeated identical suspension events and retain three bounded diagnostic log archives
  so exported evidence is both less noisy and more useful across consecutive calls.

## 0.3.0-beta.5

- Fix automatic routing on Android 17/Samsung when Telecom replays its call-start endpoint request
  around the transition to an active call.
- Preserve the safety behavior that suspends routing for genuine external endpoint requests after
  Car Call Router has acted.
- Add a deterministic production-service regression for the exact callback ordering observed in
  the field and publish the fix as a direct-download debug beta.

## 0.3.0-beta.4

- Align the installed app with the Car Call Router product identity, add Android application
  metadata and an adaptive themed icon, expose releases/source/support inside the app, and add
  reusable Android store-listing metadata with automated drift checks.
- Make GitHub Releases the prominent version and download hub, with latest-version and download
  badges plus direct APK, checksum, verification-report, and full-history links.
- Publish a durable GitHub debug pre-release with a direct APK download, checksum, verification
  report, provenance attestation, and clear signing/qualification limitations.
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
