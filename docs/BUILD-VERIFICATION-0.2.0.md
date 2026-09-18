# Car Call Router 0.2.0 — actual build and verification

Date: 17 September 2026. Status: **signed device-test APK produced**, not a proven Samsung/BMW fix.

## Delivered binary

- File: `CarCallRouter-0.2.0.apk`
- Package: `com.itaymatza.carcallrouter`
- Version: `0.2.0-device-test`, version code 3
- Minimum Android API 34 (Android 14); target API 35
- Size: 2,110,064 bytes (about 2.11 MB)
- SHA-256: `55994f6616f1a16d21638a88d84b1d2c119fb5598162a6b286b4c6c4c8258edb`
- Signing certificate SHA-256: `6da0528e875d12635c879915a1e724e7279425b8e50cba4e37ebf36c7b2c50db`
- Self-signed personal application certificate; no claim of Google/Samsung certification.

## Checks actually executed

| Check | Result |
|---|---|
| Resource compilation and manifest/resource linking with aapt2 | Passed |
| Java generated-class compilation and full Kotlin app compilation | Passed; no remaining compiler errors |
| D8 conversion of app and Kotlin runtime to Android bytecode | Passed |
| Executable routing/safety policy tests | 62/62 passed |
| Seeded policy exercise | 5,000 traces, 250,000 evaluated transitions; safety assertions passed |
| Supported-platform signature verification using AOSP apksig | Passed, v3; no errors/warnings |
| Separate v2 signature-block verification | Passed |
| ZIP CRCs, duplicate/path checks, DEX SHA-1 and Adler-32 | Passed |
| Four-byte alignment of stored entries, including resources.arsc | Passed, eight entries |
| Compiled DEX class-namespace audit | App code and Kotlin runtime only; 69 app classes, 1,042 total |
| Android manifest permissions/service inspection | Expected package, launcher, protected non-UI service and three permissions |
| Binary routing-call inspection | Telecom.requestBluetoothAudio present; prohibited AudioManager routing calls absent |

The v2 check deliberately asks the verifier to inspect the v2 block using its Android 7 verification
path, separately from the normal Android 14+ v3 verification. This does NOT lower the APK's minimum
Android version. Both actual signature blocks verify.

Policy tests and randomized traces exercise the state machine, not Android, the Bluetooth stack,
audio quality, or the truth of an Android Auto connection signal. There is no emulator or connected
phone in this environment. **APK installation, UI launch, Samsung Telecom admission, real HFP/SCO
routing, BMW microphone/output, reboot, and screen-off behavior have NOT been tested.** Android lint
and the Gradle build were not run successfully; the compiled APK used the explicit tool pipeline below.

## Build method and provenance

Ordinary Gradle/Android SDK downloads failed in this environment. Binary build components were
retrieved through read-only downloads of existing public GitHub Actions artifacts instead. No
repository was created or modified, no other app was substituted, and no private phone data was sent.

1. `iBotPeaches/Apktool`, commit `baa603f353a51b932f136584358acf025c748895`,
   run `33883000687`, artifact `9940931321`: extracted the bundled Linux aapt2 executable.
   Archive SHA-256: `bfe635e73351d2b5df4a5f34c0c08eb77879fffd37cfae474370d9b7c0ecae41`.
2. `skylot/jadx`, commit `2fb1b16386941660fda07e9017285aec40fcb37f`,
   run `34711133754`, artifact `10303312290`: used the bundled R8/D8 compiler and AOSP apksig library.
   Archive SHA-256: `189f64976f61e6ffa7b4adaf5225fbacdd43c6e2eda732b96241d10da4d01a14`.
3. `tyron12233/CodeAssist`, commit `ba4c352b423055a1773be5bbdd67bcb393c96ec8`,
   run `35171098934`, artifact `10476991132`: extracted only `assets/android.jar` as the compile/link
   platform library. No CodeAssist application classes or native libraries are in the delivered APK.
   Archive SHA-256: `9a8c5e801a8df5d08d2e5a969a8640d9b209c440178eee53c16a083c84fc9154`.
   Extracted android.jar SHA-256: `392a88db4436e8c8694bb643ac211e125bebcb2e41d22b7eb2a62af83025c5e2`.
   This is a compile-only SDK stub artifact, not a complete SDK installation. Its resource metadata
   identifies platform 36, while its VERSION_CODES declarations inspected here extend through API35.
   Every Android API referenced by this application resolved at compilation. The actual APK's minimum
   and target SDK are explicitly 34 and 35; its linked compile-resource metadata is 36.
4. Locally available OpenJDK 21.0.11 and Kotlin compiler/standard library 1.9.0, with JVM target17.

Actual pipeline: aapt2 compile/link -> javac generated R/BuildConfig -> kotlinc app -> D8 app/runtime
-> aligned ZIP packaging -> AOSP apksig signing -> AOSP apksig verification -> binary integrity audit.

`tools/SignVerify.java` is a thin launcher for AOSP apksig, not a custom cryptographic signature scheme.
The conventional Android Studio/Gradle configuration remains included for normal rebuilding; it was
not the pipeline executed here. Different compiler versions/keys do not reproduce the exact binary.
Signing private keys and password files are not included in the APK, source archive, or reports.

## Changes versus the earlier source-only version

- Replaced the external AndroidX projection dependency with a small lifecycle-scoped Kotlin adapter
  of the same official CarConnection provider protocol. Initial asynchronous query plus change
  broadcasts; no Bluetooth-name/Wi-Fi inference or periodic polling. Unknown state fails closed.
- Added cursor closing and generation-aware callbacks to the projection adapter.
- Fixed an actual Kotlin compilation error by explicitly using `java.util.ArrayDeque` for logs.
- Added a copyable phone-local ADB command and clarified phone-only versus desktop setup.
- Kept the public deprecated, device-address-specific Telecom routing API. No invalid endpoint UUID
  versus MAC matching. A CallEndpoint UUID is not a Bluetooth MAC address.
- Included Apache 2.0 license text and attribution for Kotlin runtime and the AndroidX protocol adapter.

## Implemented behavior and deliberate limitations

The master toggle starts OFF. The user selects the native BMW by paired Bluetooth address. A newly
observed cellular call's ACTIVE transition triggers a short policy window, only with verified
projection, the configured HFP device, Telecom authorization, and accepted call safety evidence.
The exact-device request is made through Telecom, not through AudioManager or direct HFP mutation.

There are at most three TOTAL automatic requests (initial plus two retries) in a four-second startup
window. Retries require the user-selected competing head-unit Bluetooth identity. With no competitor
configured there is one initial attempt and no takeover retries. Speaker, handset, wired devices,
and other Bluetooth headsets stop enforcement when observed as a route change. Initial human intent
cannot be perfectly inferred, particularly when it produces the same route as an automatic takeover.
No continuously running route-enforcement loop is used.

Emergency/unknown-emergency, anonymous/withheld-number, conference/multi-call, external/self-managed
and non-SIM calls are conservatively excluded. Resuming a held call or rebinding to an already-active
call does not restart automatic takeover. This avoids fighting a prior manual choice but means a late
service bind can miss automatic routing. 'Route now' explicitly permits a one-shot test; it bypasses
only the master/projection gates, NOT authorization, safety, target identity or device availability.

A void routing API return is logged as submission, not success. Telecom route callbacks and HFP/SCO
observations are recorded separately; only an actual two-way phone call can validate audio quality.
The app never requests A2DP changes, Bluetooth disconnection, microphone recording, or a replacement
dialer role. There is no INTERNET permission. Logs use salted device aliases, not numbers/raw MACs.

The four-second window and request intervals are provisional policy choices, NOT measurements of
this head unit's behavior. Reboot persistence, Samsung idle behavior and the 1–2 second goal remain
device acceptance tests, not verified promises.

## Activation / rollback

APK installation alone does not grant the protected Telecom access. From a paired, authorized LOCAL
ADB shell on the phone (for example LADB over Wireless Debugging), execute:

```text
cmd appops set --uid com.itaymatza.carcallrouter MANAGE_ONGOING_CALLS allow
```

A normal non-ADB terminal does not have this authority. User approval of Wireless Debugging pairing
is required; it cannot be supplied remotely by this APK. After authorization, refresh app status and
check `Telecom authorization: true`. Finish authorization before starting the test call.

To revoke from that same shell:

```text
cmd appops set --uid com.itaymatza.carcallrouter MANAGE_ONGOING_CALLS default
```

The OFF toggle prevents automatic requests. 'Pause for this call' stops new requests during an
existing session. Neither control can recall a request already submitted to Telecom.

See README.md for the parked one-shot test and phone-only setup. Keep Samsung Phone as default.
Do not make emergency test calls or test routing while driving.
