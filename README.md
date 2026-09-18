# Call Route Companion

**A generic Android 14+ reference implementation for a safety-bounded, user-configured call-audio route request.**

Call Route Companion is a Kotlin and framework-XML Android companion application. When an eligible cellular call makes a fresh transition to `ACTIVE`, it can request Telecom to route call audio to a **locally selected paired Bluetooth device**. The optional projection-state gate is based on the AndroidX host-provider protocol, rather than a particular hardware ecosystem, handset, dialer, or projection product.

> This repository contains source code, deterministic tests, and a build workflow. It does **not** include device configurations, Bluetooth addresses, signing materials, APKs, exported logs, or a claim of real-device behavior.

## Public-repository posture

The tracked tree contains no real target-device settings. Device names and Bluetooth addresses are selected at runtime and stored only in the application’s private local preferences. Generated APKs, IDE configuration, local SDK configuration, test logs, captures, and conventional key/certificate files are excluded by `.gitignore`. GitHub Actions compiles, unit-tests, and lints the source but does not upload an APK or diagnostic artifact.

A public repository still exposes its files, commit history, issue/discussion content, and workflow logs. Do not commit exported logs, screenshots, pairing records, keystores, certificates, API keys, or private test notes. If the repository should not be forked, copied, or associated with its GitHub owner, use a private repository and consider a separately planned history rewrite.

## Configuration

### Build-time identity

The source namespace is the generic value `org.carcallrouter.companion`. Override the installed application ID and version metadata through Gradle properties without changing source:

```sh
bash gradlew \
  -PAPP_APPLICATION_ID=example.callroute \
  -PAPP_VERSION_CODE=1 \
  -PAPP_VERSION_NAME=0.1.0 \
  :app:assembleDebug
```

`APP_APPLICATION_ID` defaults to `org.carcallrouter.companion`; `APP_VERSION_CODE` defaults to `1`; and `APP_VERSION_NAME` defaults to `0.1.0`. Choose an application ID that you control before distributing a build. The source namespace remains generic and fixed so Kotlin and manifest class references stay consistent.

### Runtime configuration

The app is deliberately configuration-driven at runtime. Before enabling automation, grant the requested runtime permissions, obtain the protected Telecom authorization on the test device, select a paired target Bluetooth call device, and optionally select the only competing device that may receive a bounded reassertion. The UI does not contain a built-in device name, address, car brand, phone brand, or dialer requirement.

See [configuration guidance](docs/CONFIGURATION.md), [safety and privacy boundaries](docs/SAFETY.md), and [testing guidance](docs/TESTING.md).

## Safety model and limitations

The policy fails closed. It requires Telecom authorization, runtime permissions, a verifiably SIM-backed non-emergency call, a single active call, a configured target present in both Telecom and HFP state, and—when using automatic mode—a verified projection-host state. It makes at most three automatic requests in a four-second startup window, observes route changes, and stops on safety-relevant events, possible user overrides, authorization loss, projection loss, target loss, call hold, a second call, or a routing exception. The explicit manual one-shot bypasses only the automatic toggle and projection gate; it does not bypass authorization, identity, device-presence, or emergency safeguards.

The Android compatibility boundary uses the deprecated `Telecom.requestBluetoothAudio(BluetoothDevice)` API because current public `CallEndpoint` APIs do not expose a stable Bluetooth hardware address for deterministic target matching. This is a reference implementation, not a guarantee of acceptance by every Android build, dialer, Bluetooth stack, vehicle, or headset.

> Configure and test only while parked. Do not rely on this project for emergency, safety-critical, or hands-free compliance use cases.

## Verification

With a JDK 17+ and Kotlin compiler installed:

```sh
bash tools/test-all.sh
```

The deterministic host-JVM suites exercise the pure policy and production-service code against framework doubles. They are not Android emulator, APK installation, Telecom admission, Bluetooth, microphone, or vehicle tests. The GitHub Actions workflow additionally builds, tests, and lints Android source with an Android SDK.

## License

Licensed under the [MIT License](LICENSE).
