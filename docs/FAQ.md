# Frequently asked questions

## What does the app change?

For an eligible active cellular call, it requests one of Android Telecom's currently available
Bluetooth call endpoints. It does not reroute music, navigation, notifications, or other Android
Auto media.

## Is this the same as manually selecting the car during a call?

That is the intended effect. The app requests the same live Telecom endpoint exposed by Android's
in-call audio routing. Exact speaker and microphone behavior must still be verified while parked;
an accepted API request alone is not physical-audio proof.

## Why is one-time ADB authorization required?

Android does not offer a normal runtime dialog for `MANAGE_ONGOING_CALLS`. A general utility also
cannot use the wearable-only companion grant path. This project keeps the existing Phone app as
the default dialer, so it verifies a one-time AppOps grant instead. See [Authorization](AUTHORIZATION.md).

## Does authorization survive an update?

It normally can when the application ID and signing certificate stay unchanged. Ordinary debug
builds from different machines or hosted runners are not guaranteed to share a certificate. Use
the protected signed-beta workflow for repeatable upgrades, and verify authorization after every
upgrade.

## Why did the app refuse to route?

Refusal is expected whenever authorization, call classification, endpoint identity, projection,
HFP state, or single-call safety cannot be established. Open Diagnostics and use the reason code;
do not add retries until the cause is classified. See [Testing](TESTING.md).

## Will it work with my phone or car?

Compatibility cannot be inferred from branding alone. First confirm that manually choosing the
preferred Bluetooth device in the active-call audio selector works while Android Auto remains
connected. Then follow the parked one-shot test in [Testing](TESTING.md) and report the result using
the compatibility issue form.

## Does this require root, Shizuku, or becoming the default dialer?

No. The current design uses a non-UI `InCallService`, runtime Bluetooth permissions, and a one-time
ADB AppOps grant. Root and Shizuku are not part of the supported path, and the app deliberately
does not replace the Phone UI.

## Is it production-ready?

No. The core behavior has a real-device proof of concept, but the full stability matrix and signed
upgrade verification remain release blockers. Treat every build as beta software and test only
while parked.
