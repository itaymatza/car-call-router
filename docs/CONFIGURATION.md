# Configuration guidance

## Build identity

The Android Gradle module accepts three optional Gradle properties. They may be supplied through a local Gradle properties file outside the repository or as `-P` arguments in a build command.

| Property | Default | Purpose |
|---|---|---|
| `APP_APPLICATION_ID` | `org.carcallrouter.companion` | Installable Android application ID. Choose an ID controlled by the build owner. |
| `APP_VERSION_CODE` | `1` | Monotonically increasing Android version code. |
| `APP_VERSION_NAME` | `0.1.0` | Human-readable version string. |

Example:

```sh
bash gradlew \
  -PAPP_APPLICATION_ID=example.callroute \
  -PAPP_VERSION_CODE=3 \
  -PAPP_VERSION_NAME=0.3.0 \
  :app:assembleDebug
```

Changing the application ID creates a distinct Android application. Any protected authorization must therefore be performed for the exact application ID installed on the device. The generic source namespace is intentionally fixed; changing it requires an explicit source-package migration, not a Gradle property.

## Runtime choices

At runtime, a person configuring a test device chooses the target Bluetooth call device from already paired devices. The app stores the chosen address and display name in application-private `SharedPreferences`; neither value is compiled into the source tree or transmitted by the app. An optional competing device can be selected to permit bounded reassertion only against that explicit route. When no competing device is set, the automatic path makes a single initial request instead of contesting a later route change.

The master automation toggle is disabled by default. It cannot be enabled until the target is selected, the required runtime permissions are granted, and the operating system reports that the protected ongoing-call authorization is present. A manual one-shot is available only while Telecom has bound the service, and it retains all authorization, call-safety, and target-identity requirements.

The permissions button is an explicit user action. Before distributing a customized build, make sure its user-facing explanation clearly states why it requests Bluetooth connection and phone-number access, and that a denial leaves routing disabled rather than blocking the rest of the application. Android recommends requesting runtime permissions in the context of the action that needs them and degrading gracefully when they are denied. [1]

## Call-routing authorization

After runtime permissions are granted and a target is selected, **Authorize call routing** asks Android's `CompanionDeviceManager` to associate this app with the selected Bluetooth device. The request uses an exact Bluetooth-address filter and stops discovery after that device is found. Android displays the system-owned consent UI; the user must confirm it. The app cannot silently accept this protected authorization. [3][4]

Keep the selected device nearby, powered on, and discoverable enough for Android to find it during this step. This association does not create a new Bluetooth pairing or connection. The target must already be paired in Android settings.

After Android creates the association, the app checks `TelecomManager.hasManageOngoingCallsPermission()` rather than assuming success. If Telecom access is present, setup advances automatically. If the association fails or the device's Android build does not grant call access through it, the app presents the local-ADB fallback command. This fallback still requires an authorized Wireless Debugging session on the phone or a connected computer; the app cannot execute that protected command itself.

Android documents companion-device access to `InCallService` for third-party wearable companion apps. A car head unit or a particular manufacturer build may not be accepted by the same path, so real-device verification remains necessary. The app fails closed when authorization is absent. [2]

Associations and protected authorization belong to the exact installed application ID. Uninstalling the app removes the association, and installing a build with a different application ID requires authorization again.

## Projection integration

Automatic mode uses the AndroidX car-app host-provider protocol as an optional projection-state gate. The monitor deliberately avoids inferring projection state from Bluetooth names, Wi-Fi networks, package process state, or broadcast extras. A missing, unknown, or lost projection state prevents new automatic route requests. The manual one-shot has an explicit projection-gate bypass for parked testing only.

## References

[1]: https://developer.android.com/training/permissions/requesting "Android Developers: Request runtime permissions"
[2]: https://developer.android.com/reference/android/telecom/InCallService#access-to-incallservice-for-wearable-devices "Android Developers: InCallService access for wearable devices"
[3]: https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing "Android Developers: Companion device pairing"
[4]: https://developer.android.com/reference/android/companion/BluetoothDeviceFilter.Builder#setAddress(java.lang.String) "Android Developers: exact Bluetooth address filter"
