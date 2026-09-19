# Call Route Companion


**A generic Android 14+ reference implementation for a safety-bounded, user-configured call-audio route request.**


Call Route Companion is a Kotlin and framework-XML Android utility. When an eligible cellular call makes a fresh transition to `ACTIVE`, it asks Android Telecom to select a **current, user-chosen Bluetooth call endpoint**. It does not change Android Auto media/navigation routing, become the default dialer, or manipulate `AudioManager`.


> This repository contains source code, deterministic tests, and a build workflow. The tracked source tree does **not** include device configurations, Bluetooth addresses, signing materials, APKs, or exported logs. Successful GitHub Actions runs publish a generated debug APK as a downloadable build artifact.

## Project status

The routing proof of concept has been confirmed by the project owner on the intended Samsung + Android Auto + native BMW Bluetooth setup: an active cellular call moved to the selected native hands-free endpoint, including its microphone, while Android Auto remained active. That confirms the core approach, not production reliability. Version `0.3.0-beta.1` begins the stability-qualification phase; see the [production-readiness gates](docs/PRODUCTION-READINESS.md).


## Public-repository posture


The tracked tree contains no real target-device settings. Device names and Bluetooth addresses are selected at runtime and stored only in the application’s private local preferences. Generated APKs, IDE configuration, local SDK configuration, test logs, captures, and conventional key/certificate files are excluded by `.gitignore`. GitHub Actions compiles, unit-tests, and lints the source, then uploads only the generated debug APK as an expiring build artifact. The APK is not committed to the repository.


A public repository still exposes its files, commit history, issue/discussion content, and workflow logs. Do not commit exported logs, screenshots, pairing records, keystores, certificates, API keys, or private test notes. If the repository should not be forked, copied, or associated with its GitHub owner, use a private repository and consider a separately planned history rewrite.


## Configuration


### Build-time identity


The source namespace is the generic value `org.carcallrouter.companion`. Override the installed application ID and version metadata through Gradle properties without changing source:


```sh
bash gradlew \
  -PAPP_APPLICATION_ID=example.callroute \
  -PAPP_VERSION_CODE=3 \
  -PAPP_VERSION_NAME=0.3.0-beta.1 \
  :app:assembleDebug
```


`APP_APPLICATION_ID` defaults to `org.carcallrouter.companion`; `APP_VERSION_CODE` defaults to `3`; and `APP_VERSION_NAME` defaults to `0.3.0-beta.1`. Choose an application ID that you control before distributing a build. The source namespace remains generic and fixed so Kotlin and manifest class references stay consistent.


### Runtime configuration


The app is configuration-driven at runtime. Grant the requested runtime permissions, select the exact paired call device, complete the one-time ADB authorization, and verify the result in the app. You may also select the only competing device that may receive a bounded reassertion. The UI contains no built-in device name, address, car brand, phone brand, or dialer requirement.

## Why ADB is required

`MANAGE_ONGOING_CALLS` is a `signature|appop` permission. Android documents the companion path specifically for a physical **wearable**; associating a car is not a supported general grant mechanism. A normal utility APK also cannot request this permission through a runtime dialog. The other supported route is becoming the default dialer, which requires a complete dial pad plus incoming and ongoing call UI and would replace the user's existing Phone experience. This project deliberately does not impersonate an incomplete dialer.

The selected architecture is therefore a non-UI `InCallService` with a one-time ADB AppOps grant. The app checks `TelecomManager.hasManageOngoingCallsPermission()` and never treats a command, button tap, or service declaration as proof of authorization.


See [configuration guidance](docs/CONFIGURATION.md), [safety and privacy boundaries](docs/SAFETY.md), and [testing guidance](docs/TESTING.md).


## Download and install the APK

Each successful GitHub Actions run publishes the generated debug APK as a build artifact:

1. Sign in to GitHub and open [Build and test Android source](https://github.com/itaymatza/car-call-router/actions/workflows/build.yml).
2. Open the latest successful run for the **main** branch.
3. In the **Artifacts** section, select **car-call-router-debug-apk** to download the ZIP archive.
4. Extract the archive and transfer **app-debug.apk** to the Android device.
5. Open **app-debug.apk** on the device. If Android prompts you, temporarily allow **Install unknown apps** for the browser or file manager you used, install the APK, and then disable that permission again.

If Android reports that the package cannot be updated or is incompatible with the installed version, uninstall the existing build first and retry. Uninstalling clears the app's local configuration.

> This is a debug/test build for Android 14+. Installation alone does not grant protected Telecom access. Open the app and complete the one-time authorization flow below.

### Authorize call routing in the app

1. Pair the intended car or headset in Android's Bluetooth settings and keep it nearby and powered on.
2. Open Call Route Companion and select **Allow permissions**.
3. Select **Choose Bluetooth device**, then choose the intended call device.
4. Select **Set up one-time ADB authorization** and follow the displayed steps.
5. Return to the app and tap **Verify**. Continue only after the status says authorization is detected.

See the full [configuration guidance](docs/CONFIGURATION.md), [research decision](docs/PLATFORM-RESEARCH.md), and [parked-car test procedure](docs/TESTING.md).

## Project documentation


Read the [architecture reference](docs/ARCHITECTURE.md) for the component boundaries and routing state machine. The [public-release guide](docs/PUBLIC_RELEASE.md) explains what remains visible in a public repository and how to review a change before pushing it. Contributors should follow [CONTRIBUTING.md](CONTRIBUTING.md); suspected security, privacy, or safety vulnerabilities belong in the private reporting path described by [SECURITY.md](SECURITY.md), not a public issue.


## Safety model and limitations


The policy fails closed. It requires Telecom authorization, runtime permissions, a verifiably SIM-backed non-emergency call, a single active call, a configured target present in both Telecom and HFP state, and—when using automatic mode—a verified projection-host state. It makes at most three automatic requests in a four-second startup window, observes route changes, and stops on confirmed safety-relevant events, possible user overrides, authorization loss, projection loss, target HFP loss, call hold, a second call, or a routing exception. Temporary unknown projection/HFP evidence and transient endpoint-list gaps freeze requests until fresh evidence arrives instead of being misclassified as a disconnect or user override. The explicit manual one-shot bypasses only the automatic toggle and projection gate; it does not bypass authorization, identity, device-presence, or emergency safeguards.


Routing uses API 34+ `requestCallEndpointChange()` with an endpoint object from the latest `onAvailableCallEndpointsChanged()` callback. The deprecated `requestBluetoothAudio(BluetoothDevice)` path has been removed. Because the public endpoint API exposes a name and UUID but no Bluetooth address, the app resolves identity only when the saved device name is unique among live Bluetooth endpoints, or when exactly one HFP device and one Bluetooth endpoint exist. Ambiguity fails closed. Endpoint UUIDs are not persisted.


> Configure and test only while parked. Do not rely on this project for emergency, safety-critical, or hands-free compliance use cases.


## Verification

Run `bash tools/test-all.sh` for the deterministic policy, property, and service-callback suites. The pull-request workflow also executes the production-service callback harness through Gradle, assembles the APK, runs JVM unit tests, and runs Android lint before publishing the APK artifact. Passing those checks does not establish stable Samsung, Android Auto, Bluetooth, microphone, or speaker behavior; use the parked-car procedure and stability matrix in [testing guidance](docs/TESTING.md).
