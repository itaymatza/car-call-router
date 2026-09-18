# Car Call Router 0.2.0 — signed device-test build

A real signed APK has been built: **CarCallRouter-0.2.0.apk**. Android14+; approximately2.11MB.
This is a device-test build, not yet a proven fix on your Samsung/BMW.

No PC, Android Studio or GitHub repository is required to install the supplied APK. Android's
protected call-control access does require a separate, one-time authorization on your phone.

## Phone-only installation and authorization

1. Open the supplied APK on your phone and install it. Keep Samsung Phone as your default dialer.
   Open Car Call Router; grant its requested Nearby devices and Phone permissions. Leave Auto-route OFF.
2. Use a paired local ADB shell on that same phone, for example LADB through Wireless Debugging.
   This is NOT an ordinary terminal. LADB's upstream setup instructions are at
   https://github.com/tytydraco/LADB ; the official store listing linked by its developer is
   https://play.google.com/store/apps/details?id=com.draco.ladb . Availability/price may vary.
   The developer warns of incompatibility with Shizuku; do not install both as one combined setup.
3. Enable Developer options and Wireless Debugging on a trusted Wi-Fi connection. Pair LADB with
   Wireless Debugging's 'Pair device with pairing code' dialog. Use split-screen/pop-up so the code
   dialog stays open; enter its PAIRING port and code, not an unrelated connection port. These actions
   must be approved on the phone. No pairing code or password needs to be sent to this conversation.
4. Run the following command inside the authorized local ADB shell. The app's command button copies it:

```text
cmd appops set --uid com.itaymatza.carcallrouter MANAGE_ONGOING_CALLS allow
```

5. Return to the app and refresh. Confirm `Telecom authorization: true`. Authorize BEFORE starting
   a test call. Installation alone does not make this value true. A denied or unavailable operation
   is a device-specific failure to diagnose, not a reason to assume routing works.

The app does not need a continuously running LADB/Shizuku helper for routing. After authorization,
Wireless Debugging can be turned off. Verify authorization again after reboot; persistence on this
Samsung build has not yet been tested. Do not turn off unrelated security protections blindly.

## First test — while parked

Select the original/native BMW in 'Native BMW call device'. Do not choose the aftermarket unit as the
target. It is saved by Bluetooth address, not by a guessed name or list order.

Connect BMW and Android Auto. Start an ordinary cellular call with a visible, non-emergency number,
let it use the bad route, then tap 'Route now — one-shot test'. Confirm that you hear the caller through
BMW, the other person hears you through BMW's microphone, and Android Auto stays connected.

Only after that succeeds, optionally select the BAD aftermarket Bluetooth hands-free device as the
competing device (when it really appears among paired devices), then turn automatic routing ON and
test fresh incoming and outgoing calls. With no competitor configured, the controller attempts one
initial BMW switch and does not reapply routing against an unidentified competitor. Do not guess a
Wi-Fi address or select Galaxy Buds as the competing car.

## What is implemented

Non-UI InCallService; exact-device Telecom request; true observed ACTIVE transition; HFP availability;
AndroidX car-connection provider protocol; bounded event-driven stabilization; master OFF/ON; pause
for the call; one-shot actuator test; settings; privacy-conscious persistent logs and log export.
It does not replace the dialer, disconnect devices, manipulate media/A2DP, record audio or access the
internet. A normal Android native UI is used; no Compose runtime is required.

The exact-MAC backend intentionally uses the public, deprecated requestBluetoothAudio API. A public
CallEndpoint identifier is not a MAC address, so the application does not pretend to match the two.
No hidden Binder transaction IDs or root hooks are used.

## What has actually passed

- Full app/resource/DEX compilation and packaging, plus cryptographic signature verification.
- 62 executable core-policy/safety checks and 5,000 seeded policy traces.
- ZIP, DEX, alignment, permission/service, class-namespace and routing-call inspection.

NOT tested: installation/UI on a real phone; Samsung service admission; real BMW audio; projection
provider response on your setup; idle/reboot behavior; race timing. No emulator/device was available.
The binary was built with an explicit aapt2/javac/kotlinc/D8/apksig pipeline, not a completed Gradle or
lint run. See docs/BUILD-VERIFICATION-0.2.0.md and its raw logs in docs/verification-0.2.0/.

## Current safety limits

Emergency or unclassifiable calls, private/anonymous numbers, multiple calls/conferences and non-SIM
VoIP calls are skipped. An already-active call on a late service bind will not be taken over
unexpectedly; the one-shot button is available for a deliberate test. At most three TOTAL automatic
requests occur within a four-second startup window. These timings are provisional, not measured
Android Auto behavior. Once paused or outside the startup window, the controller does not keep
fighting user choices. A request already submitted to Telecom cannot be recalled.

## Diagnostics and rollback

Use Export diagnostics after a failed test. The log includes call transitions, HFP/Telecom state,
projection, requests and observed results. Device addresses are salted aliases; phone numbers and
call audio are not recorded. A successful request submission is NOT logged as proof of a working mic.

Turn the master toggle OFF to stop automatic routing; use Pause for this call to stop new requests in
an existing session. Revoke protected access from an authorized shell with:

```text
cmd appops set --uid com.itaymatza.carcallrouter MANAGE_ONGOING_CALLS default
```

## Rebuilding from source (not required to install the supplied APK)

Open this project in Android Studio with Android SDK Platform36 and JDK17+; targetSDK35/minSDK34.
The supplied Gradle launcher bootstraps pinned Gradle8.13. The Gradle build configuration pins AGP8.13.2
and Kotlin2.2.21. With normal SDK/dependency access, run:

```text
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

That exact Gradle route was NOT executed successfully in the constrained delivery environment.
The delivered APK used local Kotlin1.9.0 and the component provenance documented in the verification
report. Conventional rebuilt APKs will differ in compiler metadata and signing key. A different
signing key cannot update an existing installation in place without an intentional key migration or
uninstall/reinstall. The personal signing key is not included in this public source package.

Core tests independent of Android SDK:

```text
bash tools/test-core.sh
bash tools/test-properties.sh
```

Historical v0.1.1 docs are in docs/history-0.1.1/ and describe an earlier failed build, NOT this binary.
The app includes Apache2.0 license text and attributions in app/src/main/assets/.
