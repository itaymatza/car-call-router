# Car Call Router for Android Auto and Bluetooth HFP

[![Build](https://github.com/itaymatza/car-call-router/actions/workflows/build.yml/badge.svg)](https://github.com/itaymatza/car-call-router/actions/workflows/build.yml)
[![CodeQL](https://github.com/itaymatza/car-call-router/actions/workflows/codeql.yml/badge.svg)](https://github.com/itaymatza/car-call-router/actions/workflows/codeql.yml)
[![Latest beta](https://img.shields.io/github/v/release/itaymatza/car-call-router?include_prereleases&label=latest%20beta)](https://github.com/itaymatza/car-call-router/releases)
[![Downloads](https://img.shields.io/github/downloads/itaymatza/car-call-router/total?label=downloads)](https://github.com/itaymatza/car-call-router/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[**Download current APK**](https://github.com/itaymatza/car-call-router/releases/download/v0.3.0-beta.3-debug/car-call-router-0.3.0-beta.3-debug.apk) · [All versions and release notes](https://github.com/itaymatza/car-call-router/releases)

![Car Call Router keeps Android Auto for navigation and media while a preferred Bluetooth HFP device handles calls.](docs/assets/social-preview.png)

**Keep Android Auto for navigation and media while routing calls through the Bluetooth hands-free device you trust.**

The installed app is named **Call Route Companion**. This repository is also described as
**Car Call Router** so people searching for an Android Auto call-audio or Bluetooth microphone
routing fix can find it.

## The problem

Some phones connected to both Android Auto and a vehicle's native Bluetooth system send calls to
the Android Auto head unit. On aftermarket units, that can mean a poor microphone even when the
vehicle's native hands-free system works well.

Call Route Companion automates the same choice a user can make from the active-call audio selector:
when an eligible cellular call becomes active, it asks Android Telecom to use a **current,
user-chosen Bluetooth call endpoint**. Android Auto remains connected for navigation and media.
The app does not become the default dialer or manipulate general media routing through
`AudioManager`.

### Common symptoms this project targets

- Android Auto uses the wrong or poor-quality microphone for phone calls.
- Calls go through an aftermarket Android Auto head unit instead of the car's native Bluetooth.
- You want Android Auto to stay connected for maps and music while another Bluetooth HFP device
  handles call speaker and microphone audio.
- Manually selecting the preferred car Bluetooth device during every call works, but you want that
  call-audio selection automated.

If those phrases describe your problem, start with [Is this for me?](#is-this-for-me) and the
[compatibility guide](docs/DEVICE-COMPATIBILITY.md).

## Is this for me?

It may fit when all of these are true:

- the phone runs Android 14 or newer;
- Android Auto and the preferred Bluetooth hands-free device are connected at the same time;
- manually choosing that Bluetooth device during a call already fixes both speaker and microphone;
- a one-time ADB authorization is acceptable.

It is not a fix for pairing failures, broken Bluetooth hardware, media-routing problems, emergency
calls, or systems where the preferred device is absent from Android's in-call audio selector. See
the [compatibility guide](docs/DEVICE-COMPATIBILITY.md) and [FAQ](docs/FAQ.md) before installing.


> This repository contains source code, deterministic tests, and a build workflow. The tracked source tree does **not** include device configurations, Bluetooth addresses, signing materials, APKs, or exported logs. Successful GitHub Actions runs publish a generated debug APK as a downloadable build artifact.

## Project status

The routing proof of concept has been confirmed by the project owner on the intended Samsung + Android Auto + native BMW Bluetooth setup: an active cellular call moved to the selected native hands-free endpoint, including its microphone, while Android Auto remained active. That confirms the core approach, not production reliability. Version `0.3.0-beta.3` adds non-overlapping, outcome-aware requests, separate evidence/action deadlines, transient-route debounce, persistent last-call results, and a protected signed-beta pipeline; see the [production-readiness gates](docs/PRODUCTION-READINESS.md).


## Public-repository posture


The tracked tree contains no real target-device settings. Device names and Bluetooth addresses are selected at runtime and stored only in the application’s private local preferences. Generated APKs, IDE configuration, local SDK configuration, test logs, captures, and conventional key/certificate files are excluded by `.gitignore`. GitHub Actions compiles, unit-tests, lints, and verifies the source. Normal builds upload an expiring debug artifact; the explicitly labeled debug pre-release publishes a durable APK, digest, and verification report. No APK is committed to the repository.


A public repository still exposes its files, commit history, issue/discussion content, and workflow logs. Do not commit exported logs, screenshots, pairing records, keystores, certificates, API keys, or private test notes. If the repository should not be forked, copied, or associated with its GitHub owner, use a private repository and consider a separately planned history rewrite.


## Configuration


### Build-time identity


The source namespace is the generic value `org.carcallrouter.companion`. Override the installed application ID and version metadata through Gradle properties without changing source:


```sh
bash gradlew \
  -PAPP_APPLICATION_ID=example.callroute \
  -PAPP_VERSION_CODE=5 \
  -PAPP_VERSION_NAME=0.3.0-beta.3 \
  :app:assembleDebug
```


`APP_APPLICATION_ID` defaults to `org.carcallrouter.companion`; `APP_VERSION_CODE` defaults to `5`; and `APP_VERSION_NAME` defaults to `0.3.0-beta.3`. Choose an application ID that you control before distributing a build. The source namespace remains generic and fixed so Kotlin and manifest class references stay consistent.


### Runtime configuration


The app is configuration-driven at runtime. Grant the requested runtime permissions, select the exact paired call device, complete the one-time ADB authorization, and verify the result in the app. You may also select the only competing device that may receive a bounded reassertion. The UI contains no built-in device name, address, car brand, phone brand, or dialer requirement.

## Why ADB is required

`MANAGE_ONGOING_CALLS` is a `signature|appop` permission. Android documents the companion path specifically for a physical **wearable**; associating a car is not a supported general grant mechanism. A normal utility APK also cannot request this permission through a runtime dialog. The other supported route is becoming the default dialer, which requires a complete dial pad plus incoming and ongoing call UI and would replace the user's existing Phone experience. This project deliberately does not impersonate an incomplete dialer.

The selected architecture is therefore a non-UI `InCallService` with a one-time ADB AppOps grant. The app checks `TelecomManager.hasManageOngoingCallsPermission()` and never treats a command, button tap, or service declaration as proof of authorization.


See the exact [authorization and troubleshooting guide](docs/AUTHORIZATION.md), [configuration guidance](docs/CONFIGURATION.md), [safety and privacy boundaries](docs/SAFETY.md), and [testing guidance](docs/TESTING.md).


## Releases and downloads

[GitHub Releases](https://github.com/itaymatza/car-call-router/releases) is the canonical version
history. Each published version has release notes and durable downloadable files, so a GitHub
account is not required just to download the APK.

| Version | Channel | Downloads |
| --- | --- | --- |
| `0.3.0-beta.3` | Android 14+ debug beta | [APK](https://github.com/itaymatza/car-call-router/releases/download/v0.3.0-beta.3-debug/car-call-router-0.3.0-beta.3-debug.apk) · [SHA-256](https://github.com/itaymatza/car-call-router/releases/download/v0.3.0-beta.3-debug/car-call-router-0.3.0-beta.3-debug.apk.sha256) · [Verification report](https://github.com/itaymatza/car-call-router/releases/download/v0.3.0-beta.3-debug/apk-verification.txt) |

New versions will appear automatically on the [Releases page](https://github.com/itaymatza/car-call-router/releases).

## Download and install the APK

The easiest path is the durable direct download:

1. Download [**car-call-router-0.3.0-beta.3-debug.apk**](https://github.com/itaymatza/car-call-router/releases/download/v0.3.0-beta.3-debug/car-call-router-0.3.0-beta.3-debug.apk).
2. Open the downloaded APK on the Android device.
3. If Android prompts you, temporarily allow **Install unknown apps** for the browser or file
   manager, install the APK, and then disable that permission again.

If Android reports that the package cannot be updated or is incompatible with the installed version, uninstall the existing build first and retry. Uninstalling clears the app's local configuration.

> This is a durable debug/test pre-release for Android 14+, not a production-ready signed release.
> Installation alone does not grant protected Telecom access. Open the app and complete the
> one-time authorization flow below. See the [release notes](docs/DEBUG-PRERELEASE-NOTES.md) and
> [production-readiness gates](docs/PRODUCTION-READINESS.md).

The [Actions build artifact](https://github.com/itaymatza/car-call-router/actions/workflows/build.yml)
remains available as a fallback for testing the newest `main` commit, but it requires a GitHub
sign-in, downloads as a ZIP, and expires.

### Authorize call routing in the app

1. Pair the intended car or headset in Android's Bluetooth settings and keep it nearby and powered on.
2. Open Call Route Companion and select **Allow permissions**.
3. Select **Choose Bluetooth device**, then choose the intended call device.
4. Select **Set up one-time ADB authorization** and follow the displayed steps.
5. Return to the app and tap **Verify**. Continue only after the status says authorization is detected.

See the exact [authorization and troubleshooting guide](docs/AUTHORIZATION.md), [configuration guidance](docs/CONFIGURATION.md), [research decision](docs/PLATFORM-RESEARCH.md), and [parked-car test procedure](docs/TESTING.md).

For repeatable in-place beta upgrades, configure the protected signed build described in
[stable beta signing](docs/SIGNING.md). Ordinary GitHub debug artifacts do not have a stable
certificate across hosted runners; the signed workflow also pins the expected certificate digest.

## Project documentation


Start with the [FAQ](docs/FAQ.md) and [compatibility guide](docs/DEVICE-COMPATIBILITY.md). Read the
[architecture reference](docs/ARCHITECTURE.md) for the component boundaries and routing state
machine. The [public-release guide](docs/PUBLIC_RELEASE.md) explains what remains visible in a
public repository and how to review a change before pushing it. Contributors should follow
[CONTRIBUTING.md](CONTRIBUTING.md); suspected security, privacy, or safety vulnerabilities belong
in the private reporting path described by [SECURITY.md](SECURITY.md), not a public issue.


## Safety model and limitations


The policy fails closed. It requires Telecom authorization, runtime permissions, a verifiably SIM-backed non-emergency call, a single active call, a configured target present in both Telecom and HFP state, and—when using automatic mode—a verified projection-host state. Evidence may arrive for up to ten seconds; once routing starts, a separate 5.5-second action/guard window applies. At most three requests are allowed, never concurrently and never less than 2.5 seconds apart. Timeout and stale-endpoint failures have typed, bounded recovery; external cancellation and unknown failures stop the session. A possible alternative route during the first second after the app's request must persist before it is classified as a user override. The explicit manual one-shot bypasses only the automatic toggle and projection gate; it does not bypass authorization, identity, device-presence, or emergency safeguards.


Routing uses API 34+ `requestCallEndpointChange()` with an endpoint object from the latest `onAvailableCallEndpointsChanged()` callback. The deprecated `requestBluetoothAudio(BluetoothDevice)` path has been removed. Because the public endpoint API exposes a name and UUID but no Bluetooth address, the app resolves identity only when the saved device name is unique among live Bluetooth endpoints, or when exactly one HFP device and one Bluetooth endpoint exist. Ambiguity fails closed. Endpoint UUIDs are not persisted.

Diagnostics deliberately distinguish `TELECOM_ENDPOINT_CONFIRMED` from
`TARGET_HFP_AUDIO_CONFIRMED`. The former means Telecom selected the intended endpoint object; the
latter also corroborates that the exact locally configured Bluetooth address owns HFP audio/SCO.
Neither replaces a parked physical microphone and speaker test.


> Configure and test only while parked. Do not rely on this project for emergency, safety-critical, or hands-free compliance use cases.


## Verification

Run `bash tools/test-all.sh` for the pure-JVM core, deterministic property, JSONL replay, and service-callback suites. The `:core` module enforces at least 90% branch coverage. The pull-request workflow also executes the production-service callback harness through Gradle, assembles the APK, runs JVM unit tests and Android lint, then verifies the APK signature and manifest identity before publishing the APK with its SHA-256 verification record. Passing those checks does not establish stable Samsung, Android Auto, Bluetooth, microphone, or speaker behavior; use the parked-car procedure and stability matrix in [testing guidance](docs/TESTING.md).

For repeatable real-device evidence, run
`bash tools/capture_device_run.sh --serial PHONE_SERIAL --scenario outgoing` while parked. The
harness stores only new structured app traces plus explicit physical observations in a local,
Git-ignored run directory and emits `PASS` only when exact HFP audio and all required observations
agree. Controlled tags classify each matrix cell, and

```sh
python3 tools/summarize_qualification.py verification/device-runs --require-ready
```

verifies that the batch used one APK/phone build and reached every required passing count.
