# Contributing

## Scope

Contributions are welcome when they preserve the project’s core properties: user-configured device selection, no embedded personal data, explicit user control, bounded behavior, and fail-closed call-safety checks. The project is a reference implementation, not a promise of compatibility with a particular device, dialer, Bluetooth stack, or projection environment.

Changes that reduce eligibility checks, bypass protected authorization, infer hardware identity from display names, retry indefinitely, or automatically fight an unconfigured route are out of scope unless they are accompanied by a new safety analysis and tests that preserve an equivalent or stronger boundary.

## Before opening a pull request

Create a focused branch from the current default branch. Keep real-device data, diagnostic captures, and build artifacts local. Update documentation when behavior, configuration, privacy, safety, or verification scope changes.

Run the deterministic suites with a JDK 17+ and Kotlin compiler installed:

```sh
bash tools/test-all.sh
```

Before changing a release version, update every release-critical declaration and run:

```sh
python3 tools/check_version_consistency.py
```

When an Android SDK is available, also run:

```sh
bash gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --stacktrace --console=plain
```

Review staged changes before pushing:

```sh
git diff --cached --check
git diff --cached
```

Do not add the fresh `verification/current/` logs created by local tests; those files are local evidence and are ignored by Git. Release integrity is established by the protected signing workflow, published digest, and artifact attestation—not a mutable checksum list in the source tree.

## Tests and documentation

Add or update deterministic policy cases for changes to routing decisions. Add or update production-service scenarios for changes that involve callback order, lifecycle behavior, route observation, authorization, projection state, HFP state, or session cancellation. State clearly when a test uses framework doubles rather than a real Android device.

Documentation must distinguish implementation behavior from device-level evidence. Do not claim that a build, test harness, emulator, or API return proves physical call routing, audio, microphone, or safety behavior.

## Pull-request description

Explain the user-visible purpose of the change, the safety assumptions it preserves or changes, the tests run, and any platform versions or device conditions that remain unverified. Keep sample identifiers synthetic and locally administered. Do not include phone numbers, device addresses, screenshots containing account details, exported logs, or credentials.

## Reporting vulnerabilities

Do not use a public issue for suspected security, privacy, or safety vulnerabilities. Follow the repository’s [security policy](SECURITY.md) instead.
